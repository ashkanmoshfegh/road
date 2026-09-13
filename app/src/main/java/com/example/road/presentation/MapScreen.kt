package com.example.road.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.location.Location
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
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

private val TEHRAN_CENTER = GeoPoint(35.6892, 51.3890)
private const val MIN_ZOOM = 8.0
private const val MAX_ZOOM = 21.0
private const val INITIAL_ZOOM = 13.0

// MUST match an actual file in app/src/main/assets/
private const val OSM_FILE_NAME = "tehran-map.osm"

private val START_MARKER_COLOR = Color.parseColor("#2E7D32")
private val DEST_MARKER_COLOR  = Color.parseColor("#C62828")
private val ROUTE_LINE_COLOR   = Color.parseColor("#FFD600")

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

// Total vertex budget across *all* overlays. Raise if your device has RAM.
private const val MAX_OSM_POINTS = 1_500_000

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

class MyLocationOverlay(context: Context, mapView: MapView) :
    MyLocationNewOverlay(GpsMyLocationProvider(context), mapView) {
    init { setDrawAccuracyEnabled(true) }

    fun update(lat: Double, lon: Double, bearingDeg: Float) {
        val loc = Location("manual").apply {
            longitude = lon
            latitude = lat
            accuracy = 2f
            altitude = 0.0
        }
        loc.bearing = bearingDeg
        setLocation(loc)
        mMapView.invalidate()
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        val fix = getLastFix() ?: return
        drawMyLocation(canvas, projection, fix)
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
        if (projection == null) return
        drawInto(canvas, projection)
    }

    private fun drawInto(canvas: Canvas, projection: Projection) {
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

    // ---------- FIX #1: use the blank vector-only tile provider ----------
    val tileProvider = remember { BlankTileProvider.create(context) }

    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    val routeMarkersRef = remember { mutableStateOf<RouteMarkersOverlay?>(null) }
    val locationOverlayRef = remember { mutableStateOf<MyLocationOverlay?>(null) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx, tileProvider).apply {
                // ---------- FIX #2: light background, not black ----------
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

                val locOverlay = MyLocationOverlay(ctx, this)
                locationOverlayRef.value = locOverlay
                overlays.add(locOverlay)

                val routeOverlay = RouteMarkersOverlay(this)
                routeMarkersRef.value = routeOverlay
                overlays.add(routeOverlay)

                mapViewRef.value = this
            }
        },
        update = { mapView ->
            routeMarkersRef.value?.update(start, dest, route)
            locationOverlayRef.value?.let { locOverlay ->
                currentPosition?.let { pos ->
                    locOverlay.update(pos.latitude, pos.longitude, pos.bearing)
                    try {
                        mapView.controller.animateTo(GeoPoint(pos.latitude, pos.longitude))
                    } catch (_: Exception) {}
                }
            }
        }
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapViewRef.value?.onResume()
                    locationOverlayRef.value?.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    mapViewRef.value?.onPause()
                    locationOverlayRef.value?.onPause()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapViewRef.value?.onDetach()
            locationOverlayRef.value?.onDetach(mapViewRef.value)
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
        mapViewRef.value?.let { mv ->
            drawOsmOverlays(mv, data)
            mv.invalidate()
        }
    }
}

// ---------- FIX #3: draw roads first so they aren't starved by point budget ----------
private fun drawOsmOverlays(mapView: MapView, data: OsmXmlParser.ParseResult) {
    var totalPoints = 0L
    val zoom = mapView.getZoomLevelDouble()

    Log.d("MapScreen",
        "Drawing overlays at zoom=$zoom water=${data.water.size} buildings=${data.buildings.size} roads=${data.roads.size}")

    // 1) Roads — always draw at any zoom.
    for (road in data.roads) {
        if (totalPoints + road.points.size > MAX_OSM_POINTS) {
            Log.w("MapScreen", "Point budget reached while drawing roads")
            break
        }
        if (road.highway !in ROAD_HIGHWAYS) continue
        val (color, width) = styleForHighway(road.highway)
        val line = Polyline(mapView).apply {
            setPoints(road.points)
            getOutlinePaint().color = color
            getOutlinePaint().strokeWidth = width
        }
        mapView.overlays.add(line)
        totalPoints += road.points.size
    }

    // 2) Water — draw before buildings.
    for (area in data.water) {
        if (totalPoints + area.points.size > MAX_OSM_POINTS) break
        val poly = Polygon(mapView).apply {
            setPoints(area.points)
            getFillPaint().color = WATER_COLOR
            getOutlinePaint().color = WATER_COLOR
            getOutlinePaint().strokeWidth = 1f
        }
        mapView.overlays.add(poly)
        totalPoints += area.points.size
    }

    // 3) Buildings last.
    for (area in data.buildings) {
        if (totalPoints + area.points.size > MAX_OSM_POINTS) break
        val poly = Polygon(mapView).apply {
            setPoints(area.points)
            getFillPaint().color = BUILDING_COLOR
            getOutlinePaint().color = BUILDING_COLOR
            getOutlinePaint().strokeWidth = 1f
        }
        mapView.overlays.add(poly)
        totalPoints += area.points.size
    }

    Log.d("MapScreen", "Total OSM points drawn: $totalPoints")
    mapView.invalidate()
}