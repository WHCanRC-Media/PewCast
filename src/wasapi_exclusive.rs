//! WASAPI exclusive-mode audio capture for lowest possible latency.
//!
//! Bypasses the Windows Audio Engine entirely, talking directly to the
//! hardware. This eliminates the ~10-15ms of buffering that shared mode adds.
//!
//! Only compiled on Windows.

use std::sync::mpsc;

use tokio::sync::broadcast;
use tracing::{info, warn};
use windows::core::PCWSTR;
use windows::Win32::Media::Audio::*;
use windows::Win32::System::Com::*;
use windows::Win32::System::Threading::*;

use crate::audio::AudioChunk;

/// Try to start WASAPI exclusive-mode capture. On success, blocks until
/// `stop_rx` signals (returns `Ok`). On init failure, returns `Err` and
/// the caller should fall back to shared mode.
pub fn start_exclusive_capture(
    device_name: Option<&str>,
    stop_rx: mpsc::Receiver<()>,
    tx: broadcast::Sender<AudioChunk>,
    sample_rate: u32,
    channels: u16,
) -> anyhow::Result<()> {
    let src = WasapiExclusiveSource { stop_rx };
    unsafe { src.start_capture_inner(device_name, tx, sample_rate, channels) }
}

struct WasapiExclusiveSource {
    stop_rx: mpsc::Receiver<()>,
}

impl WasapiExclusiveSource {
    unsafe fn start_capture_inner(
        &self,
        device_name: Option<&str>,
        tx: broadcast::Sender<AudioChunk>,
        sample_rate: u32,
        channels: u16,
    ) -> anyhow::Result<()> {
        // Initialize COM for this thread (ignore S_FALSE if already initialized)
        let hr = CoInitializeEx(None, COINIT_MULTITHREADED);
        if hr.is_err() {
            return Err(anyhow::anyhow!("CoInitializeEx failed: {:?}", hr));
        }
        // Get the audio device
        let enumerator: IMMDeviceEnumerator =
            CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)?;

        let device = if let Some(name) = device_name {
            find_device_by_name(&enumerator, name)?
        } else {
            enumerator.GetDefaultAudioEndpoint(eCapture, eConsole)?
        };

        let friendly_name = get_device_friendly_name(&device).unwrap_or_else(|| "Unknown".into());
        info!("WASAPI exclusive: using device '{}'", friendly_name);

        // Activate IAudioClient
        let audio_client: IAudioClient = device.Activate(CLSCTX_ALL, None)?;

        // Get the device's native/mix format
        let mix_format_ptr = audio_client.GetMixFormat()?;
        let mix_format = &*mix_format_ptr;

        let native_sr = mix_format.nSamplesPerSec;
        let native_ch = mix_format.nChannels;
        let native_bits = mix_format.wBitsPerSample;

        info!(
            "WASAPI exclusive: device native format: {}Hz, {}ch, {}bit",
            native_sr, native_ch, native_bits
        );

        // For exclusive mode we use the device's native format
        // Check if the format is supported in exclusive mode
        let format_ok = audio_client
            .IsFormatSupported(
                AUDCLNT_SHAREMODE_EXCLUSIVE,
                mix_format as *const WAVEFORMATEX,
                None,
            )
            .is_ok();

        if !format_ok {
            // Try a simpler PCM format at the device's native sample rate
            let simple_format = build_pcm_format(native_sr, native_ch, 16);
            let simple_ok = audio_client
                .IsFormatSupported(
                    AUDCLNT_SHAREMODE_EXCLUSIVE,
                    &simple_format as *const WAVEFORMATEX,
                    None,
                )
                .is_ok();
            if !simple_ok {
                CoTaskMemFree(Some(mix_format_ptr as *const _ as *const _));
                return Err(anyhow::anyhow!(
                    "Device does not support exclusive mode with any tested format"
                ));
            }
            // Use the simple format instead — copy it over mix_format_ptr
            // We'll just use the simple format directly below
            CoTaskMemFree(Some(mix_format_ptr as *const _ as *const _));
            return self.capture_with_format(
                &audio_client,
                &simple_format,
                tx,
                sample_rate,
                channels,
            );
        }

        let result = self.capture_with_format(&audio_client, mix_format, tx, sample_rate, channels);

        CoTaskMemFree(Some(mix_format_ptr as *const _ as *const _));
        result
    }

    unsafe fn capture_with_format(
        &self,
        audio_client: &IAudioClient,
        format: &WAVEFORMATEX,
        tx: broadcast::Sender<AudioChunk>,
        target_sr: u32,
        target_ch: u16,
    ) -> anyhow::Result<()> {
        let actual_sr = format.nSamplesPerSec;
        let actual_ch = format.nChannels;
        let bits = format.wBitsPerSample;

        // Get minimum device period for exclusive mode
        let mut default_period = 0i64;
        let mut min_period = 0i64;
        audio_client.GetDevicePeriod(Some(&mut default_period), Some(&mut min_period))?;

        // Use the minimum period for lowest latency
        let period = if min_period > 0 {
            min_period
        } else {
            default_period
        };

        info!(
            "WASAPI exclusive: minimum period = {:.2}ms",
            period as f64 / 10_000.0
        );

        // Initialize in exclusive mode with event-driven buffering
        let init_result = audio_client.Initialize(
            AUDCLNT_SHAREMODE_EXCLUSIVE,
            AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
            period,
            period,
            format as *const WAVEFORMATEX,
            None,
        );

        // Handle AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED
        if let Err(ref e) = init_result {
            if e.code().0 as u32 == 0x88890019 {
                // AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED
                let aligned_size = audio_client.GetBufferSize()?;
                let aligned_period =
                    (10_000_000.0 * aligned_size as f64 / actual_sr as f64 + 0.5) as i64;

                info!(
                    "WASAPI exclusive: re-initializing with aligned buffer ({} frames, {:.2}ms)",
                    aligned_size,
                    aligned_period as f64 / 10_000.0
                );

                audio_client.Initialize(
                    AUDCLNT_SHAREMODE_EXCLUSIVE,
                    AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
                    aligned_period,
                    aligned_period,
                    format as *const WAVEFORMATEX,
                    None,
                )?;
            } else {
                init_result?;
            }
        }

        let buffer_size = audio_client.GetBufferSize()?;
        info!(
            "WASAPI exclusive: buffer = {} frames ({:.2}ms)",
            buffer_size,
            buffer_size as f64 / actual_sr as f64 * 1000.0
        );

        // Create event for buffer-ready notifications
        let event = CreateEventW(None, false, false, PCWSTR::null())?;
        audio_client.SetEventHandle(event)?;

        // Get capture client
        let capture_client: IAudioCaptureClient = audio_client.GetService()?;

        // Start the stream
        audio_client.Start()?;
        info!(
            "WASAPI exclusive: capture started ({}Hz, {}ch, {}bit)",
            actual_sr, actual_ch, bits
        );

        let needs_resample = actual_sr != target_sr;
        let needs_downmix = actual_ch > target_ch;

        if needs_resample {
            info!(
                "WASAPI exclusive: resampling {}Hz -> {}Hz",
                actual_sr, target_sr
            );
        }
        if needs_downmix {
            info!(
                "WASAPI exclusive: downmixing {}ch -> {}ch",
                actual_ch, target_ch
            );
        }

        // Capture loop
        loop {
            // Check for stop signal (non-blocking)
            if self.stop_rx.try_recv().is_ok() {
                info!("WASAPI exclusive: stop signal received");
                break;
            }

            // Wait for buffer event (timeout 100ms to allow checking stop signal)
            let wait_result = WaitForSingleObject(event, 100);
            if wait_result.0 == 0x00000102 {
                // WAIT_TIMEOUT
                continue;
            }

            // Read available frames
            let mut packet_length = capture_client.GetNextPacketSize()?;

            while packet_length > 0 {
                let mut buffer_ptr = std::ptr::null_mut();
                let mut num_frames = 0u32;
                let mut flags = 0u32;
                let mut _device_position = 0u64;
                let mut _qpc_position = 0u64;

                capture_client.GetBuffer(
                    &mut buffer_ptr,
                    &mut num_frames,
                    &mut flags,
                    Some(&mut _device_position),
                    Some(&mut _qpc_position),
                )?;

                let f32_samples = if flags & (AUDCLNT_BUFFERFLAGS_SILENT.0 as u32) != 0 {
                    // Silent buffer — produce zeros
                    vec![0.0f32; num_frames as usize]
                } else {
                    convert_to_f32(buffer_ptr, num_frames, actual_ch, bits)
                };

                capture_client.ReleaseBuffer(num_frames)?;

                // Downmix to mono if needed
                let mono = if needs_downmix {
                    f32_samples
                        .chunks(actual_ch as usize)
                        .map(|frame| frame.iter().sum::<f32>() / actual_ch as f32)
                        .collect()
                } else {
                    f32_samples
                };

                // Resample if needed
                let final_f32 = if needs_resample {
                    resample(&mono, actual_sr, target_sr)
                } else {
                    mono
                };

                // Convert to i16
                let samples: Vec<i16> = final_f32
                    .iter()
                    .map(|&s| (s.clamp(-1.0, 1.0) * i16::MAX as f32) as i16)
                    .collect();

                let chunk = AudioChunk {
                    samples,
                    sample_rate: target_sr,
                    channels: target_ch,
                };
                let _ = tx.send(chunk);

                packet_length = capture_client.GetNextPacketSize()?;
            }
        }

        audio_client.Stop()?;
        let _ = windows::Win32::Foundation::CloseHandle(event);
        info!("WASAPI exclusive: capture stopped");
        Ok(())
    }
}

/// Find a capture device by its friendly name.
unsafe fn find_device_by_name(
    enumerator: &IMMDeviceEnumerator,
    target_name: &str,
) -> anyhow::Result<IMMDevice> {
    let collection = enumerator.EnumAudioEndpoints(eCapture, DEVICE_STATE_ACTIVE)?;
    let count = collection.GetCount()?;

    for i in 0..count {
        let device = collection.Item(i)?;
        if let Some(name) = get_device_friendly_name(&device) {
            if name == target_name {
                return Ok(device);
            }
        }
    }

    Err(anyhow::anyhow!(
        "Audio device '{}' not found for exclusive mode",
        target_name
    ))
}

/// Get the friendly name of an audio device via its property store.
unsafe fn get_device_friendly_name(device: &IMMDevice) -> Option<String> {
    use windows::core::GUID;
    use windows::Win32::Foundation::PROPERTYKEY;

    // PKEY_Device_FriendlyName = {a45c254e-df1c-4efd-8020-67d146a850e0}, 14
    let pkey = PROPERTYKEY {
        fmtid: GUID::from_values(
            0xa45c254e,
            0xdf1c,
            0x4efd,
            [0x80, 0x20, 0x67, 0xd1, 0x46, 0xa8, 0x50, 0xe0],
        ),
        pid: 14,
    };

    let store = device.OpenPropertyStore(STGM_READ).ok()?;
    let prop = store.GetValue(&pkey).ok()?;

    // VT_LPWSTR = 31
    let vt = prop.Anonymous.Anonymous.vt;
    if vt.0 == 31 {
        let pwstr = prop.Anonymous.Anonymous.Anonymous.pwszVal;
        if !pwstr.is_null() {
            return pwstr.to_string().ok();
        }
    }

    None
}

/// Build a simple PCM WAVEFORMATEX.
fn build_pcm_format(sample_rate: u32, channels: u16, bits_per_sample: u16) -> WAVEFORMATEX {
    let block_align = channels * (bits_per_sample / 8);
    WAVEFORMATEX {
        wFormatTag: WAVE_FORMAT_PCM as u16, // 1
        nChannels: channels,
        nSamplesPerSec: sample_rate,
        nAvgBytesPerSec: sample_rate * block_align as u32,
        nBlockAlign: block_align,
        wBitsPerSample: bits_per_sample,
        cbSize: 0,
    }
}

/// Convert raw audio buffer to f32 samples based on bit depth.
unsafe fn convert_to_f32(
    buffer: *const u8,
    num_frames: u32,
    channels: u16,
    bits_per_sample: u16,
) -> Vec<f32> {
    let total_samples = num_frames as usize * channels as usize;

    match bits_per_sample {
        16 => {
            let ptr = buffer as *const i16;
            let slice = std::slice::from_raw_parts(ptr, total_samples);
            slice.iter().map(|&s| s as f32 / 32768.0).collect()
        }
        24 => {
            // 24-bit samples are packed as 3 bytes each (little-endian)
            let mut samples = Vec::with_capacity(total_samples);
            for i in 0..total_samples {
                let offset = i * 3;
                let b0 = *buffer.add(offset) as i32;
                let b1 = *buffer.add(offset + 1) as i32;
                let b2 = *buffer.add(offset + 2) as i32;
                let value = (b0 | (b1 << 8) | (b2 << 16)) - if b2 >= 128 { 0x01000000 } else { 0 };
                samples.push(value as f32 / 8_388_608.0);
            }
            samples
        }
        32 => {
            // Assume IEEE float
            let ptr = buffer as *const f32;
            let slice = std::slice::from_raw_parts(ptr, total_samples);
            slice.to_vec()
        }
        _ => {
            warn!(
                "WASAPI exclusive: unsupported bit depth {}, producing silence",
                bits_per_sample
            );
            vec![0.0; total_samples]
        }
    }
}

/// Linear interpolation resampling (same algorithm as cpal path in audio.rs).
fn resample(samples: &[f32], from_rate: u32, to_rate: u32) -> Vec<f32> {
    let ratio = to_rate as f64 / from_rate as f64;
    let out_len = (samples.len() as f64 * ratio) as usize;
    (0..out_len)
        .map(|i| {
            let src_pos = i as f64 / ratio;
            let idx = src_pos as usize;
            let frac = src_pos - idx as f64;
            let a = samples[idx.min(samples.len() - 1)];
            let b = samples[(idx + 1).min(samples.len() - 1)];
            a + (b - a) * frac as f32
        })
        .collect()
}
