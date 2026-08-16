#include <jni.h>
#include "audio_engine.h"
#include "chirp_tester.h"

static audioengine::AudioEngine* gEngine = nullptr;
static audioengine::ChirpTester* gChirpTester = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_whcanrc_pewcast_audio_AudioEngine_nativeStart(JNIEnv* env, jobject thiz,
        jstring serverAddr, jint listenPort) {
    if (gEngine) {
        gEngine->stop();
        delete gEngine;
    }
    gEngine = new audioengine::AudioEngine();
    const char* addr = env->GetStringUTFChars(serverAddr, nullptr);
    bool ok = gEngine->start(addr, listenPort);
    env->ReleaseStringUTFChars(serverAddr, addr);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_whcanrc_pewcast_audio_AudioEngine_nativeGetListenPort(JNIEnv* env, jobject thiz) {
    return gEngine ? gEngine->getListenPort() : 0;
}

JNIEXPORT jstring JNICALL
Java_org_whcanrc_pewcast_audio_AudioEngine_nativeGetLastError(JNIEnv* env, jobject thiz) {
    return env->NewStringUTF(gEngine ? gEngine->getLastError().c_str() : "");
}

JNIEXPORT void JNICALL
Java_org_whcanrc_pewcast_audio_AudioEngine_nativeStop(JNIEnv* env, jobject thiz) {
    if (gEngine) {
        gEngine->stop();
        delete gEngine;
        gEngine = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_org_whcanrc_pewcast_audio_AudioEngine_nativeSetTargetBufferMs(JNIEnv* env, jobject thiz, jint ms) {
    if (gEngine) {
        gEngine->setTargetBufferMs(ms);
    }
}

JNIEXPORT jint JNICALL
Java_org_whcanrc_pewcast_audio_AudioEngine_nativeGetBufferMs(JNIEnv* env, jobject thiz) {
    return gEngine ? gEngine->getBufferMs() : 0;
}

JNIEXPORT jfloat JNICALL
Java_org_whcanrc_pewcast_audio_AudioEngine_nativeGetPlaybackRate(JNIEnv* env, jobject thiz) {
    return gEngine ? gEngine->getPlaybackRate() : 1.0f;
}

JNIEXPORT jint JNICALL
Java_org_whcanrc_pewcast_audio_AudioEngine_nativeGetLostPackets(JNIEnv* env, jobject thiz) {
    return gEngine ? gEngine->getLostPackets() : 0;
}

JNIEXPORT jint JNICALL
Java_org_whcanrc_pewcast_audio_AudioEngine_nativeGetUnderruns(JNIEnv* env, jobject thiz) {
    return gEngine ? gEngine->getUnderruns() : 0;
}

JNIEXPORT jfloat JNICALL
Java_org_whcanrc_pewcast_audio_AudioEngine_nativeGetPeakLevel(JNIEnv* env, jobject thiz) {
    return gEngine ? gEngine->getPeakLevel() : 0.0f;
}

// ChirpTester JNI bindings

JNIEXPORT jboolean JNICALL
Java_org_whcanrc_pewcast_audio_ChirpTester_nativeStartTest(JNIEnv* env, jobject thiz,
        jstring serverAddr, jint serverUdpPort, jint listenPort) {
    if (!gChirpTester) {
        gChirpTester = new audioengine::ChirpTester();
        if (!gChirpTester->init()) {
            delete gChirpTester;
            gChirpTester = nullptr;
            return JNI_FALSE;
        }
    }
    gChirpTester->stopTest();  // Stop any previous test
    const char* addr = env->GetStringUTFChars(serverAddr, nullptr);
    bool ok = gChirpTester->startTest(addr, serverUdpPort, listenPort);
    env->ReleaseStringUTFChars(serverAddr, addr);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_whcanrc_pewcast_audio_ChirpTester_nativePlayChirp(JNIEnv* env, jobject thiz) {
    if (gChirpTester) {
        gChirpTester->playChirp();
    }
}

JNIEXPORT jint JNICALL
Java_org_whcanrc_pewcast_audio_ChirpTester_nativeGetListenPort(JNIEnv* env, jobject thiz) {
    return gChirpTester ? gChirpTester->getListenPort() : 0;
}

JNIEXPORT void JNICALL
Java_org_whcanrc_pewcast_audio_ChirpTester_nativeStopTest(JNIEnv* env, jobject thiz) {
    if (gChirpTester) {
        gChirpTester->stopTest();
    }
}

JNIEXPORT void JNICALL
Java_org_whcanrc_pewcast_audio_ChirpTester_nativeShutdown(JNIEnv* env, jobject thiz) {
    if (gChirpTester) {
        gChirpTester->stopTest();
        gChirpTester->shutdown();
        delete gChirpTester;
        gChirpTester = nullptr;
    }
}

JNIEXPORT jint JNICALL
Java_org_whcanrc_pewcast_audio_ChirpTester_nativeGetLatencyMs(JNIEnv* env, jobject thiz) {
    return gChirpTester ? gChirpTester->getLatencyMs() : -2;
}

JNIEXPORT jboolean JNICALL
Java_org_whcanrc_pewcast_audio_ChirpTester_nativeIsRunning(JNIEnv* env, jobject thiz) {
    return (gChirpTester && gChirpTester->isRunning()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL
Java_org_whcanrc_pewcast_audio_ChirpTester_nativeGetPingRttUs(JNIEnv* env, jobject thiz) {
    if (!gChirpTester) {
        return env->NewLongArray(0);
    }
    auto rtts = gChirpTester->getPingRttUs();
    jlongArray result = env->NewLongArray(static_cast<jsize>(rtts.size()));
    if (!rtts.empty()) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(rtts.size()),
                                reinterpret_cast<const jlong*>(rtts.data()));
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_org_whcanrc_pewcast_audio_ChirpTester_nativeGetPacketsReceived(JNIEnv* env, jobject thiz) {
    return gChirpTester ? gChirpTester->getPacketsReceived() : 0;
}

JNIEXPORT jint JNICALL
Java_org_whcanrc_pewcast_audio_ChirpTester_nativeGetPacketsLost(JNIEnv* env, jobject thiz) {
    return gChirpTester ? gChirpTester->getPacketsLost() : 0;
}

JNIEXPORT jdouble JNICALL
Java_org_whcanrc_pewcast_audio_ChirpTester_nativeGetOutputLatencyMs(JNIEnv* env, jobject thiz) {
    return gChirpTester ? gChirpTester->getOutputLatencyMs() : -1.0;
}

}  // extern "C"
