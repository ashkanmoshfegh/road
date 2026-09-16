package com.example.road.domain.resilience

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.example.road.data.m.local.repository.GraphRepository
import com.example.road.data.m.model.Position
import com.example.road.domain.routing.RouteCalculator
import com.example.road.domain.routing.TrafficPredictor
import com.example.road.utils.GraphLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks position using ONLY onboard sensors (accelerometer + gyroscope),
 * starting from a fixed point the user taps on the map. GPS is never used.
 *
 * Adds simulation support: while [simulationActive] is true, real sensor
 * samples are ignored and position instead comes from whatever
 * [setSimulatedPosition] is fed (driven by MainViewModel walking the
 * computed route).
 */
@Singleton
class ResilienceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val graphRepository: GraphRepository,
    private val trafficPredictor: TrafficPredictor
) : SensorEventListener {

    private val _currentPosition = MutableStateFlow<Position?>(null)
    val currentPosition: StateFlow<Position?> = _currentPosition.asStateFlow()

    private val sensorFusion = SensorFusion()
    private var mapMatcher: MapMatcher? = null
    private var routeCalculator: RouteCalculator? = null

    // Fixed dead-reckoning origin. Set (and re-settable) via setInitialPosition()
    // whenever the user taps/re-taps their starting point.
    private var originPosition: Position? = null

    private var latestAccel = FloatArray(3)
    private var latestGyro = FloatArray(3)
    private var hasAccel = false
    private var hasGyro = false
    private var sensorsActive = false

    // Simulation mode: when active, real sensor input is ignored entirely,
    // and position instead comes from setSimulatedPosition() calls.
    @Volatile private var simulationActive = false

    private val sensorThread = HandlerThread("ResilienceSensorThread").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)

    private val minMatchIntervalMs = 150L
    private var lastMatchTime = 0L

    suspend fun initialize(): Boolean {
        Log.d("ResilienceManager", "initialize() called")
        return try {
            val gh = graphRepository.loadGraph()
            if (gh == null) {
                Log.e("ResilienceManager", "GraphHopper failed to load — routing will not work")
                false
            } else {
                Log.d("ResilienceManager", "GraphHopper loaded successfully")
                routeCalculator = RouteCalculator(graphRepository)
                // Skip GraphLoader (116MB JSON causes OOM); use GraphRepository.findNearest directly
                true
            }
        } catch (e: Exception) {
            Log.e("ResilienceManager", "initialize() crashed", e)
            false
        }
    }

    /**
     * Sets/re-sets the fixed origin for dead reckoning. Safe to call more
     * than once — e.g. when the user taps "Set Start" again to move it.
     */
    fun setInitialPosition(lat: Double, lon: Double, bearingDeg: Float = 0f) {
        val pos = Position(latitude = lat, longitude = lon, bearing = bearingDeg, accuracy = 0f)
        originPosition = pos
        _currentPosition.value = pos
        sensorFusion.reset(bearingDeg)
        lastMatchTime = 0L
        Log.d("ResilienceManager", "Initial position set: ($lat, $lon)")
    }

    fun clearPosition() {
        originPosition = null
        _currentPosition.value = null
        sensorFusion.reset()
    }

    /** Toggles simulation mode. While true, onSensorChanged() is a no-op. */
    fun setSimulationActive(active: Boolean) {
        simulationActive = active
        if (!active) {
            // Leaving simulation — reset dead reckoning from wherever the
            // simulation left off, so real sensor tracking resumes cleanly
            // instead of jumping back to the old pre-simulation origin.
            _currentPosition.value?.let { pos ->
                originPosition = pos
                sensorFusion.reset(pos.bearing)
                lastMatchTime = 0L
            }
        }
    }

    /** Directly sets the displayed position while simulating a route walk-through. */
    fun setSimulatedPosition(pos: Position) {
        _currentPosition.value = pos
    }

    // Runs on sensorThread, not the main thread.
    override fun onSensorChanged(event: SensorEvent?) {
        if (simulationActive) return
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> { latestAccel = event.values.clone(); hasAccel = true }
            Sensor.TYPE_GYROSCOPE -> { latestGyro = event.values.clone(); hasGyro = true }
        }

        if (!sensorsActive || !hasAccel || !hasGyro) return
        val origin = originPosition ?: return

        val gyroZ = latestGyro[2]
        val accelMag = sqrt(
            latestAccel[0] * latestAccel[0] +
                    latestAccel[1] * latestAccel[1] +
                    latestAccel[2] * latestAccel[2]
        )
        val currentTime = System.currentTimeMillis()

        val displacement = sensorFusion.update(gyroZ, accelMag, currentTime) ?: return
        val (dx, dy) = displacement

        val newLat = origin.latitude + dx / 111320.0
        val newLon = origin.longitude +
                dy / (111320.0 * kotlin.math.cos(Math.toRadians(origin.latitude)))

        val rawPos = Position(
            latitude = newLat,
            longitude = newLon,
            bearing = sensorFusion.getHeadingDegrees(),
            timestamp = currentTime
        )

        val shouldMatch = currentTime - lastMatchTime >= minMatchIntervalMs
        val finalPos = if (shouldMatch) {
            lastMatchTime = currentTime
            // Match to nearest road via GraphHopper instead of loading JSON
            val graph = graphRepository.getGraph()
            val snapped = if (graph != null) {
                val nearest = graphRepository.findNearest(graph, rawPos.latitude, rawPos.longitude)
                if (nearest != null) Position(nearest.lat, nearest.lon, rawPos.bearing, rawPos.accuracy, rawPos.timestamp)
                else rawPos
            } else rawPos
            snapped
        } else {
            rawPos
        }

        _currentPosition.value = finalPos
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @SuppressLint("MissingPermission")
    fun startSensors() {
        if (sensorsActive) return
        sensorsActive = true

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
        }
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
        }
    }

    fun stopSensors() {
        sensorsActive = false
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.unregisterListener(this)
    }

    fun getRoute(destination: Position): List<Position> {
        val from = _currentPosition.value ?: return emptyList()
        val calculator = routeCalculator ?: return emptyList()
        val path = calculator.calculateRoute(from, destination)
        return path.map { Position(it.lat, it.lon, bearing = 0f) }
    }
}