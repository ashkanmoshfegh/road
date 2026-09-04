package com.example.road.presentation

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.road.data.m.model.Position
import com.example.road.domain.resilience.ResilienceManager
import com.example.road.domain.routing.RouteCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val resilienceManager: ResilienceManager,
    private val routeCalculator: RouteCalculator
) : ViewModel() {

    // ----- UI state -----
    private val _start = MutableStateFlow<Position?>(null)
    val start: StateFlow<Position?> = _start.asStateFlow()

    private val _dest = MutableStateFlow<Position?>(null)
    val dest: StateFlow<Position?> = _dest.asStateFlow()

    private val _routePoints = MutableStateFlow<List<Position>>(emptyList())
    val routePoints: StateFlow<List<Position>> = _routePoints.asStateFlow()

    private val _isMoving = MutableStateFlow(false)
    val isMoving: StateFlow<Boolean> = _isMoving.asStateFlow()

    // Expose source from resilienceManager
    val currentSource: StateFlow<String> = resilienceManager.currentSource

    init {
        viewModelScope.launch {
            resilienceManager.initialize()
        }
    }

    // ----- Map taps -----
    fun onMapTap(lat: Double, lon: Double) {
        val pos = Position(lat, lon, 0f)
        if (_start.value == null) {
            _start.value = pos
        } else if (_dest.value == null) {
            _dest.value = pos
            calculateRoute()
        } else {
            // Reset: start becomes new point, destination cleared
            _start.value = pos
            _dest.value = null
            _routePoints.value = emptyList()
            _isMoving.value = false
        }
    }

    // ----- Route calculation -----
    private fun calculateRoute() {
        val startPos = _start.value ?: return
        val destPos = _dest.value ?: return
        viewModelScope.launch {
            try {
                val ghPoints = routeCalculator.calculateRoute(startPos, destPos)
                val positions = ghPoints.map { Position(it.lat, it.lon, 0f) }
                _routePoints.value = positions
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ----- Simulation -----
    fun startSimulation() {
        val route = _routePoints.value
        if (route.isEmpty() || _isMoving.value) return

        _isMoving.value = true
        viewModelScope.launch {
            for (i in 0 until route.size step 2) {
                val point = route[i]
                val bearing = if (i + 1 < route.size) {
                    calculateBearing(point, route[i + 1])
                } else {
                    if (i > 0) calculateBearing(route[i - 1], point) else 0f
                }

                // Simulate GPS position
                resilienceManager.simulateGpsLocation(point.latitude, point.longitude, bearing)

                delay(300)
            }
            _isMoving.value = false
        }
    }

    private fun calculateBearing(from: Position, to: Position): Float {
        val lat1 = Math.toRadians(from.latitude)
        val lon1 = Math.toRadians(from.longitude)
        val lat2 = Math.toRadians(to.latitude)
        val lon2 = Math.toRadians(to.longitude)
        val dLon = lon2 - lon1
        val y = Math.sin(dLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
        return Math.toDegrees(Math.atan2(y, x)).toFloat()
    }

    // ----- Sensor control -----
    fun startSensors() {
        resilienceManager.startSensors()
    }

    fun stopSensors() {
        resilienceManager.stopSensors()
    }

    override fun onCleared() {
        resilienceManager.stopSensors()
        super.onCleared()
    }
}