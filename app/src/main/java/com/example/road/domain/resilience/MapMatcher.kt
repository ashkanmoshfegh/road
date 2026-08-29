package com.example.road.domain.resilience

import com.example.road.data.m.model.Edge
import com.example.road.data.m.model.Node
import com.example.road.data.m.model.Position
import kotlin.math.*

class MapMatcher(private val edges: List<Edge>, private val nodes: List<Node>) {

    // Project a point onto a line segment (A-B) and return the closest point and distance.
    private fun projectOnSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Pair<Double, Double> {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy

        if (lenSq == 0.0) return Pair(ax, ay)

        var t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        t = t.coerceIn(0.0, 1.0)

        val projX = ax + t * dx
        val projY = ay + t * dy
        return Pair(projX, projY)
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    fun match(position: Position): Position {
        var bestLat = position.latitude
        var bestLon = position.longitude
        var minDist = Double.MAX_VALUE

        for (edge in edges) {
            val from = edge.from
            val to = edge.to

            val (projLat, projLon) = projectOnSegment(
                position.latitude, position.longitude,
                from.lat, from.lon,
                to.lat, to.lon
            )

            val dist = haversine(projLat, projLon, position.latitude, position.longitude)
            if (dist < minDist) {
                minDist = dist
                bestLat = projLat
                bestLon = projLon
            }
        }

        return Position(bestLat, bestLon, position.bearing, position.accuracy, position.timestamp)
    }

    fun match(points: List<Position>): List<Position> {
        return points.map { match(it) }
    }
}