package com.example.road.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.io.ByteArrayInputStream
import java.io.File

/***
 * Tile provider backed by an MBTiles file (SQLite) copied from assets.
 *
 * On first use tehran.mbtiles is unpacked from assets into internal storage
 * (assets are read-only and can't be opened as SQLite directly).
 */
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

    /***
     * Copy tehran.mbtiles from assets into internal storage if not already there.
     * Returns the File, or null if the asset isn't bundled.
     */
    fun copyMbtilesToInternal(context: Context): File? {
        val dest = File(context.filesDir, MBTILES_INTERNAL_NAME)
        if (dest.exists()) return dest

        return try {
            context.assets.open(MBTILES_ASSET).use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            dest
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

/***
 * No-op register receiver.
 */
class NoOpRegisterReceiver(private val context: Context) : IRegisterReceiver {
    override fun registerReceiver(
        receiver: android.content.BroadcastReceiver,
        filter: IntentFilter
    ): Intent? = null

    override fun unregisterReceiver(receiver: android.content.BroadcastReceiver) {}
    override fun destroy() {}
}

/***
 * Single provider module that reads tiles from an MBTiles SQLite database.
 *
 * osmdroid v6.1.18 API:
 *  - MapTileModuleProviderBase(int poolSize, int queueSize)
 *  - Required: getName(), getThreadGroupName(), getTileLoader(),
 *    getUsesDataConnection(), getMinimumZoomLevel(),
 *    getMaximumZoomLevel(), setTileSource(ITileSource)
 *
 * The key fix from the previous version: loadTile() must return an
 * [ExpirableBitmapDrawable] (not a raw BitmapDrawable) so that
 * TilesOverlay.handleTile() recognises it as a valid tile and draws it.
 * A plain BitmapDrawable fails the `isReusable` check and gets replaced
 * with the loading tile (grey grid), making the map appear blank.
 */
class MbtilesModuleProvider(
    private val context: Context
) : MapTileModuleProviderBase(1, 10) {

    private var db: SQLiteDatabase? = null
    private var dbFile: File? = null

    override fun getName(): String = "MbtilesModuleProvider"

    override fun getThreadGroupName(): String = "MbtilesModuleProvider"

    override fun getUsesDataConnection(): Boolean = false

    override fun getMinimumZoomLevel(): Int = MbtilesTileProvider.MIN_ZOOM

    override fun getMaximumZoomLevel(): Int = MbtilesTileProvider.MAX_ZOOM

    override fun setTileSource(tileSource: ITileSource) {
        // MBTiles provider is self-contained — no tile source URL needed.
    }

    override fun getTileLoader(): MapTileModuleProviderBase.TileLoader {
        return object : MapTileModuleProviderBase.TileLoader() {
            override fun loadTile(pMapTileIndex: Long): Drawable? {
                val tileX = MapTileIndex.getX(pMapTileIndex)
                val tileY = MapTileIndex.getY(pMapTileIndex)
                val tileZoom = MapTileIndex.getZoom(pMapTileIndex)

                if (tileZoom < MbtilesTileProvider.MIN_ZOOM || tileZoom > MbtilesTileProvider.MAX_ZOOM) {
                    return null
                }

                val database = db ?: return null
                if (database.isOpen.not()) return null

                // MBTiles uses TMS numbering (Y flipped).
                val tmsY = (1 shl tileZoom) - 1 - tileY

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
                            val stream = ByteArrayInputStream(blob)
                            val bitmap = BitmapFactory.decodeStream(stream)
                            if (bitmap != null) {
                                // Return ExpirableBitmapDrawable so TilesOverlay
                                // recognises it as a valid cached tile.
                                return ExpirableBitmapDrawable(bitmap).apply {
                                    setState(intArrayOf(ExpirableBitmapDrawable.UP_TO_DATE))
                                }
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
