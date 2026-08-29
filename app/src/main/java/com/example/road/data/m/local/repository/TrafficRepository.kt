package com.example.road.data.m.local.repository

import com.example.road.data.m.local.database.TrafficDao
import com.example.road.data.m.local.database.TrafficEntity
import kotlinx.coroutines.flow.first
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrafficRepository @Inject constructor(
    private val trafficDao: TrafficDao
) {

    suspend fun recordTrip(roadId: String, travelTimeSeconds: Int) {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val day = calendar.get(Calendar.DAY_OF_WEEK)
        val entity = TrafficEntity(
            roadId = roadId,
            travelTimeSeconds = travelTimeSeconds,
            hour = hour,
            dayOfWeek = day
        )
        trafficDao.insert(entity)
    }

    suspend fun getAverageTravelTime(roadId: String, hour: Int, dayOfWeek: Int): Double? {
        return trafficDao.getAverageTravelTime(roadId, hour, dayOfWeek).first()
    }

    suspend fun cleanOldData() {
        val cutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000) // 30 days
        trafficDao.deleteOldEntries(cutoff)
    }
}