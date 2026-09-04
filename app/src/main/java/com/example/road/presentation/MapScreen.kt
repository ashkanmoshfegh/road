package com.example.road.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.road.utils.OfflineTileProvider
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay

@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    onMapTap: (lat: Double, lon: Double) -> Unit
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            MapView(context).apply {
                setUseDataConnection(false) // never attempt network tile fetches
                setTileProvider(OfflineTileProvider.create(context))
                setMultiTouchControls(true)
                controller.setZoom(13.0)

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
    )
}