package com.example.road.data.local.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "traffic_history")
data class TrafficEntity(
    @PrimaryKey(autoGenerate = true) val uid: Long = 0,
    val roadId: String,
    val travelTimeSeconds: Int,
    val hour: Int,
    val dayOfWeek: Int,
    val timestamp: Long = System.currentTimeMillis()
)