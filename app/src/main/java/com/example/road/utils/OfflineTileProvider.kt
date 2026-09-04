package com.example.road.utils

import android.content.Context
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.modules.MBTilesFileArchive
import java.io.File

object OfflineTileProvider {

    private const val MBTILES_FILE_NAME = "tehran.mbtiles"

    fun create(context: Context): MapTileProviderBasic {
        val mbtilesFile = File(context.filesDir, MBTILES_FILE_NAME)
        if (!mbtilesFile.exists()) {
            copyFromAssetsIfPresent(context, mbtilesFile)
        }
        if (!mbtilesFile.exists()) {
            throw IllegalStateException(
                "Offline map not found. Expected either app/src/main/assets/$MBTILES_FILE_NAME " +
                        "or a file already placed at ${mbtilesFile.absolutePath}."
            )
        }
        val archive = MBTilesFileArchive.getDatabaseFileArchive(mbtilesFile)
        return MapTileProviderBasic(context, TileSourceFactory.MAPNIK, archive)
    }

    private fun copyFromAssetsIfPresent(context: Context, destination: File) {
        try {
            context.assets.open(MBTILES_FILE_NAME).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: java.io.FileNotFoundException) {
            // Not bundled in assets either — caller throws a clear error above.
        }
    }
}