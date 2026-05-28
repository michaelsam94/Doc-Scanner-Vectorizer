package com.michael.docscannervectorizer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.michael.docscannervectorizer.ui.CameraScreen
import com.michael.docscannervectorizer.ui.HomeScreen
import com.michael.docscannervectorizer.ui.MainViewModel
import com.michael.docscannervectorizer.ui.ReviewScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var launcherOpenCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isLauncherIntent(intent)) {
            launcherOpenCount += 1
        }
        
        // Edge-to-edge support for full screen cameras
        enableEdgeToEdge()

        setContent {
            ScannerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val launchCount = launcherOpenCount

                    LaunchedEffect(launchCount) {
                        if (launchCount > 0) {
                            navController.navigate("home") {
                                popUpTo("home") { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = "home"
                    ) {
                        composable("home") {
                            HomeScreen(
                                viewModel = viewModel,
                                onNavigateToCamera = {
                                    navController.navigate("camera")
                                },
                                onNavigateToReview = {
                                    navController.navigate("review")
                                }
                            )
                        }
                        composable("camera") {
                            CameraScreen(
                                viewModel = viewModel,
                                onNavigateToReview = {
                                    navController.navigate("review")
                                }
                            )
                        }
                        composable("review") {
                            ReviewScreen(
                                viewModel = viewModel,
                                onNavigateBackToCamera = {
                                    navController.popBackStack()
                                },
                                onNavigateToHome = {
                                    navController.navigate("home") {
                                        popUpTo("home") { inclusive = false }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (isLauncherIntent(intent)) {
            launcherOpenCount += 1
        }
    }

    private fun isLauncherIntent(intent: Intent?): Boolean {
        return intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
    }
}

@Composable
fun ScannerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val darkColorPalette = darkColorScheme(
        primary = Color(0xFF10B981), // Emerald/Green accent representing Camera smart overlays
        onPrimary = Color.Black,
        secondary = Color(0xFF14B8A6),
        onSecondary = Color.Black,
        background = Color(0xFF0F172A), // Slate 900
        surface = Color(0xFF1E293B),     // Slate 800
        onBackground = Color(0xFFF8FAFC),
        onSurface = Color(0xFFF8FAFC)
    )

    val lightColorPalette = lightColorScheme(
        primary = Color(0xFF059669),
        onPrimary = Color.White,
        secondary = Color(0xFF0D9488),
        onSecondary = Color.White,
        background = Color(0xFFF8FAFC),
        surface = Color(0xFFFFFFFF),
        onBackground = Color(0xFF0F172A),
        onSurface = Color(0xFF0F172A)
    )

    val colors = if (darkTheme) darkColorPalette else lightColorPalette

    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
