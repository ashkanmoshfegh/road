package com.example.road

import android.app.Application
import androidx.preference.PreferenceManager
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration
import java.io.File

@HiltAndroidApp
class RoadApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Load osmdroid configuration
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        Configuration.getInstance().load(this, sharedPrefs)
        Configuration.getInstance().userAgentValue = packageName
        
        // Explicitly set cache directories to internal storage to avoid permission issues
        val osmdroidDir = File(cacheDir, "osmdroid")
        if (!osmdroidDir.exists()) osmdroidDir.mkdirs()

        Configuration.getInstance().osmdroidBasePath = osmdroidDir
        Configuration.getInstance().osmdroidTileCache = File(osmdroidDir, "tiles")
    }
}
