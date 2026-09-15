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
     * Find the nearest road to the given lat/lon and return the actual
     * point ON THE ROAD closest to it — not the tapped coordinates
     * themselves, and not just the nearest graph node (which can sit far
     * along the street from where the user tapped).
     *
     * Previously this returned NodeInfo(snap.closestNode, lat, lon) —
     * note lat/lon there were the ORIGINAL query coordinates, echoed back
     * unchanged. That meant "snapping" never actually happened visually:
     * the start/destination marker always sat exactly where you tapped,
     * even if that was in the middle of a building block. Snap.snappedPoint
     * is GraphHopper's own computed closest point on the matched edge's
     * geometry, which is what should be shown/used instead.
     */
    fun findNearest(graph: GraphHopper, lat: Double, lon: Double): NodeInfo? {
        val locIndex = graph.locationIndex
        if (locIndex == null) {
            Log.w(logTag, "LocationIndex is null — graph may not be fully loaded")
            return null
        }

        return try {
            val snap = locIndex.findClosest(lat, lon, EdgeFilter.ALL_EDGES)
            if (!snap.isValid) {
                Log.w(logTag, "No valid snap found near ($lat, $lon)")
                return null
            }
            val snapped = snap.snappedPoint // GHPoint3D — the true nearest point on the road
            NodeInfo(snap.closestNode.toLong(), snapped.lat, snapped.lon)
        } catch (e: Exception) {
            Log.e(logTag, "findNearest failed", e)
            null
        }
    }
}

data class NodeInfo(val id: Long, val lat: Double, val lon: Double)