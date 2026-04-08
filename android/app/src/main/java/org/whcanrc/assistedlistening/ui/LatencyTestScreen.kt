package org.whcanrc.assistedlistening.ui

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
fun LatencyTestScreen(
    serverAddress: String,
    onNavigateBack: () -> Unit
) {
    val tester = remember { ChirpTester() }
    var isRunning by remember { mutableStateOf(false) }
    var latencyMs by remember { mutableIntStateOf(-1) }
    var results by remember { mutableStateOf(listOf<Int>()) }
    var chirpClientId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Poll for results
    LaunchedEffect(isRunning) {
        while (isRunning) {
            val result = tester.getLatencyMs()
            if (result != -1) {
                latencyMs = result
                if (result > 0) results = results + result
                isRunning = false
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
                .padding(top = 48.dp, bottom = 24.dp),
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
                        scope.launch {
                            val started = tester.startTest(0)
                            if (!started) return@launch
                            val listenPort = tester.getListenPort()
                            val cid = withContext(Dispatchers.IO) {
                                httpJoin(serverAddress, listenPort)
                            }
                            if (cid == null) {
                                Log.e(TAG, "Failed to join server for chirp test")
                                tester.stopTest()
                                latencyMs = -2
                                return@launch
                            }
                            chirpClientId = cid
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

            Spacer(modifier = Modifier.height(32.dp))

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
                        val avg = results.average().toInt()
                        val min = results.minOrNull() ?: 0
                        val max = results.maxOrNull() ?: 0

                        StatRow("Average", "$avg ms")
                        StatRow("Min", "$min ms")
                        StatRow("Max", "$max ms")
                        StatRow("Tests", "${results.size}")

                        Spacer(modifier = Modifier.height(4.dp))

                        TextButton(
                            onClick = { results = emptyList() },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Clear", color = DimText, fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
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

private fun httpJoin(serverAddress: String, listenPort: Int): String? {
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
            JSONObject(resp).getString("client_id")
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
