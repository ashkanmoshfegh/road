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
import com.example.road.utils.GraphLoader
import com.example.road.utils.OfflineTileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polyline

private val TEHRAN_CENTER = GeoPoint(35.6892, 51.3890)
private const val MIN_ZOOM = 10.0
private const val MAX_ZOOM = 17.0
private const val INITIAL_ZOOM = 13.0

@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    onMapTap: (lat: Double, lon: Double) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        val tileProvider = OfflineTileProvider.create(context)
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

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                // Load the graph edges from JSON
                val graphData = GraphLoader.loadGraph(context, "graph.json")
                val edges = graphData.second
                
                withContext(Dispatchers.Main) {
                    // Limiting edges to prevent OOM/UI freeze if the graph is huge
                    edges.take(1000).forEach { edge ->
                        val line = Polyline(mapView).apply {
                            outlinePaint.color = Color.parseColor("#2979FF")
                            outlinePaint.strokeWidth = 3f
                            setPoints(
                                listOf(
                                    GeoPoint(edge.from.lat, edge.from.lon),
                                    GeoPoint(edge.to.lat, edge.to.lon)
                                )
                            )
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
