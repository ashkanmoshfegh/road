package com.example.road.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
        super.onCreate(savedInstanceState)

        setContent {
            RoadTheme {
                val source by viewModel.currentSource.collectAsState()
                val dest by viewModel.dest.collectAsState()
                val isMoving by viewModel.isMoving.collectAsState()

                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                    ) {
                        Text("Navigation Source: $source", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(16.dp))

                        MapScreen(
                            modifier = Modifier.weight(1f),
                            onMapTap = { lat, lon -> viewModel.onMapTap(lat, lon) }
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        if (dest != null && !isMoving) {
                            Button(onClick = { viewModel.startSimulation() }) {
                                Text("Start Simulation")
                            }
                        } else {
                            Text("Tap the map to set start, then destination")
                        }
                    }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, locationPermission) == PackageManager.PERMISSION_GRANTED) {
            startGpsAndSensors()
        } else {
            requestPermissions(arrayOf(locationPermission), 100)
        }
    }

    private fun startGpsAndSensors() {
        viewModel.startSensors()

        if (ContextCompat.checkSelfPermission(this, locationPermission) != PackageManager.PERMISSION_GRANTED) return

        val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        locationManager.requestLocationUpdates(
            android.location.LocationManager.GPS_PROVIDER,
            1000L,
            1f,
            object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) {}
            }
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startGpsAndSensors()
        }
    }
}