| Stage            | Shared + WT | Exclusive + WT |
|------------------|-------------|----------------|
| *Capture*          | *~15ms*       | *3ms*            |
| Encode           | 5ms         | 5ms            |
| *Network (1-way)*  | *~8ms*        | *~8ms*           |
| Decode           | 5ms         | 5ms            |
| Jitter Buffer    | 30ms        | 30ms           |
| *Playout*          | *~20-40ms*    | *~20-40ms*       |
| **Total**        | **~83-103ms** | **~71-91ms** |

The assisted listening pipe is 
Capture -> Encode -> Network -> Decode -> Jitter Buffer -> Playout

The latency test is is
Playout -> Capture -> Encode -> Network -> Decode 
(Missing Jitter buffer so should be shorter)

### Measured Results (2026-04-03)

Server: 48kHz mono, Corsair HS65 SURROUND mic.
Client: Pixel phone, WiFi, phone speaker 3cm from mic.

| Metric                     | Chrome Exclusive | Chrome Shared | Firefox Exclusive |
|----------------------------|------------------|---------------|-------------------|
| Chirp RTT p50              | 96ms             | 120ms         | 120ms             |
| Chirp RTT range            | 88–105ms         | 91–133ms      | 93–142ms          |
| Audio Session RTT p50      | 14.8ms           | 17.4ms        | 16.0ms            |
| One-way network (RTT/2)    | 7.4ms            | 8.7ms         | 8.0ms             |
| AudioContext.outputLatency  | 32.0ms           | 32.0ms        | 33.3ms            |
| AudioContext.baseLatency    | 4.0ms            | 4.0ms         | 0.0ms             |

**Exclusive → Shared comparison (Chrome):** Switching to shared mode added
~24ms to chirp RTT (96→120ms p50), with wider spread (17ms→42ms range).
This exceeds the expected ~12ms from the budget table (5ms buffer + ~10ms
mixing), likely because shared mode fell back to the device default buffer
size rather than the 240-sample target.

The chirp test path is: phone speaker output → air → mic → WASAPI capture →
WT datagram → network → JS chirp detector (no jitter buffer, no playout).

Expected chirp RTT from known stages: ~52ms (32ms speaker + 3ms capture +
5ms WT fragmentation + 7ms network + 5ms detection). Measured 96ms (Chrome
exclusive), leaving ~44ms unaccounted — likely Android audio HAL output
buffering below what `AudioContext.outputLatency` reports.

Firefox adds ~24ms more than Chrome with similar `outputLatency`, suggesting
additional internal output buffering. Firefox also has wider jitter (49ms
spread vs Chrome's 17ms). Chrome shared mode (120ms) matches Firefox
exclusive (120ms) — the capture overhead difference roughly equals Firefox's
extra speaker output latency.

### Notes
- **Capture shared**: 240 samples / 48kHz = 5ms buffer, plus ~10ms Windows Audio Engine mixing
- **Capture exclusive**: 144 frames / 48kHz = 3ms, bypasses audio engine entirely
- **Encode**: Opus 5ms frames, LowDelay mode, 32kbps, complexity 5
- **Network**: Measured RTT 7-28ms over WiFi (median ~15ms), one-way ~8ms
- **Decode**: Opus frame = 5ms
- **Jitter buffer**: Default 30ms, adjustable 5-200ms via slider
- **WebRTC**: Not optimized for latency; uses best-effort delivery with browser-controlled jitter buffering
- **Playout**: `latencyHint: 'interactive'` but real output latency is ~20-40ms on mobile (wired/speaker), ~10-20ms on desktop (check `AudioContext.outputLatency`)
- **Exclusive mode saves ~12ms** on the capture side
