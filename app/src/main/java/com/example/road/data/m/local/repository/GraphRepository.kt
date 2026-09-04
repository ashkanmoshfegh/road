package com.example.road.data.m.local.repository

import android.content.Context
import com.graphhopper.GraphHopper
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GraphRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var graphHopper: GraphHopper? = null

    suspend fun loadGraph(): GraphHopper = withContext(Dispatchers.IO) {
        graphHopper?.let { return@withContext it }

        val osmFile = File(context.filesDir, OSM_FILE_NAME)
        val graphCacheDir = File(context.filesDir, "graph-cache")

        if (!osmFile.exists()) {
            copyFromAssetsIfPresent(osmFile)
        }

        if (!osmFile.exists()) {
            throw IllegalStateException(
                "OSM file not found. Expected either app/src/main/assets/$OSM_FILE_NAME " +
                        "(copied automatically on first run) or a file already placed at " +
                        "${osmFile.absolutePath}."
            )
        }

        val gh = GraphHopper()
        gh.setOSMFile(osmFile.absolutePath)
        gh.setGraphHopperLocation(graphCacheDir.absolutePath)
        gh.setProfiles(listOf(Profile("car").setVehicle("car").setWeighting("fastest")))
        gh.getCHPreparationHandler().setCHProfiles(listOf(CHProfile("car")))
        gh.importOrLoad()

        graphHopper = gh
        return@withContext gh
    }

    fun getGraph(): GraphHopper? = graphHopper

    private fun copyFromAssetsIfPresent(destination: File) {
        try {
            context.assets.open(OSM_FILE_NAME).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: java.io.FileNotFoundException) {
            // Not bundled in assets either — caller throws a clear error above.
        }
    }

    companion object {
        private const val OSM_FILE_NAME = "iran-260828.osm.pbf"
    }
}