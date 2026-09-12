package com.example.road.domain.routing

import android.util.Log
import com.example.road.data.m.local.repository.GraphRepository
import com.example.road.data.m.model.Position
import com.graphhopper.GHRequest
import com.graphhopper.util.shapes.GHPoint
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RouteCalculator @Inject constructor(
    private val graphRepository: GraphRepository
) {
    private val logTag = "RouteCalculator"

    fun calculateRoute(from: Position, to: Position): List<GHPoint> {
        Log.d(logTag, "calculateRoute called: (" + from + ") -> (" + to + ")")
        val graphHopper = graphRepository.getGraph()
        if (graphHopper == null) {
            Log.w(logTag, "GraphHopper not loaded yet — returning empty")
            return emptyList()
        }
        Log.d(logTag, "GraphHopper available")

        return try {
            val request = GHRequest()
                .addPoint(GHPoint(from.latitude, from.longitude))
                .addPoint(GHPoint(to.latitude, to.longitude))
                .setProfile("car")
            Log.d(logTag, "Submitting GHRequest...")
            val response = graphHopper.route(request)
            val path = response.best
            if (path == null) {
                return emptyList()
            }
            val points = path.points
            Log.d(logTag, "Route found: " + points.size() + " points, distance: " + path.distance)
            val result = mutableListOf<GHPoint>()
            for (i in 0 until points.size()) {
                val pt = points.get(i)
                result.add(GHPoint(pt.lat, pt.lon))
            }
            result
        } catch (e: Exception) {
            Log.e(logTag, "Route calculation failed", e)
            emptyList()
        }
    }
}
