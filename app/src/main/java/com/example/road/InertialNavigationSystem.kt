package com.example.road

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

data class NavPosition(val latitude: Double, val longitude: Double, val heading: Float)

class InertialNavigationSystem {
    private var lastPosition: NavPosition? = null
    private var lastTimestamp: Long = 0

    // Constant for Earth's radius in meters
    private val R = 6371000.0

    /**
     * Updates the position based on speed and heading.
     * This is a simplified dead reckoning calculation.
     */
    fun update(currentPosition: NavPosition, speedMetersPerSec: Float, headingDegrees: Float, timestamp: Long): NavPosition {
        if (lastTimestamp == 0L) {
            lastPosition = currentPosition
            lastTimestamp = timestamp
            return currentPosition
        }

        val deltaTime = (timestamp - lastTimestamp) / 1000.0 // seconds
        val distance = speedMetersPerSec * deltaTime

        val headingRad = headingDegrees * PI / 180.0
        
        // Calculate new latitude and longitude
        val deltaLat = (distance * cos(headingRad)) / R
        val deltaLon = (distance * sin(headingRad)) / (R * cos(currentPosition.latitude * PI / 180.0))

        val newLat = currentPosition.latitude + (deltaLat * 180.0 / PI)
        val newLon = currentPosition.longitude + (deltaLon * 180.0 / PI)

        val updatedPosition = NavPosition(newLat, newLon, headingDegrees)
        lastPosition = updatedPosition
        lastTimestamp = timestamp

        return updatedPosition
    }

    fun reset(position: NavPosition) {
        lastPosition = position
        lastTimestamp = System.currentTimeMillis()
    }
}
