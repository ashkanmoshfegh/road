package com.example.road.domain.resilience

import android.hardware.Sensor
import android.content.Context
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import com.example.road.data.m.model.Position
import com.example.road.data.m.local.repository.GraphRepository
import com.example.road.domain.routing.RouteCalculator
import com.example.road.domain.routing.TrafficPredictor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton
import com.example.road.utils.GraphLoader
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class ResilienceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sensorManager: SensorManager,
    private val graphRepository: GraphRepository,
    private val trafficPredictor: TrafficPredictor
) : SensorEventListener {

    private val _currentPosition = MutableStateFlow<Position?>(null)
    val currentPosition: StateFlow<Position?> = _currentPosition.asStateFlow()

    private val _currentSource = MutableStateFlow<String>("GPS")
    val currentSource: StateFlow<String> = _currentSource.asStateFlow()

    private var isGpsValid = true
    private var lastGpsPosition: Position? = null
    private var mapMatcher: MapMatcher? = null
    private var routeCalculator: RouteCalculator? = null

    private val sensorFusion = SensorFusion()

    // store latest sensor values
    private var latestAccel = FloatArray(3)
    private var latestGyro = FloatArray(3)
    private var hasAccel = false
    private var hasGyro = false

    suspend fun initialize() {
        // Load GraphHopper for routing
        val graph = graphRepository.loadGraph()
        routeCalculator = RouteCalculator(graph)

        // Load nodes/edges from JSON for map matching
        val (nodes, edges) = GraphLoader.loadGraph(context, "graph.json")
        mapMatcher = MapMatcher(edges, nodes)
    }

    fun onGpsLocation(location: Location) {
        val pos = Position(
            latitude = location.latitude,
            longitude = location.longitude,
            bearing = location.bearing,
            accuracy = location.accuracy,
            timestamp = location.time
        )
        lastGpsPosition = pos
        isGpsValid = location.accuracy < 20f

        if (isGpsValid) {
            _currentPosition.value = pos
            _currentSource.value = "GPS"
            sensorFusion.reset(location.bearing)
        }
    }

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

        // Process only when we have both sensors and GPS is invalid
        if (!isGpsValid && hasAccel && hasGyro && lastGpsPosition != null) {
            val gyroZ = latestGyro[2]
            val accelMag = sqrt(latestAccel[0]*latestAccel[0] + latestAccel[1]*latestAccel[1] + latestAccel[2]*latestAccel[2])
            val currentTime = System.currentTimeMillis()
            val displacement = sensorFusion.update(gyroZ, accelMag, currentTime)
            displacement?.let { (dx, dy) ->
                val newLat = lastGpsPosition!!.latitude + dx / 111320.0
                val newLon = lastGpsPosition!!.longitude + dy / (111320.0 * kotlin.math.cos(lastGpsPosition!!.latitude))
                val newPos = Position(
                    latitude = newLat,
                    longitude = newLon,
                    bearing = sensorFusion.getHeadingDegrees()
                )
                val matched = mapMatcher?.match(newPos) ?: newPos
                _currentPosition.value = matched
                _currentSource.value = "INS (Resilient)"
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun startSensors() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST)
    }

    fun stopSensors() {
        sensorManager.unregisterListener(this)
    }

    fun getRoute(destination: Position): List<Position> {
        val from = _currentPosition.value ?: return emptyList()
        val calculator = routeCalculator ?: return emptyList()
        val path = calculator.calculateRoute(from, destination)
        return path.map { Position(it.lat, it.lon, bearing = 0f) }
    }
}