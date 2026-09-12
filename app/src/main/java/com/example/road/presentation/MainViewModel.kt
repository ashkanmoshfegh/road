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
import org.osmdroid.util.GeoPoint
import javax.inject.Inject
import javax.inject.Singleton

@HiltViewModel
@Singleton
class MainViewModel @Inject constructor(
    application: Application,
    private val graphRepository: GraphRepository,
    private val routeCalculator: RouteCalculator,
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

    private val _instruction = MutableStateFlow("Tap map: set START")
    val instruction: StateFlow<String> = _instruction

    private val _currentPosition = MutableStateFlow<Position?>(null)
    val currentPosition: StateFlow<Position?> = _currentPosition

    private var startNodeId: Long? = null
    private var destNodeId: Long? = null

    init {
        Log.d("MainViewModel", "created")
        loadGraphFlow()
    }

    private fun loadGraphFlow() {
        viewModelScope.launch {
            // Poll: wait for graphRepository.loadGraph() to complete
            var attempts = 0
            while (attempts < 180 && _graphReady.value == false) {
                val gh = graphRepository.getGraph()
                if (gh != null) {
                    _graphReady.value = true
                    Log.d("MainViewModel", "Graph ready after ${attempts}s")
                    break
                }
                delay(1000)
                attempts++
            }
            if (_graphReady.value == false) {
                Log.w("MainViewModel", "Graph not ready after 180s")
                _routeError.value = "Map data is taking longer than expected. Tap Retry to try again."
            }
        }
    }

    fun retryLoadGraph() {
        _graphReady.value = false
        _routeError.value = null
        Log.d("MainViewModel", "Retrying graph load")
        graphRepository.loadGraph()
        loadGraphFlow()
    }

    fun onMapTap(lat: Double, lon: Double) {
        Log.d("MainViewModel", "onMapTap: lat=$lat lon=$lon")
        if (!_graphReady.value) {
            _routeError.value = "Map data not ready yet — please wait"
            return
        }

        val graph = graphRepository.getGraph()
        if (graph == null) {
            _routeError.value = "Graph not available"
            return
        }

        val node = graphRepository.findNearest(graph, lat, lon) ?: return
        Log.d("MainViewModel", "Nearest node: id=${node.id} lat=${node.lat} lon=${node.lon}")

        // After destination is set, tapping resets AND sets new start
        when {
            startNodeId == null && destNodeId == null -> {
                // Place start
                startNodeId = node.id
                _startPosition.value = Position(lat, lon)
                _instruction.value = "Tap map: set DESTINATION"
                Log.d("MainViewModel", "Start set: node=${node.id}")
            }
            startNodeId != null && destNodeId == null -> {
                // Place destination
                destNodeId = node.id
                _destPosition.value = Position(lat, lon)
                _instruction.value = "Route shown. Tap map or Reset to start over"
                Log.d("MainViewModel", "Dest set: node=${node.id}")
            }
            else -> {
                // Reset + set new start
                startNodeId = node.id
                destNodeId = null
                _startPosition.value = Position(lat, lon)
                _destPosition.value = null
                _route.value = emptyList()
                _instruction.value = "Tap map: set DESTINATION"
                Log.d("MainViewModel", "Reset & new start: node=${node.id}")
            }
        }
    }

    fun calculateRoute() {
        Log.d("MainViewModel", "calculateRoute startNodeId=$startNodeId destNodeId=$destNodeId")
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

        val startNode = graphRepository.findNearest(graph, _startPosition.value?.latitude ?: return, _startPosition.value?.longitude ?: return)
        val destNode  = graphRepository.findNearest(graph, _destPosition.value?.latitude ?: return, _destPosition.value?.longitude ?: return)

        if (startNode == null || destNode == null) {
            _routeError.value = "Could not find route nodes"
            return
        }

        // Use RouteCalculator for shortest path via graph
        val path = try {
            routeCalculator.calculateRoute(startNode, destNode)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Route calculation failed", e)
            _routeError.value = "Route calculation failed: ${e.message}"
            return
        }

        if (path.isEmpty()) {
            _routeError.value = "No route found between these points"
            return
        }

        _route.value = path.map { Position(it.lat, it.lon) }
        _instruction.value = "Route shown. Tap map or Reset to start over"
        Log.d("MainViewModel", "Route computed: ${path.size} points")
    }

    fun resetAll() {
        startNodeId = null
        destNodeId = null
        _startPosition.value = null
        _destPosition.value = null
        _route.value = emptyList()
        _instruction.value = "Tap map: set START"
        _routeError.value = null
        Log.d("MainViewModel", "Reset complete")
    }

    fun startSimulation() {
        Log.d("MainViewModel", "startSimulation called")
        // INS/simulation not yet wired — placeholder
    }

    override fun onCleared() {
        super.onCleared()
        Log.d("MainViewModel", "cleared")
    }
}
