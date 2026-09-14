package com.example.road.domain.resilience

import com.example.road.data.m.model.Edge
import com.example.road.data.m.model.Node
import com.example.road.data.m.model.Position
import kotlin.math.*

/**
 * Snaps a raw INS/GPS position onto the nearest road edge.
 *
 * Edges are bucketed into a coarse lat/lon grid so that matching a position
 * only has to scan the handful of edges near it, instead of every edge in
 * the graph (which was previously ~169,000 edges scanned per call, on the
 * main thread, up to 50 times a second — the cause of the freeze).
 */
class MapMatcher(private val edges: List<Edge>, private val nodes: List<Node>) {

    // ~0.005 deg ≈ 500m at this latitude. Tune down if roads are dense and
    // matches feel imprecise; tune up if candidate lists are still huge.
    private val cellSize = 0.005

    private fun cellKey(lat: Double, lon: Double): Long {
        val cx = floor(lat / cellSize).toLong()
        val cy = floor(lon / cellSize).toLong()
        // Pack two 32-bit-range ints into one Long key to avoid boxing a Pair.
        return (cx shl 32) xor (cy and 0xFFFFFFFFL)
    }

    // Precomputed once at construction time — this is the one-time O(n) cost,
    // not something that happens on every match() call.
    private val grid: Map<Long, List<Edge>> = edges.groupBy { e ->
        val midLat = (e.from.lat + e.to.lat) / 2.0
        val midLon = (e.from.lon + e.to.lon) / 2.0
        cellKey(midLat, midLon)
    }

    private fun candidateEdges(lat: Double, lon: Double): List<Edge> {
        val cx = floor(lat / cellSize).toLong()
        val cy = floor(lon / cellSize).toLong()
        val result = ArrayList<Edge>()
        for (dx in -1..1) {
            for (dy in -1..1) {
                val key = ((cx + dx) shl 32) xor ((cy + dy) and 0xFFFFFFFFL)
                grid[key]?.let { result.addAll(it) }
            }
        }
        return result
    }

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
        val candidates = candidateEdges(position.latitude, position.longitude)

        // Fall back to the raw position if the grid cell (and its neighbors)
        // has no edges nearby — e.g. off the edge of the loaded map.
        if (candidates.isEmpty()) return position

        var bestLat = position.latitude
        var bestLon = position.longitude
        var minDist = Double.MAX_VALUE

        for (edge in candidates) {
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