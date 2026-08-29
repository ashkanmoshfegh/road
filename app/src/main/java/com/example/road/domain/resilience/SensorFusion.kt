package com.example.road.domain.resilience

import kotlin.math.*

class SensorFusion {
    private var timestamp = 0L
    private var heading = 0.0 // radians
    private var position = Pair(0.0, 0.0) // relative displacement (meters)

    private val stepDetector = StepDetector()

    fun update(gyroZ: Float, accelMagnitude: Float, currentTime: Long): Pair<Double, Double>? {
        if (timestamp == 0L) {
            timestamp = currentTime
            return null
        }

        val dt = (currentTime - timestamp) / 1000.0
        timestamp = currentTime

        // 1. Heading from gyro integration (Z-axis rotation)
        val headingDelta = gyroZ.toDouble() * dt
        heading += headingDelta

        // Keep heading within [-PI, PI]
        if (heading > PI) heading -= 2 * PI
        if (heading < -PI) heading += 2 * PI

        // 2. Step detection and displacement
        val stepLength = stepDetector.detectStep(accelMagnitude)
        val dx = stepLength * cos(heading)
        val dy = stepLength * sin(heading)

        position = Pair(position.first + dx, position.second + dy)

        return position
    }

    fun reset(initialHeading: Float = 0f) {
        heading = initialHeading.toDouble()
        position = Pair(0.0, 0.0)
        timestamp = 0L
    }

    fun getHeadingDegrees(): Float = Math.toDegrees(heading).toFloat()
}