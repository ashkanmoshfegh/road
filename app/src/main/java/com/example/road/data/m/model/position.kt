package com.example.road.data.m.model

data class Position(
    val latitude: Double,
    val longitude: Double,
    val bearing: Float = 0f,
    val accuracy: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)