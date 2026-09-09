package com.example.road.presentation

import android.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.road.utils.MbtilesTileProvider
import com.example.road.utils.OsmXmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

private val TEHRAN_CENTER = GeoPoint(35.6892, 51.3890)
private const val MIN_ZOOM = 10.0
private const val MAX_ZOOM = 18.0   // match tehran.mbtiles render range
private const val INITIAL_ZOOM = 13.0

// The OSM vector file extracted from tehran-extract.osm.pbf (Tehran bbox only)
private const val OSM_FILE_NAME = "tehran-map.osm"


private fun styleForHighway(highway: String): Pair<String, Float> = when (highway) {
    "motorway", "motorway_link" -> "#E8622C" to 7f
    "trunk", "trunk_link" -> "#EA8B4B" to 6f
    "primary", "primary_link" -> "#F2B950" to 5f
    "secondary", "secondary_link" -> "#F7DC6F" to 4.5f
    "tertiary", "tertiary_link" -> "#FFFFFF" to 4f
    "residential", "living_street", "unclassified" -> "#D8D8D8" to 3f
    "service", "track" -> "#BFBFBF" to 2f
    "footway", "path", "cycleway", "steps", "pedestrian" -> "#9E9E9E" to 1.5f
    else -> "#C9C9C9" to 2f
}

@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    onMapTap: (lat: Double, lon: Double) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        val tileProvider = MbtilesTileProvider.create(context)
        MapView(context, tileProvider).apply {
            setUseDataConnection(false)
            setMultiTouchControls(true)

            minZoomLevel = MIN_ZOOM
            maxZoomLevel = MAX_ZOOM
            controller.setZoom(INITIAL_ZOOM)
            controller.setCenter(TEHRAN_CENTER)

            val tapReceiver = object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    onMapTap(p.latitude, p.longitude)
                    return true
                }
                override fun longPressHelper(p: GeoPoint): Boolean = false
            }
            overlays.add(MapEventsOverlay(tapReceiver))
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { mapView }
    )

    // Parse the OSM vector file and draw it as overlays on top of the MBTiles basemap.
    // Water polygons first (bottom layer), then buildings, then roads on top.
    LaunchedEffect(mapView) {
        withContext(Dispatchers.IO) {
            try {
                val osmData = OsmXmlParser.parseFromAssets(context, OSM_FILE_NAME)

                withContext(Dispatchers.Main) {
                    // Water polygons (bottom layer)
                    osmData.water.forEach { area ->
                        val polygon = Polygon(mapView).apply {
                            setPoints(area.points)
                            setFillColor(Color.parseColor("#AAD3DF"))
                            setStrokeColor(Color.parseColor("#AAD3DF"))
                            setStrokeWidth(1f)
                        }
                        mapView.overlays.add(polygon)
                    }
                    // Building footprints
                    osmData.buildings.forEach { area ->
                        val polygon = Polygon(mapView).apply {
                            setPoints(area.points)
                            setFillColor(Color.parseColor("#D9CFC4"))
                            setStrokeColor(Color.parseColor("#D9CFC4"))
                            setStrokeWidth(1f)
                        }
                        mapView.overlays.add(polygon)
                    }
                    // Roads on top (colored by highway type)
                    osmData.roads.forEach { road ->
                        val (colorHex, width) = styleForHighway(road.highway)
                        val line = Polyline(mapView).apply {
                            outlinePaint.color = Color.parseColor(colorHex)
                            outlinePaint.strokeWidth = width
                            setPoints(road.points)
                        }
                        mapView.overlays.add(line)
                    }
                    mapView.invalidate()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
