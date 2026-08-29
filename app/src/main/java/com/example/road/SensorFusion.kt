package com.example.road

import kotlin.math.*

class SensorFusion {
    private var timestamp = 0L
    private var lastAccel = FloatArray(3)
    private var lastGyro = FloatArray(3)
    private var velocity = 0.0  // m/s
    private var heading = 0.0   // radians
    private var position = Pair(0.0, 0.0) // relative displacement (meters)

    // Complementary filter constants
    private val alpha = 0.98
    private val dt = 0.02 // expected update interval in seconds (50 Hz)

    fun update(accel: FloatArray, gyro: FloatArray, currentTime: Long): Pair<Double, Double>? {
        if (timestamp == 0L) {
            timestamp = currentTime
            lastAccel = accel.clone()
            lastGyro = gyro.clone()
            return null
        }

        val dt = (currentTime - timestamp) / 1000.0
        timestamp = currentTime

        // Gyroscope: integrate angular velocity to get heading change (Z-axis)
        val gyroZ = gyro[2] // rotation around Z-axis in rad/s
        val headingDelta = gyroZ * dt

        // Accelerometer: estimate linear acceleration (remove gravity) – simplified
        // We use the magnitude of acceleration to detect movement and step detection
        val accelMagnitude = sqrt(accel[0].toDouble().pow(2) + accel[1].toDouble().pow(2) + accel[2].toDouble().pow(2))
        // Subtract gravity (9.81) to get linear acceleration
        val linearAccel = accelMagnitude - 9.81

        // Simple step detection: if acceleration peak, we can estimate step length (we'll ignore for now)
        // We'll just update velocity using linear acceleration (naive integration)
        // In practice, use step detection and step length models.
        // For demonstration, we'll use a constant speed if acceleration is above threshold.
        val speed = if (abs(linearAccel) > 1.0) 1.4 else 0.0 // arbitrary: walking speed ~1.4 m/s

        // Update heading with complementary filter: combine gyro (high-pass) and magnetometer? We'll only use gyro.
        // In a real app, use magnetometer for absolute heading correction.
        heading += headingDelta

        // Compute displacement in local frame
        val dx = speed * dt * cos(heading)
        val dy = speed * dt * sin(heading)

        // Accumulate relative position
        position = Pair(position.first + dx, position.second + dy)

        lastAccel = accel.clone()
        lastGyro = gyro.clone()

        return position
    }

    fun reset() {
        timestamp = 0L
        velocity = 0.0
        heading = 0.0
        position = Pair(0.0, 0.0)
    }

    fun getHeading(): Double = heading
}