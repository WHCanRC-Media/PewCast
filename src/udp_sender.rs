//! Native UDP unicast audio sender with precision pacing.
//!
//! Audio chunks from CPAL are accumulated in a shared PCM buffer.
//! A dedicated high-priority thread wakes at precise frame intervals
//! using spin_sleep, fragments PCM into fixed-size datagrams, and sends
//! to all registered clients.
//!
//! Datagram format: [seq: u32 LE] [timestamp: u32 LE] [pcm i16 LE samples...]

use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::Mutex;
use std::time::Instant;

use tokio::sync::broadcast;
use tracing::{debug, error, info, warn};

use crate::audio::AudioChunk;
use crate::client_registry::ClientRegistry;
use crate::qos;

/// 8-byte header: seq (4) + timestamp (4).
const HEADER_SIZE: usize = 8;

/// Max PCM samples per datagram. 8-byte header + 240 * 2 bytes = 488 bytes,
/// well within the UDP MTU (~1472 bytes on Ethernet).
const MAX_SAMPLES_PER_DATAGRAM: usize = 240;

/// Pacing interval: send one datagram every 5ms (240 samples at 48kHz).
const FRAME_DURATION: std::time::Duration = std::time::Duration::from_millis(5);

/// Size of a ping probe datagram: [seq(4) | last_rtt_us(4)].
/// Audio datagrams are always larger (header + PCM), so we can distinguish
/// ping probes by their exact 8-byte length.
const PING_PROBE_SIZE: usize = 8;

/// Run the UDP unicast audio sender.
///
/// Subscribes to the audio broadcast channel, buffers PCM, and sends
/// precision-paced datagrams to all registered clients.
/// Also echoes back 8-byte ping probes from clients for RTT measurement.
pub async fn run(
    mut audio_rx: broadcast::Receiver<AudioChunk>,
    registry: Arc<ClientRegistry>,
    udp_port: u16,
) {
    let bind_addr: SocketAddr = ([0, 0, 0, 0], udp_port).into();
    let socket = match qos::create_qos_socket(bind_addr) {
        Ok(s) => {
            s.set_nonblocking(true)
                .expect("Failed to set UDP socket nonblocking");
            s
        }
        Err(e) => {
            error!("Failed to create QoS socket for UDP sender: {}", e);
            return;
        }
    };

    let socket =
        tokio::net::UdpSocket::from_std(socket).expect("Failed to convert UDP socket to tokio");
    let socket = Arc::new(socket);

    // Spawn a task to echo ping probes from clients back.
    let ping_socket = Arc::clone(&socket);
    let ping_task = tokio::spawn(async move {
        let mut buf = [0u8; 1500];
        let mut count: u64 = 0;
        let start = std::time::Instant::now();
        loop {
            match ping_socket.recv_from(&mut buf).await {
                Ok((len, src)) => {
                    if len == PING_PROBE_SIZE {
                        if count.is_multiple_of(20) {
                            if let Ok(rtt_bytes) = buf[4..8].try_into() {
                                let rtt_us = u32::from_le_bytes(rtt_bytes);
                                if rtt_us > 0 {
                                    let rtt_ms = rtt_us as f64 / 1000.0;
                                    let elapsed = start.elapsed();
                                    info!(
                                        "UDP ping seq={}: RTT={:.1}ms ({} echoed, {:.1}s elapsed) from {}",
                                        u32::from_le_bytes(buf[0..4].try_into().unwrap()),
                                        rtt_ms, count, elapsed.as_secs_f64(), src
                                    );
                                }
                            }
                        }
                        if let Err(e) = ping_socket.send_to(&buf[..PING_PROBE_SIZE], src).await {
                            warn!("Failed to echo UDP ping to {}: {}", src, e);
                        }
                        count += 1;
                    }
                    // Non-ping datagrams from clients are ignored
                }
                Err(e) => {
                    warn!("UDP ping recv error: {}", e);
                    break;
                }
            }
        }
    });

    info!(
        "UDP unicast sender started on port {} (raw PCM i16, max {} samples/datagram, {}ms pacing, ping echo enabled)",
        udp_port, MAX_SAMPLES_PER_DATAGRAM, FRAME_DURATION.as_millis()
    );

    // Shared PCM buffer: audio thread pushes, pacing thread pops
    let pcm_buffer: Arc<Mutex<Vec<i16>>> =
        Arc::new(Mutex::new(Vec::with_capacity(MAX_SAMPLES_PER_DATAGRAM * 8)));
    let pcm_for_sender = pcm_buffer.clone();

    // Clone what the pacer thread needs
    let pacer_registry = Arc::clone(&registry);
    // Get the raw fd for the pacer thread to use std::net send_to (not tokio)
    let pacer_socket = match qos::create_qos_socket(([0, 0, 0, 0], 0u16).into()) {
        Ok(s) => s,
        Err(e) => {
            error!("Failed to create pacer socket: {}", e);
            return;
        }
    };

    // Spawn the precision-paced sender thread
    std::thread::Builder::new()
        .name("udp-pacer".into())
        .spawn(move || {
            // Set high priority for precise timing
            #[cfg(target_os = "linux")]
            unsafe {
                libc::setpriority(libc::PRIO_PROCESS, 0, -10);
            }

            let mut seq: u32 = 0;
            let mut sample_offset: u32 = 0;

            // Send timing instrumentation
            let mut last_send = Instant::now();
            let mut send_count: u64 = 0;
            let mut delta_sum_us: u64 = 0;
            let mut delta_sq_sum: f64 = 0.0;
            let mut min_delta_us: u64 = u64::MAX;
            let mut max_delta_us: u64 = 0;

            let mut next_send = Instant::now();

            loop {
                // Wait until next frame boundary
                let now = Instant::now();
                if next_send > now {
                    spin_sleep::sleep(next_send - now);
                }

                // Extract one frame from the shared buffer
                let frame: Option<Vec<i16>> = {
                    let mut buf = pcm_for_sender.lock().unwrap();
                    if buf.len() >= MAX_SAMPLES_PER_DATAGRAM {
                        // Cap buffer to prevent latency buildup from clock drift.
                        // Max 4 frames (~20ms at 5ms/frame).
                        let max_buf = MAX_SAMPLES_PER_DATAGRAM * 4;
                        if buf.len() > max_buf {
                            let skip = buf.len() - max_buf;
                            buf.drain(..skip);
                            debug!("[UDP] Dropped {} samples to prevent latency buildup", skip);
                        }
                        Some(buf.drain(..MAX_SAMPLES_PER_DATAGRAM).collect())
                    } else {
                        None
                    }
                };

                let Some(frame) = frame else {
                    // No data yet — wait a short time and retry
                    next_send = Instant::now() + FRAME_DURATION;
                    continue;
                };

                // Schedule next send relative to this one (tracks audio clock)
                next_send += FRAME_DURATION;

                // Build datagram: [seq:4] [timestamp:4] [pcm i16 LE...]
                let pcm_len = frame.len() * 2;
                let mut datagram = Vec::with_capacity(HEADER_SIZE + pcm_len);
                datagram.extend_from_slice(&seq.to_le_bytes());
                datagram.extend_from_slice(&sample_offset.to_le_bytes());
                for &s in &frame {
                    datagram.extend_from_slice(&s.to_le_bytes());
                }

                // Send to all registered clients
                let addrs = pacer_registry.get_addrs();
                for addr in &addrs {
                    if let Err(e) = pacer_socket.send_to(&datagram, addr) {
                        warn!("Failed to send UDP to {}: {}", addr, e);
                    }
                }

                if !addrs.is_empty() {
                    let now = Instant::now();
                    let delta_us = now.duration_since(last_send).as_micros() as u64;
                    last_send = now;

                    if send_count > 0 {
                        delta_sum_us += delta_us;
                        delta_sq_sum += (delta_us as f64).powi(2);
                        min_delta_us = min_delta_us.min(delta_us);
                        max_delta_us = max_delta_us.max(delta_us);
                    }
                    send_count += 1;

                    if send_count > 1 && send_count.is_multiple_of(500) {
                        let n = send_count - 1;
                        let mean_us = delta_sum_us as f64 / n as f64;
                        let variance = (delta_sq_sum / n as f64) - (mean_us * mean_us);
                        let stddev_ms = (variance.max(0.0).sqrt()) / 1000.0;
                        let mean_ms = mean_us / 1000.0;
                        let min_ms = min_delta_us as f64 / 1000.0;
                        let max_ms = max_delta_us as f64 / 1000.0;

                        let buf_remaining = pcm_for_sender.lock().unwrap().len();
                        let buf_ms = buf_remaining as f64 / 48000.0 * 1000.0;
                        debug!(
                            "[UDP] Send interval: mean={:.2}ms stddev={:.3}ms min={:.2}ms max={:.2}ms pcm_buf={:.1}ms clients={} (n={})",
                            mean_ms, stddev_ms, min_ms, max_ms, buf_ms, addrs.len(), n
                        );
                    }
                }

                seq = seq.wrapping_add(1);
                sample_offset = sample_offset.wrapping_add(frame.len() as u32);
            }
        })
        .expect("Failed to spawn UDP pacer thread");

    // Main async loop: receive audio chunks and push PCM to shared buffer
    loop {
        match audio_rx.recv().await {
            Ok(chunk) => {
                if chunk.samples.is_empty() {
                    continue;
                }
                let mut buf = pcm_buffer.lock().unwrap();
                buf.extend_from_slice(&chunk.samples);
            }
            Err(broadcast::error::RecvError::Lagged(n)) => {
                warn!("Audio receiver lagged, dropped {} chunks", n);
            }
            Err(broadcast::error::RecvError::Closed) => {
                info!("Audio channel closed, stopping UDP sender");
                break;
            }
        }
    }

    ping_task.abort();
}
