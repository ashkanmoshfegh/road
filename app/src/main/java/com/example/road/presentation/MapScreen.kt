package com.example.road.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.road.utils.OfflineTileProvider
import org.osmdroid.views.MapView

@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            MapView(context).apply {
                setTileProvider(OfflineTileProvider.create(context))
                setMultiTouchControls(true)
                controller.setZoom(13.0)
            }
        }
    )
}