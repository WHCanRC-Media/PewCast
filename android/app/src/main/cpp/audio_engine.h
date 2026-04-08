#pragma once

#include <atomic>
#include <memory>
#include <string>
#include <thread>
#include <vector>
#include <oboe/Oboe.h>

namespace audioengine {

class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    AudioEngine();
    ~AudioEngine();

    /// Start receiving unicast UDP audio from the given server.
    /// serverAddr: IP address of the server (e.g. "192.168.1.100")
    /// listenPort: local port to bind for receiving audio
    bool start(const char* serverAddr, int listenPort);
    void stop();
    void setTargetBufferMs(int ms);

    /// Returns the local UDP port bound for receiving audio.
    int getListenPort() const { return mListenPort; }

    // Stats for UI (thread-safe)
    int getBufferMs() const;
    float getPlaybackRate() const;
    int getLostPackets() const;
    int getUnderruns() const;
    float getPeakLevel() const;

private:
    // Oboe callback
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames) override;

    void networkThread();
    void writeToRing(const int16_t* samples, int count);

    // Constants
    static constexpr int kSampleRate = 48000;
    static constexpr int kChannels = 1;
    static constexpr int kHeaderSize = 8;         // [seq:4][timestamp:4]
    static constexpr int kMaxSamplesPerDatagram = 240;
    static constexpr int kMaxPacketSize = kHeaderSize + kMaxSamplesPerDatagram * 2;

    // Server address (set in start())
    std::string mServerAddr;
    int mListenPort = 0;

    // UDP socket
    int mSocket = -1;

    // Lock-free SPSC ring buffer (i16 throughout)
    std::vector<int16_t> mRingBuffer;
    int mRingSize = 0;
    std::atomic<int> mWritePos{0};
    std::atomic<int> mReadPos{0};

    // Playback state
    std::atomic<float> mPlaybackRate{1.0f};
    float mFractionalPos = 0.0f;
    std::atomic<int> mTargetBufferSamples{0};

    // Packet tracking
    int32_t mLastSeq = -1;

    // Stats (atomic for UI access)
    std::atomic<int> mBufferSamples{0};
    std::atomic<int> mLostPackets{0};
    std::atomic<int> mUnderruns{0};
    std::atomic<float> mPeakLevel{0.0f};

    // Threading
    std::atomic<bool> mRunning{false};
    std::thread mNetworkThread;

    // Oboe stream
    std::shared_ptr<oboe::AudioStream> mStream;
};

}  // namespace audioengine
