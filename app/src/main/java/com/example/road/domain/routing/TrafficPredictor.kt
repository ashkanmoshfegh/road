package com.example.road.domain.routing

import com.example.road.data.m.local.repository.TrafficRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrafficPredictor @Inject constructor(
    private val trafficRepository: TrafficRepository
) {

    suspend fun predictTravelTime(roadId: String, hour: Int, dayOfWeek: Int): Double? {
        return trafficRepository.getAverageTravelTime(roadId, hour, dayOfWeek)
    }

    suspend fun recordTrip(roadId: String, travelTimeSeconds: Int) {
        trafficRepository.recordTrip(roadId, travelTimeSeconds)
    }
}