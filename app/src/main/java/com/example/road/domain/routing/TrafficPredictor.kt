package com.example.road.domain.routing

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrafficPredictor @Inject constructor() {
    fun predictTravelTime(roadId: String, hour: Int, dayOfWeek: Int): Double? = null
    fun recordTrip(roadId: String, travelTimeSeconds: Int) {}
}