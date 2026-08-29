package com.example.road.data.m.model

data class Route(
    val nodes: List<Node>,
    val totalDistanceMeters: Double,
    val estimatedTimeSeconds: Int
)