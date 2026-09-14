package com.example.road.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.road.data.m.model.Position
import com.example.road.utils.BlankTileProvider
import com.example.road.utils.OsmXmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.*
import org.osmdroid.views.overlay.compass.CompassOverlay

private val TEHRAN_CENTER = GeoPoint(35.6892, 51.3890)
private const val MIN_ZOOM = 8.0
private const val MAX_ZOOM = 21.0
private const val INITIAL_ZOOM = 13.0

private const val OSM_FILE_NAME = "tehran-map.osm.stripped"

private val START_MARKER_COLOR   = Color.parseColor("#2E7D32")
private val DEST_MARKER_COLOR    = Color.parseColor("#C62828")
private val ROUTE_LINE_COLOR     = Color.parseColor("#FFD600")
private val CURRENT_POS_COLOR    = Color.parseColor("#1565C0")

private val MAP_BG_COLOR       = Color.parseColor("#F2EFE9")
private val WATER_COLOR        = Color.parseColor("#AAD3DF")
private val BUILDING_COLOR     = Color.parseColor("#D9CFC4")

private val ROAD_HIGHWAYS = setOf(
    "motorway", "motorway_link",
    "trunk", "trunk_link",
    "primary", "primary_link",
    "secondary", "secondary_link",
    "tertiary", "tertiary_link",
    "residential", "living_street", "unclassified",
    "service", "track", "road"
)

private fun styleForHighway(highway: String): Pair<Int, Float> = when (highway) {
    "motorway", "motorway_link" -> Color.parseColor("#E8622C") to 7f
    "trunk", "trunk_link"       -> Color.parseColor("#EA8B4B") to 6f
    "primary", "primary_link"   -> Color.parseColor("#F2B950") to 5f
    "secondary", "secondary_link"-> Color.parseColor("#F7DC6F") to 4f
    "tertiary", "tertiary_link" -> Color.parseColor("#FFFFFF") to 3.5f
    "residential", "living_street", "unclassified" -> Color.parseColor("#D8D8D8") to 3f
    "service", "track"          -> Color.parseColor("#BFBFBF") to 2f
    "footway", "path", "cycleway", "steps", "pedestrian" -> Color.parseColor("#9E9E9E") to 1.5f
    else                        -> Color.parseColor("#C9C9C9") to 2f
}

/**
 * Draws the user's current sensor-estimated position as a dot + heading
 * arrow. Deliberately NOT based on osmdroid's MyLocationNewOverlay /
 * GpsMyLocationProvider — that class pulls real device GPS internally,
 * which was overwriting/fighting with the sensor-based position and made
 * the marker appear in the wrong place. This overlay only ever draws
 * whatever Position is fed to it via update().
 */
class CurrentPositionOverlay(private val mapView: MapView) : Overlay() {
    private var position: GeoPoint? = null
    private var bearingDeg: Float = 0f

    private val haloPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val dotPaint = Paint().apply {
        color = CURRENT_POS_COLOR
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val arrowPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun update(lat: Double, lon: Double, bearing: Float) {
        position = GeoPoint(lat, lon)
        bearingDeg = bearing
        mapView.invalidate()
    }

    fun clear() {
        position = null
        mapView.invalidate()
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        val pos = position ?: return
        val px = projection.toPixels(pos, null)
        val radius = 22f

        canvas.drawCircle(px.x.toFloat(), px.y.toFloat(), radius + 5f, haloPaint)
        canvas.drawCircle(px.x.toFloat(), px.y.toFloat(), radius, dotPaint)

        canvas.save()
        canvas.rotate(bearingDeg, px.x.toFloat(), px.y.toFloat())
        val path = Path().apply {
            moveTo(px.x.toFloat(), px.y.toFloat() - radius - 14f)
            lineTo(px.x.toFloat() - 8f, px.y.toFloat() - radius + 2f)
            lineTo(px.x.toFloat() + 8f, px.y.toFloat() - radius + 2f)
            close()
        }
        canvas.drawPath(path, arrowPaint)
        canvas.restore()
    }
}

class RouteMarkersOverlay(private val mapView: MapView) : Overlay() {
    data class MarkerInfo(val position: GeoPoint, val color: Int, val label: String)

    private var startMarker: MarkerInfo? = null
    private var destMarker: MarkerInfo? = null
    private var routePoints: List<GeoPoint> = emptyList()

    private val routePaint = Paint().apply {
        color = ROUTE_LINE_COLOR
        strokeWidth = 14f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val markerFont = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }
    private val haloPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val dotPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    fun update(start: Position?, dest: Position?, route: List<Position>) {
        startMarker = start?.let { MarkerInfo(GeoPoint(it.latitude, it.longitude), START_MARKER_COLOR, "Start") }
        destMarker  = dest?.let  { MarkerInfo(GeoPoint(it.latitude, it.longitude),  DEST_MARKER_COLOR,  "Dest") }
        routePoints = route.map { GeoPoint(it.latitude, it.longitude) }
        mapView.invalidate()
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        if (routePoints.size >= 2) {
            val first = projection.toPixels(routePoints[0], null)
            val path = Path().apply { moveTo(first.x.toFloat(), first.y.toFloat()) }
            for (i in 1 until routePoints.size) {
                val p = projection.toPixels(routePoints[i], null)
                path.lineTo(p.x.toFloat(), p.y.toFloat())
            }
            canvas.drawPath(path, routePaint)
        }

        fun drawMarker(marker: MarkerInfo?) {
            marker ?: return
            val px = projection.toPixels(marker.position, null)
            val radius = 28f
            haloPaint.color = Color.WHITE
            canvas.drawCircle(px.x.toFloat(), px.y.toFloat(), radius + 5f, haloPaint)
            dotPaint.color = marker.color
            canvas.drawCircle(px.x.toFloat(), px.y.toFloat(), radius, dotPaint)
            canvas.drawText(marker.label, px.x.toFloat(), px.y.toFloat() - radius - 16f, markerFont)
        }
        drawMarker(startMarker)
        drawMarker(destMarker)
    }
}

class OsmFeaturesOverlay(
    private val roads: List<OsmXmlParser.RoadFeature>,
    private val buildings: List<OsmXmlParser.AreaFeature>,
    private val water: List<OsmXmlParser.AreaFeature>
) : Overlay() {

    private val waterPaint = Paint().apply {
        color = WATER_COLOR; style = Paint.Style.FILL; isAntiAlias = true
    }
    private val buildingPaint = Paint().apply {
        color = BUILDING_COLOR; style = Paint.Style.FILL; isAntiAlias = true
    }
    private val roadPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private data class RoadEntry(
        val points: List<GeoPoint>, val color: Int, val width: Float,
        val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double
    )
    private data class AreaEntry(
        val points: List<GeoPoint>,
        val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double
    )

    private val roadEntries: List<RoadEntry> = roads.mapNotNull { road ->
        if (road.highway !in ROAD_HIGHWAYS || road.points.size < 2) return@mapNotNull null
        val (color, width) = styleForHighway(road.highway)
        val b = boundsOf(road.points)
        RoadEntry(road.points, color, width, b.a, b.b, b.c, b.d)
    }
    private val buildingEntries: List<AreaEntry> = buildings.mapNotNull { area ->
        if (area.points.size < 3) return@mapNotNull null
        val b = boundsOf(area.points)
        AreaEntry(area.points, b.a, b.b, b.c, b.d)
    }
    private val waterEntries: List<AreaEntry> = water.mapNotNull { area ->
        if (area.points.size < 3) return@mapNotNull null
        val b = boundsOf(area.points)
        AreaEntry(area.points, b.a, b.b, b.c, b.d)
    }

    private data class Bounds4(val a: Double, val b: Double, val c: Double, val d: Double)
    private fun boundsOf(points: List<GeoPoint>): Bounds4 {
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for (p in points) {
            if (p.latitude < minLat) minLat = p.latitude
            if (p.latitude > maxLat) maxLat = p.latitude
            if (p.longitude < minLon) minLon = p.longitude
            if (p.longitude > maxLon) maxLon = p.longitude
        }
        return Bounds4(minLat, maxLat, minLon, maxLon)
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        val bbox = projection.boundingBox
        for (entry in waterEntries) {
            if (!intersects(entry.minLat, entry.maxLat, entry.minLon, entry.maxLon, bbox)) continue
            drawArea(canvas, projection, entry.points, waterPaint)
        }
        for (entry in buildingEntries) {
            if (!intersects(entry.minLat, entry.maxLat, entry.minLon, entry.maxLon, bbox)) continue
            drawArea(canvas, projection, entry.points, buildingPaint)
        }
        for (entry in roadEntries) {
            if (!intersects(entry.minLat, entry.maxLat, entry.minLon, entry.maxLon, bbox)) continue
            roadPaint.color = entry.color
            roadPaint.strokeWidth = entry.width
            drawLine(canvas, projection, entry.points, roadPaint)
        }
    }

    private fun intersects(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        bbox: org.osmdroid.util.BoundingBox
    ): Boolean =
        maxLat >= bbox.latSouth && minLat <= bbox.latNorth &&
                maxLon >= bbox.lonWest && minLon <= bbox.lonEast

    private fun drawArea(canvas: Canvas, projection: Projection, points: List<GeoPoint>, paint: Paint) {
        val path = Path()
        val first = projection.toPixels(points[0], null)
        path.moveTo(first.x.toFloat(), first.y.toFloat())
        for (i in 1 until points.size) {
            val p = projection.toPixels(points[i], null)
            path.lineTo(p.x.toFloat(), p.y.toFloat())
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawLine(canvas: Canvas, projection: Projection, points: List<GeoPoint>, paint: Paint) {
        val path = Path()
        val first = projection.toPixels(points[0], null)
        path.moveTo(first.x.toFloat(), first.y.toFloat())
        for (i in 1 until points.size) {
            val p = projection.toPixels(points[i], null)
            path.lineTo(p.x.toFloat(), p.y.toFloat())
        }
        canvas.drawPath(path, paint)
    }
}

@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    onMapTap: (lat: Double, lon: Double) -> Unit,
    start: Position?,
    dest: Position?,
    route: List<Position>,
    currentPosition: Position?,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val tileProvider = remember { BlankTileProvider.create(context) }

    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    val routeMarkersRef = remember { mutableStateOf<RouteMarkersOverlay?>(null) }
    val currentPositionRef = remember { mutableStateOf<CurrentPositionOverlay?>(null) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx, tileProvider).apply {
                setBackgroundColor(MAP_BG_COLOR)
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
                overlays.add(CompassOverlay(ctx, this))

                val posOverlay = CurrentPositionOverlay(this)
                currentPositionRef.value = posOverlay
                overlays.add(posOverlay)

                val routeOverlay = RouteMarkersOverlay(this)
                routeMarkersRef.value = routeOverlay
                overlays.add(routeOverlay)

                mapViewRef.value = this
            }
        },
        update = { mapView ->
            routeMarkersRef.value?.update(start, dest, route)
            currentPositionRef.value?.let { overlay ->
                val pos = currentPosition
                if (pos != null) {
                    overlay.update(pos.latitude, pos.longitude, pos.bearing)
                    try {
                        mapView.controller.animateTo(GeoPoint(pos.latitude, pos.longitude))
                    } catch (_: Exception) {}
                } else {
                    overlay.clear()
                }
            }
        }
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapViewRef.value?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapViewRef.value?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapViewRef.value?.onDetach()
        }
    }

    LaunchedEffect(Unit) {
        val data = withContext(Dispatchers.IO) {
            try {
                OsmXmlParser.parseFromAssets(context, OSM_FILE_NAME)
            } catch (e: Exception) {
                Log.e("MapScreen", "OSM load failed", e)
                null
            }
        }
        if (data == null) {
            Log.e("MapScreen", "OSM data is null — check assets/$OSM_FILE_NAME exists")
            return@LaunchedEffect
        }
        Log.d("MapScreen",
            "OSM loaded: ${data.water.size} water, ${data.buildings.size} buildings, ${data.roads.size} roads")

        val overlay = withContext(Dispatchers.Default) {
            OsmFeaturesOverlay(data.roads, data.buildings, data.water)
        }

        mapViewRef.value?.let { mv ->
            mv.overlays.add(overlay)
            mv.invalidate()
            Log.d("MapScreen", "OsmFeaturesOverlay attached")
        }
    }
}