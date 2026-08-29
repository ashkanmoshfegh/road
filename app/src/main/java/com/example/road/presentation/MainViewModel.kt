package com.example.road.presentation

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.road.data.model.Position
import com.example.road.domain.resilience.ResilienceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val resilienceManager: ResilienceManager
) : ViewModel() {

    val currentPosition: StateFlow<Position?> = resilienceManager.currentPosition
    val currentSource: StateFlow<String> = resilienceManager.currentSource

    init {
        viewModelScope.launch {
            resilienceManager.initialize()
            resilienceManager.startSensors()
        }
    }

    override fun onCleared() {
        resilienceManager.stopSensors()
        super.onCleared()
    }

    fun onGpsLocation(location: Location) {
        resilienceManager.onGpsLocation(location)
    }

    fun getRoute(destination: Position): List<Position> {
        return resilienceManager.getRoute(destination)
    }
}