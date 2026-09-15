package com.example.road.data.m.local.repository

import android.content.Context
import android.util.Log
import com.graphhopper.GraphHopper
import com.graphhopper.routing.util.EdgeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GraphRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val OSM_FILE_NAME = "iran-260828.osm.pbf"
    }

    private val logTag = "GraphRepository"
    private var graphHopper: GraphHopper? = null

    suspend fun loadGraph(): GraphHopper? {
        Log.d(logTag, "loadGraph() called")
        graphHopper?.let { return it }

        val osmFile = File(context.filesDir, OSM_FILE_NAME)

        Log.d(logTag, "OSM file: ${osmFile.absolutePath}, exists=${osmFile.exists()}")
        if (osmFile.exists()) {
            Log.d(logTag, "OSM size: ${osmFile.length()} bytes")
        } else {
            Log.d(logTag, "Copying from assets...")
            try {
                context.assets.open(OSM_FILE_NAME).use { input ->
                    osmFile.outputStream().use { output -> input.copyTo(output) }
                }
                Log.d(logTag, "Copy done, exists=${osmFile.exists()}, size=${osmFile.length()}")
            } catch (e: Exception) {
                Log.e(logTag, "Failed to copy from assets", e)
                return null
            }
        }

        if (!osmFile.exists()) {
            Log.e(logTag, "OSM file missing after copy — cannot load")
            return null
        }

        val cacheDir = File(context.filesDir, "gh-cache")
        val startTime = System.currentTimeMillis()

        return try {
            val gh = GraphHopper()
            gh.setOSMFile(osmFile.absolutePath)
            gh.setGraphHopperLocation(cacheDir.absolutePath)
            gh.setProfiles(listOf(
                com.graphhopper.config.Profile("car")
                    .setVehicle("car")
                    .setWeighting("fastest")
            ))
            gh.getCHPreparationHandler()
                .setCHProfiles(listOf(com.graphhopper.config.CHProfile("car")))
            Log.d(logTag, "Calling importOrLoad()...")
            gh.importOrLoad()
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(logTag, "importOrLoad() OK in ${elapsed}ms")
            graphHopper = gh
            gh
        } catch (e: OutOfMemoryError) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(logTag, "GraphHopper OOM after ${elapsed}ms", e)
            null
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(logTag, "GraphHopper error after ${elapsed}ms: ${e.message}", e)
            null
        }
    }

    fun getGraph(): GraphHopper? = graphHopper

    /**
     * Find the nearest graph node/edge to the given lat/lon and return the
     * actual road-snapped position.
     *
     * Uses LocationIndex.findClosest which is O(log n) via quadtree.
     * Returns a NodeInfo carrying the SNAPPED coordinates — the point on the
     * road network — NOT the raw tap point. Returning the raw tap is what
     * used to make start/destination look off-road. GHPoint3D's lat/lon come
     * from getSnappedPoint() only after calcSnappedPoint() runs; findClosest
     * does that internally, so snappedPoint is populated here.
     */
    fun findNearest(graph: GraphHopper, lat: Double, lon: Double): NodeInfo? {
        val locIndex = graph.locationIndex
        if (locIndex == null) {
            Log.w(logTag, "LocationIndex is null — graph may not be fully loaded")
            return null
        }

        return try {
            // GraphHopper's findClosest unconditionally calls edgeFilter.accept(...)
            // internally — passing null there throws an NPE. ALL_EDGES accepts
            // every edge, which is the correct default when not restricting by
            // vehicle/edge type.
            val snap = locIndex.findClosest(lat, lon, EdgeFilter.ALL_EDGES)
            if (!snap.isValid) {
                Log.w(logTag, "No valid snap found near ($lat, $lon)")
                return null
            }
            // snappedPoint is the closest point lying ON the road network —
            // exactly what we want to pin the marker and route origin to.
            val sp = snap.snappedPoint
            val snappedLat = if (sp != null) sp.lat else lat
            val snappedLon = if (sp != null) sp.lon else lon
            Log.d(logTag, "Snapped ($lat, $lon) -> node=${snap.closestNode} at ($snappedLat, $snappedLon)")
            NodeInfo(snap.closestNode.toLong(), snappedLat, snappedLon)
        } catch (e: Exception) {
            Log.e(logTag, "findNearest failed", e)
            null
        }
    }
}

data class NodeInfo(val id: Long, val lat: Double, val lon: Double)