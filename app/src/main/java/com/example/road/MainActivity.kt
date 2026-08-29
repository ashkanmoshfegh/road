package com.example.road

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.road.ui.theme.RoadTheme

class MainActivity : ComponentActivity() {

    private lateinit var locationMonitor: LocationMonitor
    private lateinit var ins: InertialNavigationSystem
    private lateinit var sensorProvider: SensorProvider
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Components
        ins = InertialNavigationSystem()
        sensorProvider = SensorProvider(this)
        locationMonitor = LocationMonitor(this, ins, sensorProvider)

        setContent {
            RoadTheme {
                var currentPos by remember { mutableStateOf<NavPosition?>(null) }
                var source by remember { mutableStateOf("Initializing...") }
                var hasPermission by remember { 
                    mutableStateOf(checkPermission()) 
                }

                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    hasPermission = permissions.values.all { it }
                }

                LaunchedEffect(hasPermission) {
                    if (hasPermission) {
                        startSystem { pos, src ->
                            currentPos = pos
                            source = src
                        }
                    } else {
                        launcher.launch(arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ))
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Ride-Hailing Resilience System",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Spacer(modifier = Modifier.height(32.dp))

                        StatusCard(source, currentPos)
                    }
                }
            }
        }
    }

    @Composable
    fun StatusCard(source: String, pos: NavPosition?) {
        val isResilient = source.contains("INS")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isResilient) Color(0xFFFFEBEE) else Color(0xFFE8F5E9)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Mode: $source",
                    fontWeight = FontWeight.Bold,
                    color = if (isResilient) Color.Red else Color(0xFF2E7D32)
                )
                Spacer(modifier = Modifier.height(8.dp))
                pos?.let {
                    Text(text = "Latitude: ${it.latitude}")
                    Text(text = "Longitude: ${it.longitude}")
                    Text(text = "Heading: ${it.heading} degrees")
                } ?: Text(text = "Waiting for location...")
            }
        }
    }

    private fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun startSystem(onUpdate: (NavPosition, String) -> Unit) {
        locationMonitor.startMonitoring(object : LocationMonitor.LocationUpdateListener {
            override fun onLocationChanged(position: NavPosition, source: String) {
                runOnUiThread { onUpdate(position, source) }
            }
        })

        // Periodic check to trigger INS if GPS fails
        val runnable = object : Runnable {
            override fun run() {
                locationMonitor.update()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(runnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        locationMonitor.stopMonitoring()
        handler.removeCallbacksAndMessages(null)
    }
}
