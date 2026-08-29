package com.example.road

import java.util.Calendar

/**
 * Simplified Traffic Predictor based on local history as described in the proposal.
 * Uses day of week and hour to estimate congestion levels.
 */
class TrafficPredictor {
    
    enum class TrafficLevel(val weightMultiplier: Double) {
        LOW(1.0),
        MEDIUM(1.5),
        HIGH(2.5),
        JAM(5.0)
    }

    /**
     * Estimates traffic for a given road ID based on the current time.
     */
    fun getTrafficMultiplier(roadId: String): Double {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)

        // Peak hours: 7-9 AM and 5-7 PM
        val isPeakHour = (hour in 7..9) || (hour in 17..19)
        val isWeekend = dayOfWeek == Calendar.FRIDAY || dayOfWeek == Calendar.THURSDAY

        return when {
            isPeakHour && !isWeekend -> TrafficLevel.HIGH.weightMultiplier
            isPeakHour && isWeekend -> TrafficLevel.MEDIUM.weightMultiplier
            !isPeakHour && isWeekend -> TrafficLevel.LOW.weightMultiplier
            else -> TrafficLevel.MEDIUM.weightMultiplier
        }
    }
}
