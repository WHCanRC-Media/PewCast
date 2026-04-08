package org.whcanrc.assistedlistening.audio

class ChirpTester {
    companion object {
        init {
            System.loadLibrary("audioengine")
        }
    }

    fun startTest(listenPort: Int = 0): Boolean = nativeStartTest(listenPort)
    fun stopTest() = nativeStopTest()
    fun shutdown() = nativeShutdown()
    fun getLatencyMs(): Int = nativeGetLatencyMs()
    fun getListenPort(): Int = nativeGetListenPort()
    fun isRunning(): Boolean = nativeIsRunning()

    private external fun nativeStartTest(listenPort: Int): Boolean
    private external fun nativeStopTest()
    private external fun nativeShutdown()
    private external fun nativeGetLatencyMs(): Int
    private external fun nativeGetListenPort(): Int
    private external fun nativeIsRunning(): Boolean
}
