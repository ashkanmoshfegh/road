package com.example.road.domain.routing

import com.example.road.data.model.Position
import com.graphhopper.GraphHopper
import com.graphhopper.util.shapes.GHPoint
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RouteCalculator @Inject constructor(
    private val graphHopper: GraphHopper
) {

    fun calculateRoute(from: Position, to: Position): List<GHPoint> {
        try {
            val req = graphHopper.routeBuilder()
                .addPoint(GHPoint(from.latitude, from.longitude))
                .addPoint(GHPoint(to.latitude, to.longitude))
                .setProfile("car")
                .build()
            val rsp = graphHopper.route(req)
            val path = rsp.best.path
            val points = path.points
            val result = mutableListOf<GHPoint>()
            for (i in 0 until points.size) {
                val pt = points.get(i)
                result.add(GHPoint(pt.lat, pt.lon))
            }
            return result
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }
}