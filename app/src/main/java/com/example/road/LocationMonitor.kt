package com.example.road

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle

class LocationMonitor(
    context: Context,
    private val ins: InertialNavigationSystem,
    private val sensorProvider: SensorProvider
) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var isGpsAvailable = false
    private var lastGpsLocation: Location? = null
    private var lastUpdateTime: Long = 0
    
    private val GPS_TIMEOUT = 5000L

    interface LocationUpdateListener {
        fun onLocationChanged(position: NavPosition, source: String)
    }

    private var listener: LocationUpdateListener? = null

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            isGpsAvailable = true
            lastGpsLocation = location
            lastUpdateTime = System.currentTimeMillis()
            
            val navPos = NavPosition(location.latitude, location.longitude, location.bearing)
            ins.reset(navPos)
            listener?.onLocationChanged(navPos, "GPS")
        }

        override fun onProviderDisabled(provider: String) { isGpsAvailable = false }
        override fun onProviderEnabled(provider: String) { isGpsAvailable = true }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    fun startMonitoring(updateListener: LocationUpdateListener) {
        listener = updateListener
        sensorProvider.start()
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L,
            1f,
            gpsListener
        )
    }

    fun stopMonitoring() {
        locationManager.removeUpdates(gpsListener)
        sensorProvider.stop()
    }

    fun update() {
        val currentTime = System.currentTimeMillis()
        if (lastUpdateTime != 0L && currentTime - lastUpdateTime > GPS_TIMEOUT) {
            isGpsAvailable = false
        }

        if (!isGpsAvailable && lastGpsLocation != null) {
            val speed = lastGpsLocation?.speed ?: 0f
            // Use rotation rate from Z-axis to estimate heading change
            val heading = lastGpsLocation?.bearing ?: 0f
            
            val currentPos = lastGpsLocation?.let { 
                NavPosition(it.latitude, it.longitude, it.bearing) 
            } ?: return

            val estimatedPos = ins.update(currentPos, speed, heading, currentTime)
            listener?.onLocationChanged(estimatedPos, "INS (Resilient Mode)")
        }
    }
}
