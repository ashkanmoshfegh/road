package com.example.road.utils

import android.content.Context
import org.osmdroid.tileprovider.IRegisterReceiver
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider
import org.osmdroid.tileprovider.modules.MBTilesFileArchive
import org.osmdroid.tileprovider.tilesource.XYTileSource
import java.io.File

object OfflineTileProvider {

    private const val MBTILES_FILE_NAME = "tehran.mbtiles"

    fun create(context: Context): MapTileProviderArray {
        val mbtilesFile = File(context.filesDir, MBTILES_FILE_NAME)
        if (!mbtilesFile.exists()) {
            copyFromAssetsIfPresent(context, mbtilesFile)
        }
        
        if (!mbtilesFile.exists()) {
            throw IllegalStateException("Offline map file not found at ${mbtilesFile.absolutePath}")
        }

        // Use a generic XYTileSource. The name "Tehran" or "openmaptiles" is common, 
        // but osmdroid's MBTilesFileArchive often maps to the internal metadata name.
        val tileSource = XYTileSource(
            "Tehran", 
            10, 17, 256, ".png", 
            emptyArray()
        )

        val archive = MBTilesFileArchive.getDatabaseFileArchive(mbtilesFile)
        val registerReceiver: IRegisterReceiver = CustomRegisterReceiver(context)

        val archiveProvider = MapTileFileArchiveProvider(
            registerReceiver,
            tileSource,
            arrayOf(archive)
        )

        return MapTileProviderArray(tileSource, registerReceiver, arrayOf(archiveProvider))
    }

    private fun copyFromAssetsIfPresent(context: Context, destination: File) {
        try {
            context.assets.open(MBTILES_FILE_NAME).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
