package com.runwise.app.service.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

interface AutoPauseListener {
    fun onAutoPauseTriggered()
    fun onAutoResumeTriggered()
}

/**
 * Reconstructed from Strava km00.java, lmn.java, e7.java, and vxz.java.
 * Dual-Engine Auto-Pause Subsystem:
 * 1. Accelerometer 3D Jerk Engine (20 Hz loop, fast/slow EMA, 3:1 hysteresis ratio).
 * 2. GPS Speed Engine (0.33 m/s threshold, 10-second idle timeout).
 */
class AutoPauseEngine(
    context: Context,
    private val listener: AutoPauseListener
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val handler = Handler(Looper.getMainLooper())

    private var isTracking = false
    private var isRunningState = true

    // Engine A: Accelerometer 3D Jerk variables
    private var lastAccelX = 0f
    private var lastAccelY = 0f
    private var lastAccelZ = 0f
    private var lastSampleTimeSec = 0.0

    private var fastEma = 150.0
    private var slowEma = 150.0
    private var lastRunningBaselineE = 150.0

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isTracking) return
            evaluateJerkTick()
            handler.postDelayed(this, 50L) // 20 Hz loop
        }
    }

    // Engine B: GPS Speed Timeout variables (vxz.java)
    private var lastGpsMovementTimeMs = 0L
    private val gpsPauseRunnable = Runnable {
        if (isTracking && isRunningState) {
            transitionToPaused()
        }
    }

    fun start() {
        if (isTracking) return
        isTracking = true
        isRunningState = true
        fastEma = 150.0
        slowEma = 150.0
        lastRunningBaselineE = 150.0
        lastSampleTimeSec = SystemClock.elapsedRealtime() / 1000.0
        lastGpsMovementTimeMs = SystemClock.elapsedRealtime()

        if (accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
            handler.post(tickRunnable)
        }

        scheduleGpsTimeout()
    }

    fun stop() {
        if (!isTracking) return
        isTracking = false
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(gpsPauseRunnable)
        sensorManager.unregisterListener(this)
    }

    fun setRunningState(running: Boolean) {
        isRunningState = running
    }

    fun isAutoPaused(): Boolean = !isRunningState

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isTracking || event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        lastAccelX = event.values[0]
        lastAccelY = event.values[1]
        lastAccelZ = event.values[2]
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * 20 Hz Accelerometer 3D Jerk evaluation (km00.java & lmn.java)
     */
    private fun evaluateJerkTick() {
        val nowSec = SystemClock.elapsedRealtime() / 1000.0
        val dt = max(0.05, kotlin.math.abs(nowSec - lastSampleTimeSec))
        lastSampleTimeSec = nowSec

        if (dt > 0.2) {
            // Gap guard: reset reference
            return
        }

        // Jerk magnitude: ||j|| = ||Δa / Δt||
        val jx = lastAccelX / dt
        val jy = lastAccelY / dt
        val jz = lastAccelZ / dt
        val jerkMag = sqrt(jx * jx + jy * jy + jz * jz).toDouble()

        // Dual Half-Life EMAs
        // Fast EMA (half-life = 0.4s)
        val dPowFast = 2.0.pow(-kotlin.math.abs(dt / 0.4))
        fastEma = dPowFast * fastEma + (1.0 - dPowFast) * jerkMag

        // Slow EMA (half-life = 10.0s)
        val dPowSlow = 2.0.pow(-kotlin.math.abs(dt / 10.0))
        slowEma = dPowSlow * slowEma + (1.0 - dPowSlow) * jerkMag
        slowEma = max(50.0, slowEma) // 50.0 m/s^3 minimum noise floor

        // State Transitions
        if (isRunningState) {
            // Stop condition: Fast drops below 1/3 of Slow baseline
            if (3.0 * fastEma < slowEma) {
                lastRunningBaselineE = slowEma
                slowEma = fastEma
                transitionToPaused()
            }
        } else {
            // Resume condition: Fast spikes above 3x Slow OR above prior running baseline
            if (fastEma > 3.0 * slowEma || fastEma > lastRunningBaselineE) {
                slowEma = fastEma
                transitionToResumed()
            }
        }
    }

    /**
     * Called whenever a valid GPS speed or derived speed is calculated (vxz.java).
     */
    fun onGpsSpeedUpdate(speedMps: Double) {
        if (!isTracking) return
        val now = SystemClock.elapsedRealtime()

        if (speedMps > 0.33) { // ~1.188 km/h movement threshold
            lastGpsMovementTimeMs = now
            scheduleGpsTimeout()

            // If auto-paused, GPS movement can trigger resume
            if (!isRunningState) {
                transitionToResumed()
            }
        }
    }

    private fun scheduleGpsTimeout() {
        handler.removeCallbacks(gpsPauseRunnable)
        handler.postDelayed(gpsPauseRunnable, 10000L) // 10-second idle timeout
    }

    private fun transitionToPaused() {
        if (!isRunningState) return
        isRunningState = false
        listener.onAutoPauseTriggered()
    }

    private fun transitionToResumed() {
        if (isRunningState) return
        isRunningState = true
        scheduleGpsTimeout()
        listener.onAutoResumeTriggered()
    }
}
