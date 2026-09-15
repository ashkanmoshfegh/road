package com.example.road.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.road.data.m.model.Position
import com.example.road.ui.theme.RoadTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val locationPermission = Manifest.permission.ACCESS_FINE_LOCATION
    private val viewModel: MainViewModel by viewModels()
    private val logTag = "MainActivity"

    private val graphReadyLocal = mutableStateOf(false)
    private val routeErrorLocal = mutableStateOf<String?>(null)
    private val startLocal = mutableStateOf<Position?>(null)
    private val destLocal = mutableStateOf<Position?>(null)
    private val routeLocal = mutableStateOf<List<Position>>(emptyList())
    private val instructionLocal = mutableStateOf("Tap map: set START")
    private val currentPosLocal = mutableStateOf<Position?>(null)
    private val isSimulatingLocal = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // True immersive full-bleed: hide the status/navigation bars
        // entirely instead of padding the UI around them. A swipe from the
        // edge briefly reveals them again if needed.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        Log.d(logTag, "onCreate called — starting sensor + graph initialization")

        // Accelerometer/gyroscope don't require ACCESS_FINE_LOCATION at all
        // — that permission was only ever needed for GPS, which this app no
        // longer uses. Start sensors unconditionally so tracking doesn't
        // silently fail to start if the user denies the (now-unnecessary)
        // location prompt.
        viewModel.startSensors()

        setContent {
            RoadTheme {
                LaunchedEffect(Unit) {
                    launch { viewModel.graphReady.collect { graphReadyLocal.value = it } }
                    launch { viewModel.routeError.collect { routeErrorLocal.value = it } }
                    launch { viewModel.startPosition.collect { startLocal.value = it } }
                    launch { viewModel.destPosition.collect { destLocal.value = it } }
                    launch { viewModel.route.collect { routeLocal.value = it } }
                    launch { viewModel.instruction.collect { instructionLocal.value = it } }
                    launch { viewModel.currentPosition.collect { currentPosLocal.value = it } }
                    launch { viewModel.isSimulating.collect { isSimulatingLocal.value = it } }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        MapScreen(
                            modifier = Modifier.fillMaxSize(),
                            onMapTap = { lat, lon -> viewModel.onMapTap(lat, lon) },
                            start = startLocal.value,
                            dest = destLocal.value,
                            route = routeLocal.value,
                            currentPosition = currentPosLocal.value,
                            followPosition = isSimulatingLocal.value,
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 24.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Top: current position card
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    val sourceLabel = when {
                                        currentPosLocal.value == null -> "none"
                                        isSimulatingLocal.value -> "SIMULATED"
                                        else -> "SENSOR"
                                    }
                                    Text("Navigation Source: $sourceLabel",
                                        style = MaterialTheme.typography.labelMedium)
                                    currentPosLocal.value?.let { pos ->
                                        Text(
                                            "Lat: ${"%.6f".format(pos.latitude)}  Lon: ${"%.6f".format(pos.longitude)}",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        if (pos.bearing != 0f) {
                                            Text(
                                                "Heading: ${pos.bearing.toInt()}°",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            // Bottom: error / instruction cards + action buttons
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                routeErrorLocal.value?.let { err ->
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer
                                        )
                                    ) {
                                        Text(
                                            err,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }

                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                                    )
                                ) {
                                    Text(
                                        instructionLocal.value,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(onClick = { viewModel.beginSetStart() }) {
                                        Text("Set Start")
                                    }
                                    OutlinedButton(onClick = { viewModel.beginSetDest() }) {
                                        Text("Set Destination")
                                    }
                                    if (startLocal.value != null && destLocal.value != null &&
                                        routeLocal.value.isEmpty() && graphReadyLocal.value
                                    ) {
                                        Button(onClick = { viewModel.calculateRoute() }) {
                                            Text("Calculate Route")
                                        }
                                    }
                                    if (routeLocal.value.isNotEmpty()) {
                                        if (isSimulatingLocal.value) {
                                            Button(onClick = { viewModel.stopSimulation() }) {
                                                Text("Stop Simulation")
                                            }
                                        } else {
                                            Button(onClick = { viewModel.startSimulation() }) {
                                                Text("Start Simulation")
                                            }
                                        }
                                    }
                                    if (startLocal.value != null || destLocal.value != null || routeLocal.value.isNotEmpty()) {
                                        OutlinedButton(onClick = { viewModel.resetAll() }) {
                                            Text("Reset")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}