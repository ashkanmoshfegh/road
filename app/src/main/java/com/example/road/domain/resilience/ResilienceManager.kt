package com.example.road.domain.resilience

import android.hardware.Sensor
import android.content.Context
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import com.example.road.data.m.model.Position
import com.example.road.data.m.local.repository.GraphRepository
import com.example.road.domain.routing.RouteCalculator
import com.example.road.domain.routing.TrafficPredictor
import com.example.road.utils.GraphLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResilienceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sensorManager: SensorManager,
    private val graphRepository: GraphRepository,
    private val trafficPredictor: TrafficPredictor
) : SensorEventListener {

    private val _currentPosition = MutableStateFlow<Position?>(null)
    val currentPosition: StateFlow<Position?> = _currentPosition.asStateFlow()

    private val _currentSource = MutableStateFlow<String>("INS")
    val currentSource: StateFlow<String> = _currentSource.asStateFlow()

    // INS state
    private val sensorFusion = SensorFusion()
    private var mapMatcher: MapMatcher? = null
    private var routeCalculator: RouteCalculator? = null

    // GPS anchor — last good GPS fix used to correct INS drift
    private var gpsAnchor: Position? = null
    private var lastGpsTime = 0L

    // Sensor data
    private var latestAccel = FloatArray(3)
    private var latestGyro = FloatArray(3)
    private var hasAccel = false
    private var hasGyro = false

    // Whether sensors are registered
    private var sensorsActive = false

    suspend fun initialize() {
        // GraphHopper loading is a suspend fun that dispatches to IO — non-blocking.
        val gh = graphRepository.loadGraph()
        if (gh == null) {
            Log.e("ResilienceManager", "GraphHopper failed to load — routing will not work")
        } else {
            Log.d("ResilienceManager", "GraphHopper loaded successfully")
        }
        routeCalculator = RouteCalculator(graphRepository)
        // Defer the heavy 57MB JSON parse (GraphLoader) + MapMatcher construction
        // to a background coroutine so it doesn't block the main thread.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (nodes, edges) = GraphLoader.loadGraph(context, "graph.json")
                withContext(Dispatchers.Main) {
                    mapMatcher = MapMatcher(edges, nodes)
                    Log.d("ResilienceManager", "MapMatcher initialized with ${nodes.size} nodes, ${edges.size} edges")
                }
            } catch (e: Exception) {
                Log.e("ResilienceManager", "Failed to load graph.json for MapMatcher", e)
            }
        }
    }

    // ---------- GPS callback (feeds anchor for INS correction) ----------

    fun onGpsLocation(location: Location) {
        val pos = Position(
            latitude = location.latitude,
            longitude = location.longitude,
            bearing = location.bearing,
            accuracy = location.accuracy,
            timestamp = location.time
        )

        // Always update GPS anchor — INS will correct toward it
        gpsAnchor = pos
        lastGpsTime = location.time

        // If GPS is accurate (< 20m), use it as the primary position
        // and reset the INS to avoid drift accumulation
        if (location.accuracy < 20f && location.hasBearing()) {
            _currentPosition.value = pos
            _currentSource.value = "GPS"
            sensorFusion.reset(location.bearing)
            // Reset INS displacement relative to this GPS anchor
            return
        }

        // GPS is available but not accurate enough — INS provides the position,
        // GPS anchor is used for periodic correction (done in onSensorChanged)
        if (_currentPosition.value == null) {
            // No position yet — use GPS as initial anchor
            _currentPosition.value = pos
            sensorFusion.reset(location.bearing)
        }
    }

    // ---------- Sensor callback (INS dead-reckoning) ----------

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                latestAccel = event.values.clone()
                hasAccel = true
            }
            Sensor.TYPE_GYROSCOPE -> {
                latestGyro = event.values.clone()
                hasGyro = true
            }
        }

        if (!sensorsActive || !hasAccel || !hasGyro) return

        val gyroZ = latestGyro[2]
        val accelMag = sqrt(latestAccel[0] * latestAccel[0] +
                latestAccel[1] * latestAccel[1] +
                latestAccel[2] * latestAccel[2])
        val currentTime = System.currentTimeMillis()

        val displacement = sensorFusion.update(gyroZ, accelMag, currentTime)
        displacement?.let { (dx, dy) ->
            // Start from GPS anchor if available, otherwise from last position
            val base = gpsAnchor ?: _currentPosition.value ?: return@let

            val newLat = base.latitude + dx / 111320.0
            val newLon = base.longitude +
                    dy / (111320.0 * kotlin.math.cos(Math.toRadians(base.latitude)))

            var newPos = Position(
                latitude = newLat,
                longitude = newLon,
                bearing = sensorFusion.getHeadingDegrees(),
                timestamp = currentTime
            )

            // Apply map matching to snap to roads
            val matched = mapMatcher?.match(newPos) ?: newPos

            // Periodically correct toward GPS anchor (every 5 seconds or if GPS is recent)
            if (gpsAnchor != null && (currentTime - lastGpsTime) < 5000L) {
                // Gentle correction: move 10% toward GPS anchor each update
                val corrFactor = 0.1
                val corrLat = matched.latitude + (gpsAnchor!!.latitude - matched.latitude) * corrFactor
                val corrLon = matched.longitude + (gpsAnchor!!.longitude - matched.longitude) * corrFactor
                newPos = Position(
                    latitude = corrLat,
                    longitude = corrLon,
                    bearing = matched.bearing,
                    timestamp = currentTime
                )
            }

            _currentPosition.value = newPos
            _currentSource.value = "INS"
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------- Sensor lifecycle ----------

    fun startSensors() {
        if (sensorsActive) return
        sensorsActive = true

        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // Use SENSOR_DELAY_GAME (20ms) for smoother, more frequent updates
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        }
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
        }

        // Also register GPS listener — GPS feeds the anchor for INS correction
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,  // 1 second interval
                    1f,     // 1 meter movement
                    gpsLocationListener
                )
            }
            // Also try network provider for faster initial fix
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    1f,
                    gpsLocationListener
                )
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    fun stopSensors() {
        sensorsActive = false
        sensorManager.unregisterListener(this)
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.removeUpdates(gpsLocationListener)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    // ---------- GPS location listener (inner class) ----------

    private val gpsLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onGpsLocation(location)
        }
        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onStatusChanged(provider: String, status: Int, extras: android.os.Bundle?) {}
    }

    // ---------- Routing (existing) ----------

    fun getRoute(destination: Position): List<Position> {
        val from = _currentPosition.value ?: return emptyList()
        val calculator = routeCalculator ?: return emptyList()
        val path = calculator.calculateRoute(from, destination)
        return path.map { Position(it.lat, it.lon, bearing = 0f) }
    }

    fun simulateGpsLocation(lat: Double, lon: Double, bearing: Float) {
        val loc = Location("simulated").apply {
            this.latitude = lat
            this.longitude = lon
            this.bearing = bearing
            this.accuracy = 5f
            this.time = System.currentTimeMillis()
        }
        onGpsLocation(loc)
    }
}
