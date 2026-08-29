package com.example.road.data.local.repository

import android.content.Context
import com.graphhopper.GraphHopper
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.graphhopper.routing.util.EncodingManager
import com.graphhopper.util.CmdArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GraphRepository @Inject constructor(private val context: Context) {

    private var graphHopper: GraphHopper? = null

    suspend fun loadGraph(): GraphHopper = withContext(Dispatchers.IO) {
        graphHopper?.let { return@withContext it }

        val gh = GraphHopper()
        val osmFile = File(context.filesDir, "tehran.osm.pbf")
        val graphCacheDir = File(context.filesDir, "graph-cache")

        // Ensure the OSM file exists. In production, you'd download it or copy from assets.
        if (!osmFile.exists()) {
            // Fallback: copy from assets if you bundle it.
            // If not, throw an exception or download.
            throw IllegalStateException("OSM file not found. Place it in internal storage or assets.")
        }

        gh.setOSMFile(osmFile.absolutePath)
        gh.setGraphHopperLocation(graphCacheDir.absolutePath)
        gh.setEncodingManager(EncodingManager.create("car"))
        gh.setProfiles(listOf(Profile("car").setVehicle("car").setWeighting("fastest")))
        gh.getCHPreparationHandler().setCHProfiles(listOf(CHProfile("car")))
        gh.importOrLoad()

        graphHopper = gh
        return@withContext gh
    }

    fun getGraph(): GraphHopper? = graphHopper
}