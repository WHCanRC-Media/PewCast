//! Native UDP unicast audio sender.
//!
//! Sends raw PCM i16 samples (same format as the WebTransport transport)
//! to all registered native clients over plain UDP.
//!
//! Datagram format: [seq: u32 LE] [timestamp: u32 LE] [pcm i16 LE samples...]

use std::net::SocketAddr;
use std::sync::Arc;

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

/// Run the UDP unicast audio sender.
///
/// Subscribes to the audio broadcast channel, fragments chunks into
/// fixed-size datagrams, and sends to all registered clients.
pub async fn run(
    mut audio_rx: broadcast::Receiver<AudioChunk>,
    registry: Arc<ClientRegistry>,
    udp_port: u16,
) {
    let bind_addr: SocketAddr = ([0, 0, 0, 0], udp_port).into();
    let socket = match qos::create_qos_socket(bind_addr) {
        Ok(s) => s,
        Err(e) => {
            error!("Failed to create QoS socket for UDP sender: {}", e);
            return;
        }
    };

    info!(
        "UDP unicast sender started on port {} (raw PCM i16, max {} samples/datagram)",
        udp_port, MAX_SAMPLES_PER_DATAGRAM
    );

    let mut seq: u32 = 0;
    let mut sample_offset: u32 = 0;
    let mut send_count: u64 = 0;

    loop {
        match audio_rx.recv().await {
            Ok(chunk) => {
                if chunk.samples.is_empty() {
                    continue;
                }

                let addrs = registry.get_addrs();
                if addrs.is_empty() {
                    // No clients — still advance seq/offset to stay consistent
                    let num_samples = chunk.samples.len();
                    let num_datagrams = num_samples.div_ceil(MAX_SAMPLES_PER_DATAGRAM);
                    seq = seq.wrapping_add(num_datagrams as u32);
                    sample_offset = sample_offset.wrapping_add(num_samples as u32);
                    continue;
                }

                for fragment in chunk.samples.chunks(MAX_SAMPLES_PER_DATAGRAM) {
                    let pcm_len = fragment.len() * 2;
                    let mut datagram = Vec::with_capacity(HEADER_SIZE + pcm_len);

                    // Header
                    datagram.extend_from_slice(&seq.to_le_bytes());
                    datagram.extend_from_slice(&sample_offset.to_le_bytes());

                    // PCM payload: i16 LE
                    for &s in fragment {
                        datagram.extend_from_slice(&s.to_le_bytes());
                    }

                    // Send to all registered clients
                    for addr in &addrs {
                        if let Err(e) = socket.send_to(&datagram, addr) {
                            warn!("Failed to send UDP to {}: {}", addr, e);
                        }
                    }

                    seq = seq.wrapping_add(1);
                    sample_offset = sample_offset.wrapping_add(fragment.len() as u32);
                    send_count += 1;
                }

                if send_count.is_multiple_of(500) {
                    debug!(
                        "[UDP] Sent {} datagrams (seq={}, ts={}, clients={})",
                        send_count,
                        seq,
                        sample_offset,
                        addrs.len(),
                    );
                }
            }
            Err(broadcast::error::RecvError::Lagged(n)) => {
                warn!("UDP audio receiver lagged, dropped {} chunks", n);
            }
            Err(broadcast::error::RecvError::Closed) => {
                info!("Audio channel closed, stopping UDP sender");
                break;
            }
        }
    }
}
