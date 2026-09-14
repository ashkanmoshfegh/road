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
 * Tracks the device's position using ONLY onboard sensors — accelerometer +
 * gyroscope dead-reckoning — starting from a fixed point the user taps on
 * the map (their "I am here" tap). GPS/LocationManager is deliberately never
 * used anywhere in this class.
 *
 * Because there's no GPS to periodically re-anchor against, all displacement
 * is measured relative to [originPosition], which is set exactly once per
 * "session" via [setInitialPosition] and never moved afterward. This means
 * position will drift over time/distance — that's an inherent limitation of
 * sensor-only dead reckoning, not a bug — [MapMatcher] snapping to road
 * geometry is what keeps the drift from looking obviously wrong on screen.
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

    // Fixed dead-reckoning origin. Set once by setInitialPosition() when the
    // user taps their starting point; never updated afterward.
    private var originPosition: Position? = null

    private var latestAccel = FloatArray(3)
    private var latestGyro = FloatArray(3)
    private var hasAccel = false
    private var hasGyro = false
    private var sensorsActive = false

    // Sensor callbacks run here, off the main thread — map-matching against
    // ~169K edges is too heavy to do on Dispatchers.Main.
    private val sensorThread = HandlerThread("ResilienceSensorThread").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)

    // Only run the expensive map-matching scan this often; displacement is
    // still integrated on every sensor sample regardless.
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
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val (nodes, edges) = GraphLoader.loadGraph(context, "graph.json")
                        val matcher = MapMatcher(edges, nodes)
                        withContext(Dispatchers.Main) {
                            mapMatcher = matcher
                            Log.d("ResilienceManager", "MapMatcher initialized with ${nodes.size} nodes, ${edges.size} edges")
                        }
                    } catch (e: Exception) {
                        Log.e("ResilienceManager", "Failed to load graph.json for MapMatcher", e)
                    }
                }
                true
            }
        } catch (e: Exception) {
            Log.e("ResilienceManager", "initialize() crashed", e)
            false
        }
    }

    /**
     * Call when the user taps the map to mark their current/starting
     * location. This becomes the fixed origin for all subsequent
     * sensor-based dead reckoning.
     */
    fun setInitialPosition(lat: Double, lon: Double, bearingDeg: Float = 0f) {
        val pos = Position(latitude = lat, longitude = lon, bearing = bearingDeg, accuracy = 0f)
        originPosition = pos
        _currentPosition.value = pos
        sensorFusion.reset(bearingDeg)
        lastMatchTime = 0L
        Log.d("ResilienceManager", "Initial position set: ($lat, $lon)")
    }

    /** Call on Reset — stops drawing/tracking a position until the next tap. */
    fun clearPosition() {
        originPosition = null
        _currentPosition.value = null
        sensorFusion.reset()
    }

    // Runs on sensorThread (see startSensors()), not the main thread.
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> { latestAccel = event.values.clone(); hasAccel = true }
            Sensor.TYPE_GYROSCOPE -> { latestGyro = event.values.clone(); hasGyro = true }
        }

        if (!sensorsActive || !hasAccel || !hasGyro) return
        val origin = originPosition ?: return // no start point tapped yet — nothing to track from

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
            mapMatcher?.match(rawPos) ?: rawPos
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
        // No GPS/LocationManager registration — positioning is sensor-only,
        // by design.
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