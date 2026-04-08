#pragma once

/**
 * ChirpTester - Round-trip audio latency measurement
 *
 * Measures the time for audio to travel:
 *   Phone speaker -> Air -> Server mic -> Network -> Phone -> Detect
 *
 * Usage:
 *   ChirpTester tester;
 *   tester.startTest();
 *   // Poll getLatencyMs() until != -1
 *   int latency = tester.getLatencyMs();  // -2 = timeout, >0 = ms
 *   tester.stopTest();
 */

#include <atomic>
#include <memory>
#include <thread>
#include <vector>
#include <oboe/Oboe.h>

namespace audioengine {

class ChirpTester : public oboe::AudioStreamDataCallback {
public:
    ChirpTester();
    ~ChirpTester();

    /// Open the Oboe audio stream. Call once before startTest().
    /// Returns false if setup fails.
    bool init();

    /// Close the audio stream. Safe to call multiple times.
    void shutdown();

    /// Start latency test. Returns false if already running or not initialized.
    /// listenPort: local UDP port to receive audio on (0 = auto-assign).
    /// Call getLatencyMs() to poll for results.
    bool startTest(int listenPort = 0);

    /// Returns the local UDP port bound for this test (valid after startTest).
    int getListenPort() const { return mListenPort; }

    /// Stop the current test (keeps stream alive for reuse).
    void stopTest();

    /// Get test result:
    ///   -1 = test in progress
    ///   -2 = timeout (no chirp detected within 2 seconds)
    ///   >0 = measured round-trip latency in milliseconds
    int getLatencyMs() const;

    /// Returns true if test is currently running.
    bool isRunning() const;

private:
    // Oboe callback - fills output buffer with chirp samples
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames) override;

    // Network receive thread - listens for and detects chirp in server audio
    void networkThread();

    // Cross-correlate chirp template against audio buffer, return peak
    // correlation value. Sets peakPos to sample offset of peak within buffer.
    float crossCorrelate(const float* buffer, int bufferLen, int& peakPos);

    // ==================== CONSTANTS ====================

    static constexpr int kSampleRate = 48000;      // Audio sample rate (Hz)
    static constexpr int kChannels = 1;            // Mono audio

    // Chirp parameters
    static constexpr int kChirpDurationMs = 5;     // Chirp length (ms)
    static constexpr int kChirpSamples = kSampleRate * kChirpDurationMs / 1000;  // = 240 samples
    static constexpr float kChirpStartFreq = 1000.0f;  // Frequency sweep start (Hz)
    static constexpr float kChirpEndFreq = 4000.0f;    // Frequency sweep end (Hz)

    // Network parameters
    static constexpr int kHeaderSize = 8;          // [seq:4][timestamp:4]
    static constexpr int kMaxSamplesPerDatagram = 240;
    static constexpr int kMaxPacketSize = kHeaderSize + kMaxSamplesPerDatagram * 2;

    // Test timeout
    static constexpr int kTimeoutMs = 2000;        // Give up after 2 seconds

    // Cross-correlation detection threshold (normalized, 0.0 to 1.0)
    static constexpr float kCorrelationThreshold = 0.4f;

    // ==================== STATE ====================

    // Chirp playback (written by audio thread)
    std::vector<float> mChirpWaveform;             // Pre-generated chirp samples
    float mChirpEnergy = 0.0f;                     // Precomputed sum-of-squares of chirp template
    std::atomic<int> mChirpPlayPos{0};             // Current position in chirp
    std::atomic<bool> mPlaying{false};             // Is chirp currently playing?

    // Timing and detection (shared between audio and network threads)
    std::atomic<int64_t> mChirpStartTimeNs{0};     // Timestamp when chirp started playing
    std::atomic<int> mLatencyMs{-1};               // Result: -1=running, -2=timeout, >0=latency

    // Cross-correlation state (used only by network thread)
    std::vector<float> mPrevFrame;                 // Previous decoded frame for cross-frame detection
    int64_t mPrevFrameTimeNs = 0;                  // Receive timestamp of previous frame

    // Resources (persistent across tests)
    bool mInitialized = false;                     // Whether init() succeeded

    // Resources (per-test)
    int mSocket = -1;                              // UDP unicast socket
    int mListenPort = 0;                           // Bound local port

    // Threading
    std::atomic<bool> mRunning{false};             // Test running flag
    std::thread mNetworkThread;                    // Receives and analyzes server audio

    // Audio output
    std::shared_ptr<oboe::AudioStream> mStream;   // Oboe output stream for chirp
};

}  // namespace audioengine
