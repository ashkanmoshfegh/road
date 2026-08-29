package com.example.road.domain.resilience

import kotlin.math.sqrt

class StepDetector {
    private val threshold = 1.2f // m/s²
    private var lastMagnitude = 0f
    private var stepCount = 0

    fun detectStep(accelMagnitude: Float): Float {
        val diff = accelMagnitude - lastMagnitude
        lastMagnitude = accelMagnitude

        // Simple peak detection: when acceleration spikes above gravity and fluctuates.
        if (diff > threshold && accelMagnitude > 9.0f) {
            stepCount++
            return 0.75f // average step length in meters (user-specific calibration can be added)
        }
        return 0f
    }
}