package org.whcanrc.assistedlistening

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.whcanrc.assistedlistening.ui.LatencyTestScreen
import org.whcanrc.assistedlistening.ui.ListeningScreen
import org.whcanrc.assistedlistening.ui.theme.AssistedListeningTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AssistedListeningTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "listening") {
        composable("listening") {
            ListeningScreen(
                onNavigateToLatencyTest = { serverAddress ->
                    navController.navigate("latency_test/${java.net.URLEncoder.encode(serverAddress, "UTF-8")}")
                }
            )
        }
        composable("latency_test/{serverAddress}") { backStackEntry ->
            val serverAddress = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("serverAddress") ?: "",
                "UTF-8"
            )
            LatencyTestScreen(
                serverAddress = serverAddress,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
