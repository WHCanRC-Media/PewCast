#include "audio_engine.h"

#include <android/log.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <pthread.h>
#include <sched.h>
#include <sys/socket.h>
#include <unistd.h>
#include <algorithm>
#include <cstring>

#define LOG_TAG "AudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace audioengine {

AudioEngine::AudioEngine() {
    // Default 30ms target buffer
    setTargetBufferMs(30);
}

AudioEngine::~AudioEngine() {
    stop();
}

bool AudioEngine::start(const char* serverAddr, int listenPort) {
    if (mRunning.load()) {
        return true;
    }

    mServerAddr = serverAddr;
    mListenPort = listenPort;

    // Allocate ring buffer (4x target for headroom, minimum 200ms)
    int targetSamples = mTargetBufferSamples.load();
    mRingSize = std::max(targetSamples * 4, kSampleRate / 5);  // At least 200ms
    mRingBuffer.resize(mRingSize);
    std::fill(mRingBuffer.begin(), mRingBuffer.end(), static_cast<int16_t>(0));
    mWritePos.store(0);
    mReadPos.store(0);
    mFractionalPos = 0.0f;
    mLastSeq = -1;
    mLostPackets.store(0);
    mUnderruns.store(0);

    // Create UDP socket for unicast receive
    mSocket = socket(AF_INET, SOCK_DGRAM, 0);
    if (mSocket < 0) {
        LOGE("Failed to create socket: %s", strerror(errno));
        return false;
    }

    // Allow address reuse
    int reuse = 1;
    setsockopt(mSocket, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

    // Bind to local port for receiving unicast audio
    sockaddr_in bindAddr{};
    bindAddr.sin_family = AF_INET;
    bindAddr.sin_addr.s_addr = htonl(INADDR_ANY);
    bindAddr.sin_port = htons(listenPort);

    if (bind(mSocket, reinterpret_cast<sockaddr*>(&bindAddr), sizeof(bindAddr)) < 0) {
        LOGE("Failed to bind socket to port %d: %s", listenPort, strerror(errno));
        close(mSocket);
        mSocket = -1;
        return false;
    }

    // If listenPort was 0, retrieve the actual bound port
    if (listenPort == 0) {
        sockaddr_in actualAddr{};
        socklen_t addrLen = sizeof(actualAddr);
        if (getsockname(mSocket, reinterpret_cast<sockaddr*>(&actualAddr), &addrLen) == 0) {
            mListenPort = ntohs(actualAddr.sin_port);
        }
    }

    // Set receive timeout for clean shutdown
    timeval tv{};
    tv.tv_sec = 0;
    tv.tv_usec = 100000;  // 100ms
    setsockopt(mSocket, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    // Create Oboe audio stream with i16 format
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::I16)
           ->setChannelCount(kChannels)
           ->setSampleRate(kSampleRate)
           ->setDataCallback(this);

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open audio stream: %s", oboe::convertToText(result));
        close(mSocket);
        mSocket = -1;
        return false;
    }

    // Pre-fill buffer with silence to reach target
    int prefill = mTargetBufferSamples.load();
    mWritePos.store(prefill);
    mBufferSamples.store(prefill);

    // Start network thread
    mRunning.store(true);
    mNetworkThread = std::thread(&AudioEngine::networkThread, this);

    // Boost network thread priority for timely packet processing
    sched_param param{};
    param.sched_priority = sched_get_priority_max(SCHED_FIFO) - 10;
    if (pthread_setschedparam(mNetworkThread.native_handle(), SCHED_FIFO, &param) != 0) {
        // Fall back to SCHED_RR if FIFO not available
        param.sched_priority = sched_get_priority_max(SCHED_RR) - 5;
        pthread_setschedparam(mNetworkThread.native_handle(), SCHED_RR, &param);
    }

    // Start audio stream
    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start audio stream: %s", oboe::convertToText(result));
        mRunning.store(false);
        mNetworkThread.join();
        mStream->close();
        mStream.reset();
        close(mSocket);
        mSocket = -1;
        return false;
    }

    LOGI("AudioEngine started: server=%s, listenPort=%d, ringSize=%d, target=%d samples",
         mServerAddr.c_str(), mListenPort, mRingSize, targetSamples);
    return true;
}

void AudioEngine::stop() {
    if (!mRunning.load()) {
        return;
    }

    mRunning.store(false);

    // Stop audio stream first
    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }

    // Stop network thread
    if (mNetworkThread.joinable()) {
        mNetworkThread.join();
    }

    // Close socket
    if (mSocket >= 0) {
        close(mSocket);
        mSocket = -1;
    }

    LOGI("AudioEngine stopped");
}

void AudioEngine::setTargetBufferMs(int ms) {
    mTargetBufferSamples.store(kSampleRate * ms / 1000);
}

int AudioEngine::getBufferMs() const {
    return mBufferSamples.load() * 1000 / kSampleRate;
}

float AudioEngine::getPlaybackRate() const {
    return mPlaybackRate.load();
}

int AudioEngine::getLostPackets() const {
    return mLostPackets.load();
}

int AudioEngine::getUnderruns() const {
    return mUnderruns.load();
}

float AudioEngine::getPeakLevel() const {
    return mPeakLevel.load();
}

void AudioEngine::networkThread() {
    // Pre-allocated receive buffer
    uint8_t packet[kMaxPacketSize];

    // Receive jitter instrumentation
    timespec tsNow{};
    int64_t lastRecvNs = 0;
    int64_t recvCount = 0;
    int64_t deltaSumUs = 0;
    double deltaSqSum = 0.0;
    int64_t minDeltaUs = INT64_MAX;
    int64_t maxDeltaUs = 0;

    LOGI("Network thread started (listening on port %d)", mListenPort);

    while (mRunning.load()) {
        sockaddr_in srcAddr{};
        socklen_t srcLen = sizeof(srcAddr);

        ssize_t len = recvfrom(mSocket, packet, sizeof(packet), 0,
                               reinterpret_cast<sockaddr*>(&srcAddr), &srcLen);

        if (len < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                continue;
            }
            LOGE("recvfrom error: %s", strerror(errno));
            continue;
        }

        // Minimum packet size: 8-byte header + at least 1 i16 sample (2 bytes)
        if (len < kHeaderSize + 2) {
            continue;
        }

        // Measure inter-packet arrival time
        clock_gettime(CLOCK_MONOTONIC, &tsNow);
        int64_t nowNs = tsNow.tv_sec * 1000000000LL + tsNow.tv_nsec;
        if (lastRecvNs > 0) {
            int64_t deltaUs = (nowNs - lastRecvNs) / 1000;
            deltaSumUs += deltaUs;
            deltaSqSum += static_cast<double>(deltaUs) * deltaUs;
            if (deltaUs < minDeltaUs) minDeltaUs = deltaUs;
            if (deltaUs > maxDeltaUs) maxDeltaUs = deltaUs;
        }
        lastRecvNs = nowNs;
        recvCount++;

        if (recvCount > 1 && recvCount % 500 == 0) {
            int64_t n = recvCount - 1;
            double meanUs = static_cast<double>(deltaSumUs) / n;
            double variance = (deltaSqSum / n) - (meanUs * meanUs);
            double stddevMs = std::sqrt(std::max(0.0, variance)) / 1000.0;
            double meanMs = meanUs / 1000.0;
            double minMs = static_cast<double>(minDeltaUs) / 1000.0;
            double maxMs = static_cast<double>(maxDeltaUs) / 1000.0;

            int bufSamples = mBufferSamples.load(std::memory_order_relaxed);
            double bufMs = static_cast<double>(bufSamples) * 1000.0 / kSampleRate;

            LOGI("[RX] interval: mean=%.2fms stddev=%.3fms min=%.2fms max=%.2fms buf=%.1fms (n=%lld)",
                 meanMs, stddevMs, minMs, maxMs, bufMs, (long long)n);
        }

        // Parse header (little-endian): [seq:4][timestamp:4]
        uint32_t seq;
        memcpy(&seq, packet, sizeof(seq));

        // Track packet loss
        if (mLastSeq >= 0) {
            int32_t gap = static_cast<int32_t>(seq) - mLastSeq - 1;
            if (gap > 0 && gap < 1000) {
                mLostPackets.fetch_add(gap);
            }
        }
        mLastSeq = static_cast<int32_t>(seq);

        // Payload is raw PCM i16 LE — write directly to ring buffer
        int payloadBytes = static_cast<int>(len) - kHeaderSize;
        int samples = payloadBytes / 2;
        if (samples > kMaxSamplesPerDatagram) {
            samples = kMaxSamplesPerDatagram;
        }

        // Cast payload pointer to i16 (already little-endian on ARM/x86)
        const int16_t* pcm = reinterpret_cast<const int16_t*>(packet + kHeaderSize);
        writeToRing(pcm, samples);
    }

    LOGI("Network thread stopped");
}

void AudioEngine::writeToRing(const int16_t* samples, int count) {
    int writePos = mWritePos.load(std::memory_order_relaxed);
    int readPos = mReadPos.load(std::memory_order_acquire);

    // Calculate available space (leave one sample gap to distinguish full from empty)
    int available = mRingSize - 1 - ((writePos - readPos + mRingSize) % mRingSize);

    if (count > available) {
        // Buffer overflow - drop oldest samples by advancing read position
        int overflow = count - available;
        mReadPos.store((readPos + overflow) % mRingSize, std::memory_order_release);
    }

    // Write samples using memcpy where possible
    int newWritePos = (writePos + count) % mRingSize;
    if (writePos + count <= mRingSize) {
        std::memcpy(&mRingBuffer[writePos], samples, count * sizeof(int16_t));
    } else {
        int firstPart = mRingSize - writePos;
        std::memcpy(&mRingBuffer[writePos], samples, firstPart * sizeof(int16_t));
        std::memcpy(&mRingBuffer[0], samples + firstPart, (count - firstPart) * sizeof(int16_t));
    }

    mWritePos.store(newWritePos, std::memory_order_release);
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames) {

    int16_t* output = static_cast<int16_t*>(audioData);

    int writePos = mWritePos.load(std::memory_order_acquire);
    int readPos = mReadPos.load(std::memory_order_relaxed);

    int available = (writePos - readPos + mRingSize) % mRingSize;

    // Adaptive rate control
    int targetSamples = mTargetBufferSamples.load(std::memory_order_relaxed);
    if (targetSamples > 0) {
        int error = available - targetSamples;
        float correction = static_cast<float>(error) / static_cast<float>(targetSamples) * 0.02f;
        float rate = 1.0f + std::clamp(correction, -0.02f, 0.02f);
        mPlaybackRate.store(rate, std::memory_order_relaxed);
    }

    float playbackRate = mPlaybackRate.load(std::memory_order_relaxed);

    // Read with linear interpolation (interpolate in i16, output i16)
    int16_t peakAbs = 0;
    for (int i = 0; i < numFrames; i++) {
        if (available <= 1) {
            // Underrun - output silence
            output[i] = 0;
            mUnderruns.fetch_add(1, std::memory_order_relaxed);
        } else {
            int idx = readPos % mRingSize;
            int nextIdx = (readPos + 1) % mRingSize;
            float frac = mFractionalPos;

            // Linear interpolation between samples
            int32_t interpolated = static_cast<int32_t>(
                mRingBuffer[idx] * (1.0f - frac) +
                mRingBuffer[nextIdx] * frac);
            // Clamp to i16 range
            if (interpolated > 32767) interpolated = 32767;
            if (interpolated < -32768) interpolated = -32768;
            output[i] = static_cast<int16_t>(interpolated);

            // Advance position by playback rate
            mFractionalPos += playbackRate;
            while (mFractionalPos >= 1.0f) {
                mFractionalPos -= 1.0f;
                readPos++;
                available--;
            }
        }

        // Track peak level for volume meter
        int16_t abs = output[i] < 0 ? -output[i] : output[i];
        if (abs > peakAbs) peakAbs = abs;
    }

    mPeakLevel.store(static_cast<float>(peakAbs) / 32767.0f, std::memory_order_relaxed);
    mReadPos.store(readPos % mRingSize, std::memory_order_release);
    mBufferSamples.store(available, std::memory_order_relaxed);

    return oboe::DataCallbackResult::Continue;
}

}  // namespace audioengine
