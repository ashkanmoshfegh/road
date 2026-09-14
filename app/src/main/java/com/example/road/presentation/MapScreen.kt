package com.example.road.presentation

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
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
import com.example.road.utils.MbtilesTileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.*
import org.osmdroid.views.overlay.compass.CompassOverlay

private val TEHRAN_CENTER = GeoPoint(35.6892, 51.3890)

// Match MbtilesTileProvider's own MIN_ZOOM/MAX_ZOOM — the MapView shouldn't
// let the user zoom past what the mbtiles archive actually contains, or
// they'll just see blank/missing tiles outside that range.
private val MIN_ZOOM = MbtilesTileProvider.MIN_ZOOM.toDouble()
private val MAX_ZOOM = MbtilesTileProvider.MAX_ZOOM.toDouble()
private const val INITIAL_ZOOM = 13.0

private val START_MARKER_COLOR   = Color.parseColor("#2E7D32")
private val DEST_MARKER_COLOR    = Color.parseColor("#C62828")
private val ROUTE_LINE_COLOR     = Color.parseColor("#FFD600")
private val CURRENT_POS_COLOR    = Color.parseColor("#1565C0")

/**
 * Draws the user's current sensor-estimated position as a dot + heading
 * arrow. Deliberately NOT osmdroid's MyLocationNewOverlay/GpsMyLocationProvider
 * — that pulls real device GPS internally, which fought with the sensor
 * position. This only ever draws whatever Position update() is given.
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

/**
 * Route line + start/destination markers. Drawn on top of the mbtiles
 * raster background — the GraphHopper graph itself is never rendered
 * anywhere; it only ever supplies coordinates (via RouteCalculator /
 * GraphRepository.findNearest) for this overlay to draw.
 */
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

    // Real raster tiles read from assets/tehran.mbtiles, copied to internal
    // storage on first use (see MbtilesTileProvider.copyMbtilesToInternal).
    // This replaces the vector-only BlankTileProvider approach entirely —
    // the background you see is now the actual pre-rendered mbtiles PNGs,
    // not custom-drawn roads/buildings.
    val tileProvider = remember { MbtilesTileProvider.create(context) }

    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    val routeMarkersRef = remember { mutableStateOf<RouteMarkersOverlay?>(null) }
    val currentPositionRef = remember { mutableStateOf<CurrentPositionOverlay?>(null) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx, tileProvider).apply {
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
}