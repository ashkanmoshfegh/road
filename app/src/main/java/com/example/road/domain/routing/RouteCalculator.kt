package com.example.road.domain.routing

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

    fun calculateRoute(from: Position, to: Position): List<GHPoint> {
        val graphHopper = graphRepository.getGraph()
            ?: return emptyList() // Graph not loaded yet

        return try {
            val request = GHRequest()
                .addPoint(GHPoint(from.latitude, from.longitude))
                .addPoint(GHPoint(to.latitude, to.longitude))
                .setProfile("car")
            val response = graphHopper.route(request)
            val path = response.best
            val points = path.points
            val result = mutableListOf<GHPoint>()
            for (i in 0 until points.size()) {
                val pt = points.get(i)
                result.add(GHPoint(pt.lat, pt.lon))
            }
            result
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}