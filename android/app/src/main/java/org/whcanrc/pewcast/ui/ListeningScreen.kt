package org.whcanrc.pewcast.ui

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import org.whcanrc.pewcast.audio.AudioEngine
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.roundToInt

private const val TAG = "ListeningScreen"
private const val SERVICE_TYPE = "_pewcast._tcp."

private data class DiscoveredServer(val name: String, val address: String)

// Colors matching the web UI
private val BlueAccent = Color(0xFF4A90D9)
private val GreenLive = Color(0xFF4CAF50)
private val OrangeConnecting = Color(0xFFFF9800)
private val RedError = Color(0xFFF44336)
private val DimText = Color(0xFF888888)
private val DimmerText = Color(0xFF666666)
private val SurfaceDark = Color(0xFF222244)

@Composable
fun ListeningScreen(
    onNavigateToLatencyTest: (serverAddress: String) -> Unit = {}
) {
    val context = LocalContext.current
    val engine = remember { AudioEngine() }
    var isConnected by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var targetBufferMs by remember { mutableFloatStateOf(30f) }
    var serverAddress by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf<String?>(null) }
    var connectionError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Low-latency WiFi lock
    val wifiLock = remember {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "AudioEngine").apply {
            setReferenceCounted(false)
        }
    }

    // mDNS
    var discoveredServers by remember { mutableStateOf<List<DiscoveredServer>>(emptyList()) }
    var showServerDropdown by remember { mutableStateOf(false) }
    val nsdManager = remember { context.getSystemService(Context.NSD_SERVICE) as NsdManager }

    DisposableEffect(Unit) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        val ip = si.host?.hostAddress ?: return
                        val addr = "$ip:${si.port}"
                        val server = DiscoveredServer(name = si.serviceName, address = addr)
                        discoveredServers = (discoveredServers + server).distinctBy { it.address }
                        // Auto-select first discovered server, or update if user hasn't manually edited
                        if (serverAddress.isEmpty() || discoveredServers.size == 1) {
                            serverAddress = addr
                        }
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {}

        onDispose {
            if (isConnected) {
                engine.stop()
                wifiLock.release()
                clientId?.let { cid ->
                    scope.launch(Dispatchers.IO) { httpLeave(serverAddress, cid) }
                }
            }
            try { nsdManager.stopServiceDiscovery(listener) } catch (_: Exception) {}
        }
    }

    // Stats
    var bufferMs by remember { mutableIntStateOf(0) }
    var playbackRate by remember { mutableFloatStateOf(1.0f) }
    var lostPackets by remember { mutableIntStateOf(0) }
    var underruns by remember { mutableIntStateOf(0) }
    var peakLevel by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isConnected) {
        while (isConnected) {
            bufferMs = engine.getBufferMs()
            playbackRate = engine.getPlaybackRate()
            lostPackets = engine.getLostPackets()
            underruns = engine.getUnderruns()
            peakLevel = engine.getPeakLevel()
            delay(100)
        }
    }

    LaunchedEffect(isConnected, clientId) {
        val cid = clientId ?: return@LaunchedEffect
        while (isConnected) {
            delay(5000)
            withContext(Dispatchers.IO) {
                if (!httpHeartbeat(serverAddress, cid)) {
                    Log.w(TAG, "Heartbeat failed")
                }
            }
        }
    }

    // Button color animation
    val buttonColor by animateColorAsState(
        targetValue = when {
            connectionError != null -> RedError
            isConnecting -> OrangeConnecting
            isConnected -> GreenLive
            else -> BlueAccent
        },
        animationSpec = tween(300), label = "buttonColor"
    )

    // Pulse animation for connecting state
    val pulseAlpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ), label = "pulseAlpha"
    )

    val connectDisconnect = {
        if (isConnected) {
            engine.stop()
            wifiLock.release()
            val cid = clientId
            val addr = serverAddress
            clientId = null
            isConnected = false
            isConnecting = false
            connectionError = null
            if (cid != null) {
                scope.launch(Dispatchers.IO) { httpLeave(addr, cid) }
            }
        } else if (!isConnecting) {
            if (serverAddress.isBlank()) {
                connectionError = "No server found — enter address manually"
            } else {
                connectionError = null
                isConnecting = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        engine.setTargetBufferMs(targetBufferMs.roundToInt())
                        val host = serverAddress.substringBefore(":")
                        if (!engine.start(host, 0)) {
                            return@withContext "Failed to start audio engine"
                        }
                        val listenPort = engine.getListenPort()
                        val joinResult = httpJoin(serverAddress, listenPort)
                        if (joinResult == null) {
                            engine.stop()
                            return@withContext "Failed to connect to server"
                        }
                        clientId = joinResult
                        null
                    }
                    isConnecting = false
                    if (result != null) {
                        connectionError = result
                    } else {
                        wifiLock.acquire()
                        isConnected = true
                    }
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 64.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Title
            Text(
                text = "PewCast",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Tap to start listening",
                style = MaterialTheme.typography.bodySmall,
                color = DimText,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            // Big circular button
            Surface(
                modifier = Modifier
                    .size(150.dp)
                    .alpha(if (isConnecting) pulseAlpha else 1f),
                shape = CircleShape,
                color = buttonColor.copy(alpha = 0.15f),
                border = BorderStroke(4.dp, buttonColor),
                onClick = connectDisconnect
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = when {
                            isConnecting -> "..."
                            isConnected -> "Live"
                            else -> "Listen"
                        },
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = buttonColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Status text
            Text(
                text = when {
                    connectionError != null -> connectionError!!
                    isConnecting -> "Connecting..."
                    isConnected -> "Live"
                    else -> "Ready"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = DimText,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Volume meter (shown when connected)
            if (isConnected) {
                val volumePct = peakLevel.coerceIn(0f, 1f)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF333333))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(volumePct)
                                .clip(RoundedCornerShape(4.dp))
                                .background(GreenLive)
                        )
                    }
                    Text(
                        text = "Volume",
                        style = MaterialTheme.typography.labelSmall,
                        color = DimmerText,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Server address — auto-discovered via mDNS, manual entry as fallback
            val selectedServer = discoveredServers.find { it.address == serverAddress }
            if (discoveredServers.isNotEmpty() && !isConnected && !isConnecting) {
                // Show discovered server hostname as tappable text
                Box {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showServerDropdown = true },
                        color = Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedServer?.name ?: serverAddress,
                                style = MaterialTheme.typography.bodySmall,
                                color = DimText
                            )
                            if (discoveredServers.size > 1) {
                                Text(
                                    text = " (${discoveredServers.size} servers)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BlueAccent
                                )
                            }
                        }
                    }
                    DropdownMenu(
                        expanded = showServerDropdown,
                        onDismissRequest = { showServerDropdown = false }
                    ) {
                        discoveredServers.forEach { server ->
                            DropdownMenuItem(
                                text = { Text(server.name) },
                                onClick = {
                                    serverAddress = server.address
                                    showServerDropdown = false
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Enter manually...", color = DimText) },
                            onClick = {
                                showServerDropdown = false
                                serverAddress = ""
                                discoveredServers = emptyList()
                            }
                        )
                    }
                }
            } else if (!isConnected && !isConnecting) {
                // No servers discovered yet — show text field for manual entry
                if (serverAddress.isEmpty() && discoveredServers.isEmpty()) {
                    Text(
                        text = "Searching for server...",
                        style = MaterialTheme.typography.bodySmall,
                        color = DimText,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                OutlinedTextField(
                    value = serverAddress,
                    onValueChange = { serverAddress = it },
                    label = { Text("Server address") },
                    placeholder = { Text("e.g. 192.168.1.100:8080") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Advanced stats (collapsible)
            if (isConnected) {
                var showAdvanced by remember { mutableStateOf(false) }

                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(
                        text = if (showAdvanced) "Hide details" else "Show details",
                        fontSize = 13.sp,
                        color = DimText
                    )
                }

                if (showAdvanced) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Buffer slider
                            Text(
                                text = "Buffer Target: ${targetBufferMs.roundToInt()} ms",
                                fontSize = 12.sp,
                                color = DimmerText
                            )
                            Slider(
                                value = targetBufferMs,
                                onValueChange = {
                                    targetBufferMs = it
                                    engine.setTargetBufferMs(it.roundToInt())
                                },
                                valueRange = 5f..200f,
                                steps = 38,
                                modifier = Modifier.fillMaxWidth(),
                                colors = SliderDefaults.colors(
                                    thumbColor = BlueAccent,
                                    activeTrackColor = BlueAccent
                                )
                            )

                            HorizontalDivider(color = Color(0xFF333333))

                            // Buffer depth bar
                            Text(
                                text = "Buffer: ${bufferMs} ms",
                                fontSize = 12.sp,
                                color = DimmerText
                            )
                            val maxDisplayMs = targetBufferMs * 4
                            val bufferPct = (bufferMs / maxDisplayMs).coerceIn(0f, 1f)
                            val bufferBarColor = when {
                                bufferMs < targetBufferMs * 0.3f -> RedError
                                bufferMs > targetBufferMs * 2f -> OrangeConnecting
                                else -> GreenLive
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF333333))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(bufferPct)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(bufferBarColor)
                                )
                            }

                            HorizontalDivider(color = Color(0xFF333333))
                            StatRow("Rate", String.format("%.4f", playbackRate))
                            StatRow("Lost packets", "$lostPackets",
                                valueColor = if (lostPackets > 0) RedError else DimText)
                            StatRow("Underruns", "$underruns",
                                valueColor = if (underruns > 0) RedError else DimText)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Latency test link
            TextButton(onClick = { onNavigateToLatencyTest(serverAddress) }) {
                Text("Latency Test", color = BlueAccent, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, valueColor: Color = Color(0xFFCCCCCC)) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 12.sp, color = DimText)
        Text(text = value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = valueColor)
    }
}

// --- HTTP helpers ---

// Trust-all SSL context for self-signed LAN server certs
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

private fun httpHeartbeat(serverAddress: String, clientId: String): Boolean {
    return try {
        val url = URL("https://$serverAddress/udp/heartbeat")
        val conn = openConnection(url).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 3000
            readTimeout = 3000
        }
        val body = JSONObject().put("client_id", clientId).toString()
        conn.outputStream.use { it.write(body.toByteArray()) }
        conn.responseCode in 200..299
    } catch (e: Exception) {
        Log.w(TAG, "Heartbeat error: $e")
        false
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
