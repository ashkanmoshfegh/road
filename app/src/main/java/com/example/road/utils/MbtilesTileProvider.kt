package com.example.road.utils

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.Log
import org.osmdroid.tileprovider.ExpirableBitmapDrawable
import org.osmdroid.tileprovider.IRegisterReceiver
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import org.osmdroid.api.IMapView
import kotlin.math.pow

object MbtilesTileProvider {
    const val MIN_ZOOM = 10
    const val MAX_ZOOM = 17
    private const val MBTILES_ASSET = "tehran.mbtiles"
    private const val MBTILES_INTERNAL_NAME = "map.mbtiles"

    fun create(context: Context): MapTileProviderArray {
        val tileSource = XYTileSource(
            "TehranOffline",
            MIN_ZOOM,
            MAX_ZOOM,
            256,
            "",
            emptyArray()
        )
        val registerReceiver = NoOpRegisterReceiver(context)
        val provider = MbtilesModuleProvider(context)
        return MapTileProviderArray(tileSource, registerReceiver, arrayOf(provider))
    }

    fun copyMbtilesToInternal(context: Context): java.io.File? {
        val dest = java.io.File(context.filesDir, MBTILES_INTERNAL_NAME)
        if (dest.exists()) return dest
        return try {
            context.assets.open(MBTILES_ASSET).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

class NoOpRegisterReceiver(private val context: Context) : IRegisterReceiver {
    override fun registerReceiver(receiver: android.content.BroadcastReceiver, filter: android.content.IntentFilter): android.content.Intent? = null
    override fun unregisterReceiver(receiver: android.content.BroadcastReceiver) {}
    override fun destroy() {}
}

class MbtilesModuleProvider(private val context: Context) : MapTileModuleProviderBase(1, 10) {
    private var db: SQLiteDatabase? = null
    private var dbFile: java.io.File? = null

    override fun getName(): String = "MbtilesModuleProvider"
    override fun getThreadGroupName(): String = "MbtilesModuleProvider"
    override fun getUsesDataConnection(): Boolean = false
    override fun getMinimumZoomLevel(): Int = MbtilesTileProvider.MIN_ZOOM
    override fun getMaximumZoomLevel(): Int = MbtilesTileProvider.MAX_ZOOM
    override fun setTileSource(tileSource: ITileSource) {}

    override fun getTileLoader(): MapTileModuleProviderBase.TileLoader {
        return object : MapTileModuleProviderBase.TileLoader() {
            override fun loadTile(pMapTileIndex: Long): Drawable? {
                val tileX = MapTileIndex.getX(pMapTileIndex)
                val tileY = MapTileIndex.getY(pMapTileIndex)  // Web Mercator Y from osmdroid
                val tileZoom = MapTileIndex.getZoom(pMapTileIndex)

                if (tileZoom < MbtilesTileProvider.MIN_ZOOM || tileZoom > MbtilesTileProvider.MAX_ZOOM) {
                    return null
                }

                val database = db ?: return null
                if (!database.isOpen) return null

                // Convert Web Mercator Y to TMS Y for MBTiles lookup.
                // MBTiles stores tiles with TMS Y (Y inverted): tmsY = (2^z - 1) - webY
                val maxY = 2.0.pow(tileZoom.toDouble()).toInt() - 1
                val tmsY = maxY - tileY

                var cursor: Cursor? = null
                try {
                    cursor = database.query(
                        "tiles",
                        arrayOf("tile_data"),
                        "zoom_level = ? AND tile_column = ? AND tile_row = ?",
                        arrayOf(tileZoom.toString(), tileX.toString(), tmsY.toString()),
                        null, null, null
                    )
                    if (cursor.moveToFirst()) {
                        val blob = cursor.getBlob(0)
                        if (blob != null && blob.size > 0) {
                            val stream = java.io.ByteArrayInputStream(blob)
                            val bitmap = BitmapFactory.decodeStream(stream)
                            if (bitmap != null) {
                                val drawable = ExpirableBitmapDrawable(bitmap)
                                drawable.setState(intArrayOf(ExpirableBitmapDrawable.UP_TO_DATE))
                                return drawable
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(IMapView.LOGTAG, "MbtilesModuleProvider.loadTile error", e)
                } finally {
                    cursor?.close()
                }
                return null
            }

            override fun onTileLoaderInit() {
                if (db != null && db?.isOpen == true) return
                val file = MbtilesTileProvider.copyMbtilesToInternal(context)
                    ?: return
                dbFile = file
                db = SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY
                )
                Log.d(IMapView.LOGTAG, "MbtilesModuleProvider: DB opened (${file.absolutePath})")
            }

            override fun onTileLoaderShutdown() {
                db?.close()
                db = null
                dbFile = null
            }
        }
    }
}