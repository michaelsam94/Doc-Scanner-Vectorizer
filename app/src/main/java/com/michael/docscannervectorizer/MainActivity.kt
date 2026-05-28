package com.michael.docscannervectorizer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.michael.docscannervectorizer.di.ViewModelFactory
import com.michael.docscannervectorizer.feature.adjust.AdjustScreen
import com.michael.docscannervectorizer.feature.adjust.AdjustViewModel
import com.michael.docscannervectorizer.feature.gallery.GalleryScreen
import com.michael.docscannervectorizer.feature.gallery.GalleryViewModel
import com.michael.docscannervectorizer.feature.scan.ScanScreen
import com.michael.docscannervectorizer.feature.scan.ScanViewModel
import com.michael.docscannervectorizer.feature.vectorize.VectorizeScreen
import com.michael.docscannervectorizer.feature.vectorize.VectorizeViewModel
import com.michael.docscannervectorizer.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Get safe access to manual di container
        val appContainer = (application as ScannerApplication).container
        val factory = ViewModelFactory(appContainer)

        setContent {
            MyApplicationTheme {
                val navController = rememberNavController()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "gallery",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        // Document list/summary screen
                        composable("gallery") {
                            val galleryViewModel: GalleryViewModel = viewModel(factory = factory)
                            GalleryScreen(
                                viewModel = galleryViewModel,
                                onNavigateToScan = { navController.navigate("scan") },
                                onNavigateToAdjust = { docId -> navController.navigate("adjust/$docId") }
                            )
                        }

                        // Boundary crop & capture screen
                        composable("scan") {
                            val scanViewModel: ScanViewModel = viewModel(factory = factory)
                            ScanScreen(
                                viewModel = scanViewModel,
                                onNavigateToAdjust = { docId -> 
                                    // Pop back to gallery or clear scan so back stack is pristine
                                    navController.navigate("adjust/$docId") {
                                        popUpTo("scan") { inclusive = true }
                                    }
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // Rotate, enhance & filter screen
                        composable(
                            route = "adjust/{documentId}",
                            arguments = listOf(navArgument("documentId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val documentId = backStackEntry.arguments?.getString("documentId") ?: ""
                            val adjustViewModel: AdjustViewModel = viewModel(factory = factory)
                            AdjustScreen(
                                documentId = documentId,
                                viewModel = adjustViewModel,
                                onNavigateToVectorize = { docId -> navController.navigate("vectorize/$docId") },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // SVG and transparent PNG exporter
                        composable(
                            route = "vectorize/{documentId}",
                            arguments = listOf(navArgument("documentId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val documentId = backStackEntry.arguments?.getString("documentId") ?: ""
                            val vectorizeViewModel: VectorizeViewModel = viewModel(factory = factory)
                            VectorizeScreen(
                                documentId = documentId,
                                viewModel = vectorizeViewModel,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
