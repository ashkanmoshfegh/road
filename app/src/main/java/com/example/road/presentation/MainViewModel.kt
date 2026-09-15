package com.example.road.presentation

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.road.data.m.local.repository.GraphRepository
import com.example.road.data.m.model.Position
import com.example.road.domain.routing.RouteCalculator
import com.example.road.domain.resilience.ResilienceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class EditTarget { START, DEST, NONE }

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val graphRepository: GraphRepository,
    private val routeCalculator: RouteCalculator,
    private val resilienceManager: ResilienceManager,
) : AndroidViewModel(application) {

    private val _graphReady = MutableStateFlow(false)
    val graphReady: StateFlow<Boolean> = _graphReady

    private val _routeError = MutableStateFlow<String?>(null)
    val routeError: StateFlow<String?> = _routeError

    private val _startPosition = MutableStateFlow<Position?>(null)
    val startPosition: StateFlow<Position?> = _startPosition

    private val _destPosition = MutableStateFlow<Position?>(null)
    val destPosition: StateFlow<Position?> = _destPosition

    private val _route = MutableStateFlow<List<Position>>(emptyList())
    val route: StateFlow<List<Position>> = _route

    private val _instruction = MutableStateFlow("Tap map: set START (your current location)")
    val instruction: StateFlow<String> = _instruction

    private val _currentPosition = MutableStateFlow<Position?>(null)
    val currentPosition: StateFlow<Position?> = _currentPosition

    // Which point the next tap will set. Replaces the old implicit
    // "first tap = start, second tap = dest, third tap = reset+restart"
    // sequence, which had no way to go back and move START again without
    // pressing Reset. Now "Set Start" / "Set Destination" buttons let the
    // user re-enter either mode at any time.
    private val _editTarget = MutableStateFlow(EditTarget.START)
    val editTarget: StateFlow<EditTarget> = _editTarget

    private val _isSimulating = MutableStateFlow(false)
    val isSimulating: StateFlow<Boolean> = _isSimulating

    private var startNodeId: Long? = null
    private var destNodeId: Long? = null
    private var simulationJob: Job? = null

    init {
        Log.d("MainViewModel", "created, starting graph load")
        viewModelScope.launch {
            val ok = resilienceManager.initialize()
            if (!ok) {
                _routeError.value = "Graph load failed — check Logcat for GraphHopper errors"
            }
        }
        loadGraphPoll()
        observePosition()
    }

    private fun loadGraphPoll() {
        viewModelScope.launch {
            var elapsed = 0L
            while (elapsed < 180_000 && !_graphReady.value) {
                val gh = graphRepository.getGraph()
                if (gh != null) {
                    _graphReady.value = true
                    Log.d("MainViewModel", "Graph ready after ${elapsed / 1000}s")
                    break
                }
                delay(1000)
                elapsed += 1000
            }
            if (!_graphReady.value) {
                Log.w("MainViewModel", "Graph timeout after 180s")
                _routeError.value = "Map data is taking longer than expected. Tap Retry to try again."
            }
        }
    }

    private fun observePosition() {
        viewModelScope.launch {
            resilienceManager.currentPosition.collect { pos ->
                _currentPosition.value = pos
            }
        }
    }

    fun retryLoadGraph() {
        _graphReady.value = false
        _routeError.value = null
        _instruction.value = "Retrying map load..."
        Log.d("MainViewModel", "Retry: reloading graph")
        viewModelScope.launch {
            val ok = resilienceManager.initialize()
            if (!ok) {
                _routeError.value = "Retry failed — check Logcat"
            }
        }
        loadGraphPoll()
    }

    /** Switches to "next tap sets START" mode. Callable at any time, not just on Reset. */
    fun beginSetStart() {
        _editTarget.value = EditTarget.START
        _instruction.value = "Tap map: set START (your current location)"
        _routeError.value = null
    }

    /** Switches to "next tap sets DESTINATION" mode. Callable at any time. */
    fun beginSetDest() {
        _editTarget.value = EditTarget.DEST
        _instruction.value = "Tap map: set DESTINATION"
        _routeError.value = null
    }

    fun onMapTap(lat: Double, lon: Double) {
        Log.d("MainViewModel", "Tap: ($lat, $lon) graphReady=${_graphReady.value} target=${_editTarget.value}")

        if (!_graphReady.value) {
            _routeError.value = "Map data not ready — please wait"
            return
        }

        val graph = graphRepository.getGraph() ?: run {
            _routeError.value = "Graph not available"
            return
        }

        // node.lat/node.lon are the actual snapped-to-road point, not the
        // raw tap — see GraphRepository.findNearest.
        val node = graphRepository.findNearest(graph, lat, lon) ?: run {
            _routeError.value = "No road found near that point"
            return
        }
        Log.d("MainViewModel", "Nearest road point: node=${node.id} at (${node.lat}, ${node.lon})")

        when (_editTarget.value) {
            EditTarget.START -> {
                startNodeId = node.id
                _startPosition.value = Position(node.lat, node.lon)
                resilienceManager.setInitialPosition(node.lat, node.lon)
                _routeError.value = null

                if (destNodeId != null) {
                    _editTarget.value = EditTarget.NONE
                    if (_route.value.isNotEmpty()) {
                        // Start moved after a route already existed — refresh it
                        // instead of leaving a stale route on screen.
                        calculateRoute()
                    } else {
                        _instruction.value = "Route shown. Use buttons to change Start/Destination"
                        calculateRoute()
                    }
                } else {
                    _editTarget.value = EditTarget.DEST
                    _instruction.value = "Tap map: set DESTINATION"
                }
            }
            EditTarget.DEST -> {
                destNodeId = node.id
                _destPosition.value = Position(node.lat, node.lon)
                _routeError.value = null
                _editTarget.value = EditTarget.NONE

                if (startNodeId != null) {
                    calculateRoute()
                } else {
                    _instruction.value = "Now set START"
                    _editTarget.value = EditTarget.START
                }
            }
            EditTarget.NONE -> {
                _routeError.value = "Tap 'Set Start' or 'Set Destination' to move a point"
            }
        }
    }

    fun calculateRoute() {
        Log.d("MainViewModel", "calculateRoute start=$startNodeId dest=$destNodeId")

        if (!_graphReady.value) {
            _routeError.value = "Map data not ready"
            return
        }
        if (startNodeId == null || destNodeId == null) {
            _routeError.value = "Set both start and destination first"
            return
        }

        val graph = graphRepository.getGraph() ?: run {
            _routeError.value = "Graph not available"
            return
        }

        val startPos = _startPosition.value ?: return
        val destPos  = _destPosition.value ?: return

        val ghPoints = try {
            routeCalculator.calculateRoute(startPos, destPos)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Route calc failed", e)
            _routeError.value = "Route calculation failed: ${e.message}"
            return
        }

        if (ghPoints.isEmpty()) {
            _routeError.value = "No route found between these points"
            Log.w("MainViewModel", "No route found")
            return
        }

        _route.value = ghPoints.map { ghp ->
            Position(latitude = ghp.lat, longitude = ghp.lon)
        }
        _instruction.value = "Route shown. Use buttons to change Start/Destination"
        Log.d("MainViewModel", "Route: ${ghPoints.size} points")
    }

    fun resetAll() {
        stopSimulation()
        startNodeId = null
        destNodeId = null
        _startPosition.value = null
        _destPosition.value = null
        _route.value = emptyList()
        _editTarget.value = EditTarget.START
        _instruction.value = "Tap map: set START (your current location)"
        _routeError.value = null
        resilienceManager.clearPosition()
        Log.d("MainViewModel", "Reset")
    }

    /** Walks the calculated route at a fixed pace, driving currentPosition. */
    fun startSimulation() {
        val points = _route.value
        if (points.size < 2) {
            _routeError.value = "No route to simulate — calculate a route first"
            return
        }
        simulationJob?.cancel()
        resilienceManager.setSimulationActive(true)
        _isSimulating.value = true

        simulationJob = viewModelScope.launch {
            for (i in points.indices) {
                val p = points[i]
                val bearing = if (i < points.size - 1) bearingBetween(p, points[i + 1]) else 0f
                resilienceManager.setSimulatedPosition(Position(p.latitude, p.longitude, bearing))
                delay(400L)
            }
            _isSimulating.value = false
            resilienceManager.setSimulationActive(false)
        }
    }

    fun stopSimulation() {
        simulationJob?.cancel()
        simulationJob = null
        if (_isSimulating.value) {
            _isSimulating.value = false
            resilienceManager.setSimulationActive(false)
        }
    }

    private fun bearingBetween(a: Position, b: Position): Float {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val brngDeg = Math.toDegrees(atan2(y, x))
        return ((brngDeg + 360.0) % 360.0).toFloat()
    }

    fun startSensors() {
        resilienceManager.startSensors()
    }

    fun stopSensors() {
        resilienceManager.stopSensors()
    }
}