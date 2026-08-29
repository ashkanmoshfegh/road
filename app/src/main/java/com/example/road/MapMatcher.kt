package com.example.road

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

class MapMatcher(private val routingEngine: RoutingEngine) {

    /**
     * Snaps a raw coordinate to the nearest node in the graph.
     * This fulfills the "Map Matching" requirement to increase accuracy 
     * when GPS is unavailable.
     */
    fun matchToNearestNode(lat: Double, lon: Double, nodes: List<Node>): Node? {
        if (nodes.isEmpty()) return null
        
        var closestNode: Node? = null
        var minDistance = Double.MAX_VALUE

        for (node in nodes) {
            val dist = calculateDistance(lat, lon, node.lat, node.lon)
            if (dist < minDistance) {
                minDistance = dist
                closestNode = node
            }
        }
        return closestNode
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        
        val lat1Rad = lat1 * PI / 180.0
        val lat2Rad = lat2 * PI / 180.0

        val a = sin(dLat / 2).pow(2.0) +
                cos(lat1Rad) * cos(lat2Rad) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
