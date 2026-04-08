package org.whcanrc.assistedlistening.audio

class AudioEngine {
    companion object {
        init {
            System.loadLibrary("audioengine")
        }
    }

    fun start(serverAddr: String, listenPort: Int): Boolean = nativeStart(serverAddr, listenPort)
    fun stop() = nativeStop()
    fun setTargetBufferMs(ms: Int) = nativeSetTargetBufferMs(ms)
    fun getListenPort(): Int = nativeGetListenPort()
    fun getBufferMs(): Int = nativeGetBufferMs()
    fun getPlaybackRate(): Float = nativeGetPlaybackRate()
    fun getLostPackets(): Int = nativeGetLostPackets()
    fun getUnderruns(): Int = nativeGetUnderruns()
    fun getPeakLevel(): Float = nativeGetPeakLevel()

    private external fun nativeStart(serverAddr: String, listenPort: Int): Boolean
    private external fun nativeStop()
    private external fun nativeSetTargetBufferMs(ms: Int)
    private external fun nativeGetListenPort(): Int
    private external fun nativeGetBufferMs(): Int
    private external fun nativeGetPlaybackRate(): Float
    private external fun nativeGetLostPackets(): Int
    private external fun nativeGetUnderruns(): Int
    private external fun nativeGetPeakLevel(): Float
}
