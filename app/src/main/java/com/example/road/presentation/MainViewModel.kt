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

    private var startNodeId: Long? = null
    private var destNodeId: Long? = null

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

    fun onMapTap(lat: Double, lon: Double) {
        Log.d("MainViewModel", "Tap: ($lat, $lon) graphReady=${_graphReady.value}")

        if (!_graphReady.value) {
            _routeError.value = "Map data not ready — please wait"
            return
        }

        val graph = graphRepository.getGraph() ?: run {
            _routeError.value = "Graph not available"
            return
        }

        val node = graphRepository.findNearest(graph, lat, lon) ?: return
        Log.d("MainViewModel", "Nearest node: ${node.id} at (${node.lat}, ${node.lon})")

        // 3-state tap machine: start → dest → reset+newStart
        when {
            startNodeId == null && destNodeId == null -> {
                startNodeId = node.id
                _startPosition.value = Position(lat, lon)
                // This tap IS "where I am" — there's no GPS, so it becomes
                // the fixed origin for sensor-based dead reckoning.
                resilienceManager.setInitialPosition(lat, lon)
                _instruction.value = "Tap map: set DESTINATION"
                Log.d("MainViewModel", "Start set: node=${node.id}")
            }
            startNodeId != null && destNodeId == null -> {
                destNodeId = node.id
                _destPosition.value = Position(lat, lon)
                _instruction.value = "Route shown. Tap map or Reset to start over"
                Log.d("MainViewModel", "Dest set: node=${node.id}")
            }
            else -> {
                // Reset and place new start
                startNodeId = node.id
                destNodeId = null
                _startPosition.value = Position(lat, lon)
                _destPosition.value = null
                _route.value = emptyList()
                resilienceManager.setInitialPosition(lat, lon)
                _instruction.value = "Tap map: set DESTINATION"
                Log.d("MainViewModel", "Reset + new start: node=${node.id}")
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
        _instruction.value = "Route shown. Tap map or Reset to start over"
        Log.d("MainViewModel", "Route: ${ghPoints.size} points")
    }

    fun resetAll() {
        startNodeId = null
        destNodeId = null
        _startPosition.value = null
        _destPosition.value = null
        _route.value = emptyList()
        _instruction.value = "Tap map: set START (your current location)"
        _routeError.value = null
        resilienceManager.clearPosition()
        Log.d("MainViewModel", "Reset")
    }

    fun startSimulation() {
        Log.d("MainViewModel", "startSimulation — not yet implemented")
    }

    fun startSensors() {
        resilienceManager.startSensors()
    }

    fun stopSensors() {
        resilienceManager.stopSensors()
    }
}