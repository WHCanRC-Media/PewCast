#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <oboe/Oboe.h>

namespace audioengine {

class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
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

    /// Why the last start() failed, or why a running stream was torn down.
    /// Empty if nothing has gone wrong. Safe to call from any thread.
    std::string getLastError() const;

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

    // Oboe error callback — fires on a mid-session disconnect (e.g. USB DAC
    // unplugged). Triggers a clean teardown instead of going silent.
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

    // Open the output stream with the given sharing mode, retrying a few times
    // to ride out the USB HAL's asynchronous endpoint release on reconnect.
    oboe::Result openOutputStream(oboe::SharingMode sharingMode);

    void networkThread();
    void writeToRing(const int16_t* samples, int count);
    void setLastError(const std::string& reason);

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

    // Last failure reason, surfaced to the UI so a user can report something
    // more useful than "it didn't work".
    mutable std::mutex mLastErrorMutex;
    std::string mLastError;
};

}  // namespace audioengine
