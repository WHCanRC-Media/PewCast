/**
 * ChirpTester - Measures round-trip audio latency
 *
 * LATENCY TEST FLOW:
 * ==================
 * 1. Android plays a chirp (frequency sweep) through phone speaker via Oboe
 * 2. Sound travels through air to server's microphone
 * 3. Server captures audio, sends raw PCM i16 via UDP unicast
 * 4. Android receives UDP packets, converts i16 to float, detects chirp
 * 5. Latency = time from chirp playback start to chirp detection
 *
 * PACKET FORMAT (from server):
 * [0-3]   seq:       u32 LE - packet sequence number
 * [4-7]   timestamp: u32 LE - sample offset (RTP-style)
 * [8+]    pcm:       i16 LE - raw PCM samples (up to 240 samples)
 */

#include "chirp_tester.h"

#include <android/log.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#include <cmath>
#include <cstring>

#define LOG_TAG "ChirpTester"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace audioengine {

ChirpTester::ChirpTester() {
    // Pre-generate the chirp waveform (linear frequency sweep from 1kHz to 4kHz)
    mChirpWaveform.resize(kChirpSamples);

    for (int i = 0; i < kChirpSamples; i++) {
        float t = static_cast<float>(i) / kSampleRate;  // Time in seconds
        float progress = static_cast<float>(i) / kChirpSamples;  // 0.0 to 1.0

        // Linear frequency sweep: f(t) = f_start + (f_end - f_start) * progress
        float phase = 2.0f * M_PI * (kChirpStartFreq * t +
                      0.5f * (kChirpEndFreq - kChirpStartFreq) * t * progress);

        // Apply smooth envelope to prevent audible clicks at start/end
        float envelope = 1.0f;
        int rampSamples = kSampleRate / 1000;  // 48 samples = 1ms
        if (i < rampSamples) {
            envelope = static_cast<float>(i) / rampSamples;
        } else if (i > kChirpSamples - rampSamples) {
            envelope = static_cast<float>(kChirpSamples - i) / rampSamples;
        }

        mChirpWaveform[i] = std::sin(phase) * envelope * 1.0f;
    }

    // Precompute chirp template energy for normalized cross-correlation
    mChirpEnergy = 0.0f;
    for (int i = 0; i < kChirpSamples; i++) {
        mChirpEnergy += mChirpWaveform[i] * mChirpWaveform[i];
    }
}

ChirpTester::~ChirpTester() {
    stopTest();
    shutdown();
}

bool ChirpTester::init() {
    if (mInitialized) {
        return true;
    }

    // Create Oboe audio output stream for chirp playback
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::Float)
           ->setChannelCount(kChannels)
           ->setSampleRate(kSampleRate)
           ->setFramesPerDataCallback(480)
           ->setDataCallback(this);

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open audio stream: %s", oboe::convertToText(result));
        return false;
    }

    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start audio stream: %s", oboe::convertToText(result));
        mStream->close();
        mStream.reset();
        return false;
    }

    mInitialized = true;
    LOGI("ChirpTester initialized (stream ready)");
    return true;
}

void ChirpTester::shutdown() {
    if (!mInitialized) {
        return;
    }

    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }

    mInitialized = false;
    LOGI("ChirpTester shutdown");
}

bool ChirpTester::startTest(int listenPort) {
    if (mRunning.load()) {
        return false;
    }
    if (!mInitialized) {
        LOGE("ChirpTester not initialized, call init() first");
        return false;
    }

    // Reset state for new test
    mLatencyMs.store(-1);
    mChirpPlayPos.store(0);
    mPlaying.store(false);

    // Set up UDP unicast socket to receive server audio
    mSocket = socket(AF_INET, SOCK_DGRAM, 0);
    if (mSocket < 0) {
        LOGE("Failed to create socket: %s", strerror(errno));
        return false;
    }

    int reuse = 1;
    setsockopt(mSocket, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

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

    if (listenPort == 0) {
        sockaddr_in actualAddr{};
        socklen_t addrLen = sizeof(actualAddr);
        if (getsockname(mSocket, reinterpret_cast<sockaddr*>(&actualAddr), &addrLen) == 0) {
            mListenPort = ntohs(actualAddr.sin_port);
        }
    } else {
        mListenPort = listenPort;
    }

    // Set 100ms receive timeout
    timeval tv{};
    tv.tv_sec = 0;
    tv.tv_usec = 100000;
    setsockopt(mSocket, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    // Initialize timestamp
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    mChirpStartTimeNs.store(ts.tv_sec * 1000000000LL + ts.tv_nsec);

    // Reset cross-correlation state
    mPrevFrame.clear();
    mPrevFrameTimeNs = 0;

    // Start
    mRunning.store(true);
    mPlaying.store(true);
    mNetworkThread = std::thread(&ChirpTester::networkThread, this);

    LOGI("Chirp test started");
    return true;
}

void ChirpTester::stopTest() {
    if (!mRunning.load()) {
        return;
    }

    mRunning.store(false);
    mPlaying.store(false);

    if (mNetworkThread.joinable()) {
        mNetworkThread.join();
    }

    if (mSocket >= 0) {
        close(mSocket);
        mSocket = -1;
    }
    mListenPort = 0;

    LOGI("Chirp test stopped");
}

int ChirpTester::getLatencyMs() const {
    return mLatencyMs.load();
}

bool ChirpTester::isRunning() const {
    return mRunning.load();
}

oboe::DataCallbackResult ChirpTester::onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames) {

    float* output = static_cast<float*>(audioData);

    if (mPlaying.load()) {
        int playPos = mChirpPlayPos.load();

        // Record the precise timestamp when the FIRST chirp sample is written
        if (playPos == 0) {
            timespec ts;
            clock_gettime(CLOCK_MONOTONIC, &ts);
            mChirpStartTimeNs.store(ts.tv_sec * 1000000000LL + ts.tv_nsec);
        }

        for (int i = 0; i < numFrames; i++) {
            if (playPos < kChirpSamples) {
                output[i] = mChirpWaveform[playPos++];
            } else {
                output[i] = 0.0f;
            }
        }
        mChirpPlayPos.store(playPos);

        // Stop playback after chirp + 100ms of silence
        if (playPos >= kChirpSamples + kSampleRate / 10) {
            mPlaying.store(false);
        }
    } else {
        std::memset(output, 0, numFrames * sizeof(float));
    }

    return oboe::DataCallbackResult::Continue;
}

void ChirpTester::networkThread() {
    uint8_t packet[kMaxPacketSize];
    float pcmBuffer[kMaxSamplesPerDatagram];
    int packetCount = 0;
    float maxCorr = 0.0f;

    LOGI("Detection thread started, startTimeNs=%lld", (long long)mChirpStartTimeNs.load());

    while (mRunning.load()) {
        // Check timeout
        timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        int64_t nowNs = ts.tv_sec * 1000000000LL + ts.tv_nsec;
        int64_t elapsedMs = (nowNs - mChirpStartTimeNs.load()) / 1000000;

        if (elapsedMs > kTimeoutMs && mLatencyMs.load() == -1) {
            mLatencyMs.store(-2);
            LOGI("Chirp detection timed out after %lld ms, received %d packets, maxCorr=%.4f",
                 (long long)elapsedMs, packetCount, maxCorr);
            break;
        }

        // Receive next UDP packet
        sockaddr_in srcAddr{};
        socklen_t srcLen = sizeof(srcAddr);

        ssize_t len = recvfrom(mSocket, packet, sizeof(packet), 0,
                               reinterpret_cast<sockaddr*>(&srcAddr), &srcLen);

        if (len < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                continue;
            }
            continue;
        }

        // Minimum valid packet: 8-byte header + at least 1 i16 sample
        if (len < kHeaderSize + 2) continue;

        // Record receive timestamp
        clock_gettime(CLOCK_MONOTONIC, &ts);
        int64_t frameTimeNs = ts.tv_sec * 1000000000LL + ts.tv_nsec;

        // Convert raw PCM i16 LE payload to float
        int payloadBytes = static_cast<int>(len) - kHeaderSize;
        int samples = payloadBytes / 2;
        if (samples > kMaxSamplesPerDatagram) {
            samples = kMaxSamplesPerDatagram;
        }

        const uint8_t* payload = packet + kHeaderSize;
        for (int i = 0; i < samples; i++) {
            int16_t s;
            memcpy(&s, payload + i * 2, sizeof(s));
            pcmBuffer[i] = static_cast<float>(s) / 32767.0f;
        }

        packetCount++;

        // Build combined buffer: previous frame + current frame
        int prevLen = static_cast<int>(mPrevFrame.size());
        int combinedLen = prevLen + samples;
        std::vector<float> combined(combinedLen);
        if (prevLen > 0) {
            std::memcpy(combined.data(), mPrevFrame.data(), prevLen * sizeof(float));
        }
        std::memcpy(combined.data() + prevLen, pcmBuffer, samples * sizeof(float));

        // Cross-correlate chirp template against combined buffer
        int peakPos = -1;
        float corr = crossCorrelate(combined.data(), combinedLen, peakPos);
        if (corr > maxCorr) maxCorr = corr;

        int64_t frameElapsedMs = (frameTimeNs - mChirpStartTimeNs.load()) / 1000000;
        LOGI("pkt#%d t=%lldms corr=%.4f peakPos=%d", packetCount, (long long)frameElapsedMs, corr, peakPos);

        if (corr >= kCorrelationThreshold && peakPos >= 0) {
            // CHIRP DETECTED! Compute sample-accurate latency.
            int64_t chirpTimeNs;
            if (peakPos >= prevLen) {
                int offsetInFrame = peakPos - prevLen;
                int64_t adjustNs = static_cast<int64_t>(samples - offsetInFrame) * 1000000000LL / kSampleRate;
                chirpTimeNs = frameTimeNs - adjustNs;
            } else {
                int64_t adjustNs = static_cast<int64_t>(prevLen - peakPos) * 1000000000LL / kSampleRate;
                chirpTimeNs = mPrevFrameTimeNs - adjustNs;
            }

            int latency = static_cast<int>((chirpTimeNs - mChirpStartTimeNs.load()) / 1000000);
            mLatencyMs.store(latency);
            LOGI("Chirp detected! Latency: %d ms (corr: %.4f, peakPos: %d, packets: %d)",
                 latency, corr, peakPos, packetCount);
            break;
        }

        // Save current frame as previous
        mPrevFrame.assign(pcmBuffer, pcmBuffer + samples);
        mPrevFrameTimeNs = frameTimeNs;
    }

    LOGI("Detection thread stopped");
}

float ChirpTester::crossCorrelate(const float* buffer, int bufferLen, int& peakPos) {
    float bestCorr = -1.0f;
    peakPos = -1;

    int positions = bufferLen - kChirpSamples + 1;
    if (positions <= 0) return -1.0f;

    for (int offset = 0; offset < positions; offset++) {
        float cross = 0.0f;
        float signalEnergy = 0.0f;

        for (int i = 0; i < kChirpSamples; i++) {
            cross += buffer[offset + i] * mChirpWaveform[i];
            signalEnergy += buffer[offset + i] * buffer[offset + i];
        }

        float denom = std::sqrt(mChirpEnergy * signalEnergy);
        float ncc = (denom > 1e-10f) ? (cross / denom) : 0.0f;

        if (ncc > bestCorr) {
            bestCorr = ncc;
            peakPos = offset;
        }
    }

    return bestCorr;
}

}  // namespace audioengine
