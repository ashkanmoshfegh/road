package com.example.road.utils

import android.content.Context
import org.osmdroid.tileprovider.IRegisterReceiver
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * A tile provider with no backing tile source at all.
 *
 * osmdroid's MapView still needs *some* ITileSource/provider to exist for its
 * pan/zoom/projection math to work, but we no longer render a raster basemap -
 * roads, buildings, and water are drawn as vector overlays straight from the
 * .osm file (see [OsmXmlParser] and MapScreen). The empty provider array means
 * every tile request simply resolves to "not found" and the MapView shows its
 * plain background color underneath the overlays.
 *
 * Because there is no pre-rendered tile set to run out of, zoom is only capped
 * by osmdroid's own hard ceiling (22), not by whatever an mbtiles archive
 * happened to be rendered at.
 */
object BlankTileProvider {

    private const val MIN_ZOOM = 0
    private const val MAX_ZOOM = 21 // 1 below osmdroid's hard ceiling of 22, for safety margin

    fun create(context: Context): MapTileProviderArray {
        val tileSource = XYTileSource("Blank", MIN_ZOOM, MAX_ZOOM, 256, "", emptyArray())
        val registerReceiver: IRegisterReceiver = CustomRegisterReceiver(context)
        val noProviders = emptyArray<MapTileModuleProviderBase>()
        return MapTileProviderArray(tileSource, registerReceiver, noProviders)
    }
}