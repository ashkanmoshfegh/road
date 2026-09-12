package com.example.road.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.road.ui.theme.RoadTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val locationPermission = Manifest.permission.ACCESS_FINE_LOCATION
    private val viewModel: MainViewModel by viewModels()
    private val logTag = "MainActivity"

    // StateFlow mirrors — AndroidViewModel creates these, but StateFlow is read-only.
    // We use a local mirror mutableStateOf that we update from the ViewModel's flows.
    private val graphReadyLocal = mutableStateOf(false)
    private val routeErrorLocal = mutableStateOf<String?>(null)
    private val startLocal = mutableStateOf<Position?>(null)
    private val destLocal = mutableStateOf<Position?>(null)
    private val routeLocal = mutableStateOf<List<Position>>(emptyList())
    private val instructionLocal = mutableStateOf("Tap map: set START")
    private val currentPosLocal = mutableStateOf<Position?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        Log.d(logTag, "onCreate called — starting sensor + graph initialization")

        setContent {
            RoadTheme {
                // Collect from ViewModel StateFlows into local state
                LaunchedEffect(Unit) {
                    launch { viewModel.graphReady.collect { graphReadyLocal.value = it } }
                    launch { viewModel.routeError.collect { routeErrorLocal.value = it } }
                    launch { viewModel.startPosition.collect { startLocal.value = it } }
                    launch { viewModel.destPosition.collect { destLocal.value = it } }
                    launch { viewModel.route.collect { routeLocal.value = it } }
                    launch { viewModel.instruction.collect { instructionLocal.value = it } }
                    launch { viewModel.currentPosition.collect { currentPosLocal.value = it } }
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
                        )

                        // HUD overlay
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .safeDrawingPadding()
                                .padding(horizontal = 16.dp, vertical = 32.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Top: current position card
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Navigation Source: ${currentPosLocal.value?.let { "INS" } ?: "none"}",
                                        style = MaterialTheme.typography.labelMedium)
                                    currentPosLocal.value?.let { pos ->
                                        Text(
                                            "Lat: ${"%.6f".format(pos.latitude)}  Lon: ${"%.6f".format(pos.longitude)}",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        if (pos.accuracy > 0) {
                                            Text(
                                                "Accuracy: ${pos.accuracy.toInt()}m",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
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

                            // Bottom: action buttons + instructions
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Error / status message
                                routeErrorLocal.value?.let { err ->
                                    Text(
                                        err,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }

                                // Instruction text
                                Text(
                                    instructionLocal.value,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

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
                                if (routeLocal.value.isNotEmpty() && !graphReadyLocal.value) {
                                    // can't simulate without graph
                                } else if (routeLocal.value.isNotEmpty()) {
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

        // Request location permission and start sensors
        if (ContextCompat.checkSelfPermission(this, locationPermission) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startSensors()
        } else {
            requestPermissions(arrayOf(locationPermission), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            viewModel.startSensors()
        }
    }
}
