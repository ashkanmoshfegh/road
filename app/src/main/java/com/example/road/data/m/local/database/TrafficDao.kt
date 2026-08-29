package com.example.road.data.m.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrafficDao {
    @Insert
    suspend fun insert(entry: TrafficEntity)

    @Query("SELECT AVG(travelTimeSeconds) FROM traffic_history WHERE roadId = :roadId AND hour = :hour AND dayOfWeek = :dayOfWeek")
    fun getAverageTravelTime(roadId: String, hour: Int, dayOfWeek: Int): Flow<Double?>

    @Query("DELETE FROM traffic_history WHERE timestamp < :cutoff")
    suspend fun deleteOldEntries(cutoff: Long)
}