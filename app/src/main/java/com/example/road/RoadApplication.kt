package com.example.road

import android.app.Application
import androidx.preference.PreferenceManager
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration

@HiltAndroidApp
class RoadApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Must run before any osmdroid MapView is created, or OSM's tile
        // servers will 403 the requests (they reject blank/default User-Agent values).
        Configuration.getInstance().load(
            this,
            PreferenceManager.getDefaultSharedPreferences(this)
        )
        Configuration.getInstance().userAgentValue = packageName
    }
}