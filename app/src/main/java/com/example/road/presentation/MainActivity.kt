package com.example.road.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val locationPermission = Manifest.permission.ACCESS_FINE_LOCATION
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            RoadTheme {
                val start       by viewModel.start.collectAsState()
                val dest        by viewModel.dest.collectAsState()
                val route       by viewModel.routePoints.collectAsState()
                val currentPos  by viewModel.currentPosition.collectAsState()
                val source      by viewModel.currentSource.collectAsState()
                val isMoving    by viewModel.isMoving.collectAsState()

                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        MapScreen(
                            modifier = Modifier.fillMaxSize(),
                            onMapTap = { lat, lon -> viewModel.onMapTap(lat, lon) },
                            start = start,
                            dest = dest,
                            route = route,
                            currentPosition = currentPos,
                        )

                        // HUD overlay — source + position + accuracy
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .safeDrawingPadding()
                                .padding(horizontal = 16.dp, vertical = 80.dp),
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
                                    Text("Navigation Source: $source",
                                        style = MaterialTheme.typography.labelMedium)
                                    currentPos?.let { pos ->
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

                            // Bottom: action buttons
                            if (dest != null && route.isNotEmpty() && !isMoving) {
                                Button(
                                    onClick = { viewModel.startSimulation() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Start Simulation")
                                }
                                TextButton(
                                    onClick = { viewModel.stopSensors() },
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                ) {
                                    Text("Stop Sensors")
                                }
                            } else {
                                Text(
                                    if (start == null) "Tap map: set START (green)"
                                    else if (dest == null) "Tap map: set DESTINATION (red)"
                                    else "Route shown. Tap to reset.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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
