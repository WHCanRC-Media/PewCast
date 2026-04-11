package org.whcanrc.assistedlistening.ui

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.whcanrc.assistedlistening.audio.ChirpTester
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val TAG = "LatencyTestScreen"

private val BlueAccent = Color(0xFF4A90D9)
private val GreenLive = Color(0xFF4CAF50)
private val OrangeConnecting = Color(0xFFFF9800)
private val RedError = Color(0xFFF44336)
private val DimText = Color(0xFF888888)
private val SurfaceDark = Color(0xFF222244)

/** Result from /udp/join including the server's UDP port. */
private data class JoinResult(val clientId: String, val udpPort: Int)

@Composable
fun LatencyTestScreen(
    serverAddress: String,
    autoRunCount: Int = 0,
    onNavigateBack: () -> Unit
) {
    val tester = remember { ChirpTester() }
    var isRunning by remember { mutableStateOf(false) }
    var latencyMs by remember { mutableIntStateOf(-1) }
    var results by remember { mutableStateOf(listOf<Int>()) }
    var chirpClientId by remember { mutableStateOf<String?>(null) }
    var serverUdpPort by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // Diagnostics state
    var pingRtts by remember { mutableStateOf(longArrayOf()) }
    var packetsReceived by remember { mutableIntStateOf(0) }
    var packetsLost by remember { mutableIntStateOf(0) }
    var outputLatencyMs by remember { mutableStateOf(-1.0) }
    var reportStatus by remember { mutableStateOf("") }

    // Extract server IP (strip port) for native UDP
    val serverIp = remember(serverAddress) {
        serverAddress.substringBefore(":")
    }

    // Poll for results
    LaunchedEffect(isRunning) {
        while (isRunning) {
            val result = tester.getLatencyMs()
            // Update live diagnostics while running
            pingRtts = tester.getPingRttUs()
            packetsReceived = tester.getPacketsReceived()
            packetsLost = tester.getPacketsLost()
            outputLatencyMs = tester.getOutputLatencyMs()

            if (result != -1) {
                latencyMs = result
                if (result > 0) results = results + result
                isRunning = false
                // Grab final diagnostics
                pingRtts = tester.getPingRttUs()
                packetsReceived = tester.getPacketsReceived()
                packetsLost = tester.getPacketsLost()
                outputLatencyMs = tester.getOutputLatencyMs()
                tester.stopTest()
                val cid = chirpClientId
                if (cid != null) {
                    withContext(Dispatchers.IO) { httpLeave(serverAddress, cid) }
                    chirpClientId = null
                }
            }
            delay(50)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            tester.stopTest()
            tester.shutdown()
            chirpClientId?.let { cid ->
                scope.launch(Dispatchers.IO) { httpLeave(serverAddress, cid) }
            }
        }
    }

    // Auto-run mode: run N chirps automatically when launched from automation intent
    LaunchedEffect(autoRunCount) {
        if (autoRunCount <= 0) return@LaunchedEffect

        Log.i(TAG, "LATENCY_AUTORUN: starting $autoRunCount tests against $serverAddress")
        delay(500) // let UI render

        // Discover the server's UDP port once upfront, reuse for all chirps
        val discoverResult = withContext(Dispatchers.IO) { httpJoin(serverAddress, 0) }
        if (discoverResult == null) {
            Log.e(TAG, "LATENCY_SUMMARY: 0 ok, $autoRunCount timeout (failed to discover UDP port)")
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) { httpLeave(serverAddress, discoverResult.clientId) }
        val cachedUdpPort = discoverResult.udpPort

        var timeouts = 0
        for (i in 1..autoRunCount) {
            val result = runOneChirp(tester, serverAddress, serverIp, cachedUdpPort)
            if (result > 0) {
                results = results + result
                Log.i(TAG, "LATENCY_RESULT: ${result}ms")
            } else {
                timeouts++
                Log.i(TAG, "LATENCY_RESULT: timeout")
            }
            // Grab diagnostics after each chirp
            pingRtts = tester.getPingRttUs()
            packetsReceived = tester.getPacketsReceived()
            packetsLost = tester.getPacketsLost()
            outputLatencyMs = tester.getOutputLatencyMs()
            delay(1000) // pause between chirps
        }

        // Log summary
        if (results.isNotEmpty()) {
            val sorted = results.sorted()
            val avg = results.average().toInt()
            val min = sorted.first()
            val max = sorted.last()
            val p50 = sorted[sorted.size / 2]
            Log.i(TAG, "LATENCY_SUMMARY: ${results.size} ok, $timeouts timeout, avg=${avg}ms, min=${min}ms, p50=${p50}ms, max=${max}ms")
        } else {
            Log.i(TAG, "LATENCY_SUMMARY: 0 ok, $timeouts timeout")
        }

        // Auto-send report
        withContext(Dispatchers.IO) {
            sendReport(
                serverAddress = serverAddress,
                chirpRtts = results.map { it.toDouble() },
                pingRttsUs = pingRtts.toList(),
                packetsReceived = packetsReceived,
                packetsLost = packetsLost,
                outputLatencyMs = outputLatencyMs
            )
        }
        Log.i(TAG, "LATENCY_DONE")
    }

    // Result display color
    val resultColor by animateColorAsState(
        targetValue = when {
            isRunning -> OrangeConnecting
            latencyMs == -2 -> RedError
            latencyMs > 0 -> GreenLive
            else -> DimText
        },
        animationSpec = tween(300), label = "resultColor"
    )

    // Pulse when running
    val pulseAlpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ), label = "pulseAlpha"
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 48.dp, bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Back
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                TextButton(onClick = onNavigateBack) {
                    Text("< Back", color = BlueAccent, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Latency Test",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Plays a chirp through the speaker and measures round-trip time.",
                style = MaterialTheme.typography.bodySmall,
                color = DimText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, start = 16.dp, end = 16.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Big result display
            Surface(
                modifier = Modifier
                    .size(160.dp)
                    .alpha(if (isRunning) pulseAlpha else 1f),
                shape = CircleShape,
                color = resultColor.copy(alpha = 0.12f),
                border = BorderStroke(3.dp, resultColor.copy(alpha = 0.4f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = when {
                            isRunning -> "..."
                            latencyMs == -2 -> "Timeout"
                            latencyMs > 0 -> "$latencyMs"
                            else -> "---"
                        },
                        fontSize = if (latencyMs == -2 || isRunning) 20.sp else 36.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = resultColor
                    )
                    if (latencyMs > 0 && !isRunning) {
                        Text(
                            text = "ms",
                            fontSize = 13.sp,
                            color = DimText,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 28.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Run button
            Button(
                onClick = {
                    if (!isRunning) {
                        latencyMs = -1
                        pingRtts = longArrayOf()
                        packetsReceived = 0
                        packetsLost = 0
                        outputLatencyMs = -1.0
                        scope.launch {
                            // First join to discover the server's UDP port
                            val discoverResult = withContext(Dispatchers.IO) {
                                httpJoin(serverAddress, 0)
                            }
                            if (discoverResult == null) {
                                Log.e(TAG, "Failed to join server for chirp test")
                                latencyMs = -2
                                return@launch
                            }
                            serverUdpPort = discoverResult.udpPort
                            // Leave immediately — we just needed the port
                            withContext(Dispatchers.IO) {
                                httpLeave(serverAddress, discoverResult.clientId)
                            }

                            // Start test (binds socket, starts ping probes, but no chirp yet)
                            val started = tester.startTest(serverIp, discoverResult.udpPort, 0)
                            if (!started) {
                                latencyMs = -2
                                return@launch
                            }

                            // Join with our actual listen port so server sends audio to us
                            val listenPort = tester.getListenPort()
                            val joinResult = withContext(Dispatchers.IO) {
                                httpJoin(serverAddress, listenPort)
                            }
                            if (joinResult == null) {
                                Log.e(TAG, "Failed to join with listen port")
                                tester.stopTest()
                                latencyMs = -2
                                return@launch
                            }
                            chirpClientId = joinResult.clientId

                            // Small delay to let audio packets start flowing
                            delay(100)

                            // Now trigger chirp playback
                            tester.playChirp()
                            isRunning = true
                        }
                    }
                },
                enabled = !isRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BlueAccent,
                    disabledContainerColor = BlueAccent.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(48.dp)
            ) {
                Text(
                    if (isRunning) "Testing..." else "Run Test",
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Ping RTT display
            if (pingRtts.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Network Ping", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = BlueAccent)

                        val rttsMs = pingRtts.map { it / 1000.0 }
                        val sorted = rttsMs.sorted()
                        val min = sorted.first()
                        val max = sorted.last()
                        val avg = rttsMs.average()
                        val p50 = sorted[sorted.size / 2]

                        StatRow("p50", "%.1f ms".format(p50))
                        StatRow("avg", "%.1f ms".format(avg))
                        StatRow("min / max", "%.1f / %.1f ms".format(min, max))
                        StatRow("probes", "${pingRtts.size}")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // Packet stats & output latency
            if (packetsReceived > 0 || outputLatencyMs >= 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Transport Stats", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = BlueAccent)

                        if (packetsReceived > 0) {
                            StatRow("Packets received", "$packetsReceived")
                            StatRow("Packets lost", "$packetsLost")
                            if (packetsReceived + packetsLost > 0) {
                                val lossRate = packetsLost.toDouble() / (packetsReceived + packetsLost) * 100.0
                                StatRow("Loss rate", "%.1f%%".format(lossRate))
                            }
                        }
                        if (outputLatencyMs >= 0) {
                            StatRow("Oboe output latency", "%.1f ms".format(outputLatencyMs))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // Results history
            if (results.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Chirp RTT", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = BlueAccent)

                        val avg = results.average().toInt()
                        val min = results.minOrNull() ?: 0
                        val max = results.maxOrNull() ?: 0

                        StatRow("Average", "$avg ms")
                        StatRow("Min", "$min ms")
                        StatRow("Max", "$max ms")
                        StatRow("Tests", "${results.size}")

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(
                                onClick = { results = emptyList() },
                            ) {
                                Text("Clear", color = DimText, fontSize = 12.sp)
                            }
                            // Send Report button
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        reportStatus = "Sending..."
                                        val success = withContext(Dispatchers.IO) {
                                            sendReport(
                                                serverAddress = serverAddress,
                                                chirpRtts = results.map { it.toDouble() },
                                                pingRttsUs = pingRtts.toList(),
                                                packetsReceived = packetsReceived,
                                                packetsLost = packetsLost,
                                                outputLatencyMs = outputLatencyMs
                                            )
                                        }
                                        reportStatus = if (success) "Sent" else "Failed"
                                    }
                                },
                            ) {
                                Text(
                                    if (reportStatus.isEmpty()) "Send Report" else reportStatus,
                                    color = BlueAccent,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 12.sp, color = DimText)
        Text(text = value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFCCCCCC))
    }
}

// --- Chirp automation helper ---

/** Run a single chirp test and return latency in ms, or -2 on timeout/error. */
private suspend fun runOneChirp(
    tester: ChirpTester,
    serverAddress: String,
    serverIp: String,
    cachedUdpPort: Int = 0,
): Int {
    val udpPort = if (cachedUdpPort > 0) {
        cachedUdpPort
    } else {
        // Discover UDP port if not cached
        val discoverResult = withContext(Dispatchers.IO) { httpJoin(serverAddress, 0) }
            ?: return -2
        withContext(Dispatchers.IO) { httpLeave(serverAddress, discoverResult.clientId) }
        discoverResult.udpPort
    }

    // Start test (binds socket, starts ping probes)
    if (!tester.startTest(serverIp, udpPort, 0)) return -2

    // Join with actual listen port so server sends audio to us
    val listenPort = tester.getListenPort()
    val joinResult = withContext(Dispatchers.IO) { httpJoin(serverAddress, listenPort) }
    if (joinResult == null) {
        tester.stopTest()
        return -2
    }

    // Wait for audio packets to start flowing, then play chirp
    delay(200)
    tester.playChirp()

    // Poll for result (timeout after 5s)
    val deadline = System.currentTimeMillis() + 5000
    var result = -1
    while (System.currentTimeMillis() < deadline) {
        result = tester.getLatencyMs()
        if (result != -1) break
        delay(50)
    }

    tester.stopTest()
    withContext(Dispatchers.IO) { httpLeave(serverAddress, joinResult.clientId) }

    return if (result == -1) -2 else result
}

// --- HTTP helpers ---

private val trustAllSslContext: SSLContext by lazy {
    val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })
    SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
}

private fun openConnection(url: URL): HttpURLConnection {
    val conn = url.openConnection() as HttpURLConnection
    if (conn is HttpsURLConnection) {
        conn.sslSocketFactory = trustAllSslContext.socketFactory
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
    }
    return conn
}

private fun httpJoin(serverAddress: String, listenPort: Int): JoinResult? {
    return try {
        val url = URL("https://$serverAddress/udp/join")
        val conn = openConnection(url).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 5000
            readTimeout = 5000
        }
        val body = JSONObject().put("port", listenPort).toString()
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode == 200) {
            val resp = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(resp)
            JoinResult(
                clientId = json.getString("client_id"),
                udpPort = json.getInt("udp_port")
            )
        } else {
            Log.e(TAG, "Join failed: HTTP ${conn.responseCode}")
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "Join error: $e")
        null
    }
}

private fun httpLeave(serverAddress: String, clientId: String) {
    try {
        val url = URL("https://$serverAddress/udp/leave")
        val conn = openConnection(url).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 3000
            readTimeout = 3000
        }
        val body = JSONObject().put("client_id", clientId).toString()
        conn.outputStream.use { it.write(body.toByteArray()) }
        conn.responseCode
    } catch (e: Exception) {
        Log.w(TAG, "Leave error: $e")
    }
}

private fun sendReport(
    serverAddress: String,
    chirpRtts: List<Double>,
    pingRttsUs: List<Long>,
    packetsReceived: Int,
    packetsLost: Int,
    outputLatencyMs: Double,
): Boolean {
    return try {
        val report = JSONObject().apply {
            put("transport", "udp")
            put("user_agent", "Android/${android.os.Build.MODEL}")
            if (outputLatencyMs >= 0) put("output_latency_ms", outputLatencyMs)
            if (chirpRtts.isNotEmpty()) put("chirp_rtt", JSONArray(chirpRtts))
            if (pingRttsUs.isNotEmpty()) {
                val pingMs = pingRttsUs.map { it / 1000.0 }
                put("audio_rtt", JSONArray(pingMs))
            }
            put("packets_received", packetsReceived.toLong())
            put("packets_lost", packetsLost.toLong())
        }

        val url = URL("https://$serverAddress/latency_test/report")
        val conn = openConnection(url).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 5000
            readTimeout = 5000
        }
        conn.outputStream.use { it.write(report.toString().toByteArray()) }
        val ok = conn.responseCode in 200..299
        if (!ok) {
            Log.e(TAG, "Report failed: HTTP ${conn.responseCode}")
        }
        ok
    } catch (e: Exception) {
        Log.e(TAG, "Report error: $e")
        false
    }
}
