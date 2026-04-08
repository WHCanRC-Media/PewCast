#pragma once

/**
 * ChirpTester - Round-trip audio latency measurement with diagnostics
 *
 * Measures the time for audio to travel:
 *   Phone speaker -> Air -> Server mic -> Network -> Phone -> Detect
 *
 * Also runs UDP ping probes for network RTT measurement and tracks
 * packet loss via sequence numbers.
 *
 * Usage:
 *   ChirpTester tester;
 *   tester.startTest("192.168.1.100", 8082);
 *   // Poll getLatencyMs() until != -1
 *   int latency = tester.getLatencyMs();  // -2 = timeout, >0 = ms
 *   tester.stopTest();
 *   // Access diagnostics: getPingRttMs(), getPacketsReceived(), etc.
 */

#include <atomic>
#include <memory>
#include <mutex>
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

    /// Start latency test with diagnostics.
    /// serverAddr: server IP for sending ping probes (e.g., "192.168.1.100")
    /// serverUdpPort: server UDP port for ping probes
    /// listenPort: local UDP port to receive audio on (0 = auto-assign).
    /// Starts network threads and ping probes but does NOT play chirp yet.
    /// Call playChirp() after joining the server to trigger the measurement.
    bool startTest(const char* serverAddr, int serverUdpPort, int listenPort = 0);

    /// Trigger chirp playback. Call after server registration so audio is flowing.
    void playChirp();

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

    // ==================== DIAGNOSTICS ====================

    /// Get ping RTT measurements in microseconds (thread-safe copy).
    std::vector<int64_t> getPingRttUs();

    /// Get number of audio packets received during the test.
    int getPacketsReceived() const { return mPacketsReceived.load(); }

    /// Get number of audio packets lost (seq gaps) during the test.
    int getPacketsLost() const { return mPacketsLost.load(); }

    /// Get Oboe output latency in milliseconds, or -1 if unavailable.
    double getOutputLatencyMs() const;

private:
    // Oboe callback - fills output buffer with chirp samples
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames) override;

    // Network receive thread - listens for and detects chirp in server audio
    void networkThread();

    // Ping probe sending thread
    void pingThread();

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
    static constexpr int kPingProbeSize = 8;       // [seq:4][last_rtt_us:4]

    // Test timeout
    static constexpr int kTimeoutMs = 2000;        // Give up after 2 seconds

    // Ping probe interval
    static constexpr int kPingIntervalMs = 50;     // Send a ping every 50ms

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

    // Server address for ping probes
    std::string mServerAddr;
    int mServerUdpPort = 0;

    // Ping probe state
    std::atomic<uint32_t> mPingSeq{0};             // Next ping sequence number
    std::atomic<uint32_t> mLastPingRttUs{0};       // Last measured RTT in microseconds
    std::mutex mPingRttMutex;
    std::vector<int64_t> mPingRttUs;               // All ping RTT measurements in microseconds
    // Map from seq -> send timestamp for pending pings
    std::mutex mPingPendingMutex;
    std::vector<std::pair<uint32_t, int64_t>> mPingPending;  // (seq, sentTimeNs)

    // Packet tracking
    std::atomic<int> mPacketsReceived{0};
    std::atomic<int> mPacketsLost{0};
    int32_t mLastSeq = -1;                         // Last audio packet sequence number

    // Threading
    std::atomic<bool> mRunning{false};             // Test running flag
    std::thread mNetworkThread;                    // Receives and analyzes server audio
    std::thread mPingThread;                       // Sends ping probes

    // Audio output
    std::shared_ptr<oboe::AudioStream> mStream;   // Oboe output stream for chirp
};

}  // namespace audioengine
