package com.example.road.utils

import CustomRegisterReceiver
import android.content.Context
import org.osmdroid.tileprovider.IRegisterReceiver
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider
import org.osmdroid.tileprovider.modules.MBTilesFileArchive
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import java.io.File


object OfflineTileProvider {

    private const val MBTILES_FILE_NAME = "tehran.mbtiles"

    fun create(context: Context): MapTileProviderArray {
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

        val tileSource = TileSourceFactory.MAPNIK
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
        } catch (e: java.io.FileNotFoundException) {
            // Not bundled in assets either — caller throws a clear error above.
        }
    }
}