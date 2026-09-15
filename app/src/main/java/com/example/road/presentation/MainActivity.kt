package com.example.road.presentation

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.road.data.m.model.Position
import com.example.road.ui.theme.RoadTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val locationPermission = Manifest.permission.ACCESS_FINE_LOCATION
    private val viewModel: MainViewModel by viewModels()
    private val logTag = "MainActivity"

    // Local mirrors of StateFlows
    private val graphReadyLocal = mutableStateOf(false)
    private val routeErrorLocal = mutableStateOf<String?>(null)
    private val startLocal = mutableStateOf<Position?>(null)
    private val destLocal = mutableStateOf<Position?>(null)
    private val routeLocal = mutableStateOf<List<Position>>(emptyList())
    private val instructionLocal = mutableStateOf("Tap map: set START")
    private val currentPosLocal = mutableStateOf<Position?>(null)

    // ── Problem 5/6: hide system bars (status + nav) so map is full bleed ──
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
        // make the window background opaque so the hidden bars don't show
        // through as black if the activity doesn't cover the whole screen
        // (Compose Surface fills MaxSize so this is mostly belt-and-suspenders).
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        // Problem 6: we NEED edge-to-edge for the map to fill the screen,
        // but we immediately hide the bars.  The Compose content uses
        // setWindowInsets to keep Compose-drawn controls (HUD) below the
        // status bar, but the MapView (a native View wrapped in AndroidView)
        // gets the full rect because we set its modifier to fillMaxSize()
        // AND the window has no translucent bars.
        enableEdgeToEdge()
        hideSystemBars()
        super.onCreate(savedInstanceState)

        logTag.d("onCreate — hide system bars, start sensor + graph init")

        setContent {
            RoadTheme {
                // Collect StateFlows into local mutableStateOf mirrors
                LaunchedEffect(Unit) {
                    launch { viewModel.graphReady.collect { graphReadyLocal.value = it } }
                    launch { viewModel.routeError.collect { routeErrorLocal.value = it } }
                    launch { viewModel.startPosition.collect { startLocal.value = it } }
                    launch { viewModel.destPosition.collect { destLocal.value = it } }
                    launch { viewModel.route.collect { routeLocal.value = it } }
                    launch { viewModel.instruction.collect { instructionLocal.value = it } }
                    launch { viewModel.currentPosition.collect { currentPosLocal.value = it } }
                }

                // The map gets the full window; the HUD cards sit on top with a
                // dark translucent backing (problem 7) so text is legible on any
                // map color.
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        MapScreen(
                            modifier = Modifier.fillMaxSize(),
                            onMapTap = { lat, lon -> viewModel.onMapTap(lat, lon) },
                            start = startLocal.value,
                            dest = destLocal.value,
                            route = routeLocal.value,
                            currentPosition = currentPosLocal.value,
                            setMode = startLocal.value == null || destLocal.value == null,
                        )

                        // ── HUD overlay ──
                        // Problem 7: every text chunk renders inside a dark rounded
                        // card so it's readable on any map tile.  Error text gets a
                        // RED backing color; instructional/instruction text gets the
                        // dark semi-transparent #CC000000 (HUD_BACKGROUND).
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 20.dp)
                                .systemBarsPadding()   // keep HUD below status bar
                                .navigationBarsPadding(),  // keep HUD above nav bar
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            // Top card: current position
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp)),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        "Navigation Source: ${currentPosLocal.value?.let { "INS" } ?: "none"}",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    currentPosLocal.value?.let { pos ->
                                        Text(
                                            "Lat: ${"%.6f".format(pos.latitude)}  Lon: ${"%.6f".format(pos.longitude)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        if (pos.accuracy > 0) {
                                            Text(
                                                "Accuracy: ${pos.accuracy.toInt()}m",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (pos.bearing != 0f) {
                                            Text(
                                                "Heading: ${pos.bearing.toInt()}°",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            // Bottom: action buttons + status/instructions
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                // Problem 7: error text → red backing card
                                routeErrorLocal.value?.let { err ->
                                    Card(
                                        modifier = Modifier.padding(bottom = 12.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer
                                        ),
                                    ) {
                                        Text(
                                            err,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(10.dp),
                                        )
                                    }
                                }

                                // Instruction text — dark backing card (problem 7)
                                Card(
                                    modifier = Modifier.padding(bottom = 8.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = ComposeColor(0xCC000000)
                                    ),
                                ) {
                                    Text(
                                        instructionLocal.value,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ComposeColor(0xFFFFFFFF),
                                        modifier = Modifier.padding(10.dp),
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Calculate Route button
                                if (startLocal.value != null && destLocal.value != null &&
                                    routeLocal.value.isEmpty() && graphReadyLocal.value
                                ) {
                                    Button(onClick = { viewModel.calculateRoute() }) {
                                        Text("Calculate Route")
                                    }
                                }

                                // Reset button
                                if (startLocal.value != null || destLocal.value != null || routeLocal.value.isNotEmpty()) {
                                    OutlinedButton(onClick = { viewModel.resetAll() }) {
                                        Text("Reset")
                                    }
                                }

                                // Start Simulation button
                                if (routeLocal.value.isNotEmpty() && graphReadyLocal.value) {
                                    Button(onClick = { viewModel.startSimulation() }) {
                                        Text("Start Simulation")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Permission + sensors
        if (ContextCompat.checkSelfPermission(this, locationPermission) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startSensors()
        } else {
            requestPermissions(arrayOf(locationPermission), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            viewModel.startSensors()
        }
    }
}

private fun android.util.Log.d(tag: String, msg: String) {
    android.util.Log.d(tag, msg)
}
