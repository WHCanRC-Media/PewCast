package org.whcanrc.pewcast

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.whcanrc.pewcast.ui.LatencyTestScreen
import org.whcanrc.pewcast.ui.ListeningScreen
import org.whcanrc.pewcast.ui.theme.PewCastTheme

data class LatencyTestAction(val count: Int, val serverAddress: String?)

class MainActivity : ComponentActivity() {
    var pendingLatencyTest by mutableStateOf<LatencyTestAction?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PewCastTheme {
                AppNavigation(this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val action = intent.getStringExtra("action")
        if (action == "latency_test") {
            pendingLatencyTest = LatencyTestAction(
                count = intent.getIntExtra("count", 10),
                serverAddress = intent.getStringExtra("server_address")
            )
        }
    }
}

@Composable
fun AppNavigation(activity: MainActivity) {
    val navController = rememberNavController()

    // Observe pending latency test action from intent
    val pending = activity.pendingLatencyTest
    LaunchedEffect(pending) {
        if (pending != null) {
            val addr = pending.serverAddress ?: return@LaunchedEffect
            val encoded = java.net.URLEncoder.encode(addr, "UTF-8")
            navController.navigate("latency_test/$encoded?count=${pending.count}") {
                launchSingleTop = true
            }
            activity.pendingLatencyTest = null
        }
    }

    NavHost(navController = navController, startDestination = "listening") {
        composable("listening") {
            ListeningScreen(
                onNavigateToLatencyTest = { serverAddress ->
                    navController.navigate("latency_test/${java.net.URLEncoder.encode(serverAddress, "UTF-8")}")
                }
            )
        }
        composable(
            "latency_test/{serverAddress}?count={count}",
            arguments = listOf(
                navArgument("count") {
                    type = NavType.IntType
                    defaultValue = 0
                }
            )
        ) { backStackEntry ->
            val serverAddress = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("serverAddress") ?: "",
                "UTF-8"
            )
            val autoRunCount = backStackEntry.arguments?.getInt("count") ?: 0
            LatencyTestScreen(
                serverAddress = serverAddress,
                autoRunCount = autoRunCount,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
