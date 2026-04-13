package org.whcanrc.pewcast.audio

class ChirpTester {
    companion object {
        init {
            System.loadLibrary("audioengine")
        }
    }

    fun startTest(serverAddr: String, serverUdpPort: Int, listenPort: Int = 0): Boolean =
        nativeStartTest(serverAddr, serverUdpPort, listenPort)
    fun playChirp() = nativePlayChirp()
    fun stopTest() = nativeStopTest()
    fun shutdown() = nativeShutdown()
    fun getLatencyMs(): Int = nativeGetLatencyMs()
    fun getListenPort(): Int = nativeGetListenPort()
    fun isRunning(): Boolean = nativeIsRunning()

    // Diagnostics
    fun getPingRttUs(): LongArray = nativeGetPingRttUs()
    fun getPacketsReceived(): Int = nativeGetPacketsReceived()
    fun getPacketsLost(): Int = nativeGetPacketsLost()
    fun getOutputLatencyMs(): Double = nativeGetOutputLatencyMs()

    private external fun nativeStartTest(serverAddr: String, serverUdpPort: Int, listenPort: Int): Boolean
    private external fun nativePlayChirp()
    private external fun nativeStopTest()
    private external fun nativeShutdown()
    private external fun nativeGetLatencyMs(): Int
    private external fun nativeGetListenPort(): Int
    private external fun nativeIsRunning(): Boolean
    private external fun nativeGetPingRttUs(): LongArray
    private external fun nativeGetPacketsReceived(): Int
    private external fun nativeGetPacketsLost(): Int
    private external fun nativeGetOutputLatencyMs(): Double
}
