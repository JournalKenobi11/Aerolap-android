package com.runwise.app.coach

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

class CadenceTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _currentCadenceSpm = MutableStateFlow(0)
    val currentCadenceSpm: StateFlow<Int> = _currentCadenceSpm.asStateFlow()

    private val stepTimestamps = ArrayDeque<Long>()
    private val windowDurationMs = 6_000L // 6-second responsive rolling analysis window to capture cadence changes & drops

    private var totalWorkoutSteps = 0
    private var isTracking = false
    private var lastStepTime = 0L

    // Accelerometer peak detection fallback if step detector not triggered
    private var lastAccelMag = 9.8f
    private var accelPeakPending = false
    private var lastPeakTime = 0L

    fun start() {
        if (isTracking) return
        isTracking = true
        stepTimestamps.clear()
        totalWorkoutSteps = 0
        _currentCadenceSpm.value = 0
        lastStepTime = SystemClock.elapsedRealtime()

        if (stepDetectorSensor != null) {
            sensorManager.registerListener(this, stepDetectorSensor, SensorManager.SENSOR_DELAY_FASTEST)
        } else if (accelerometerSensor != null) {
            sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        if (!isTracking) return
        isTracking = false
        sensorManager.unregisterListener(this)
        _currentCadenceSpm.value = 0
    }

    fun getWorkoutSteps(): Int = totalWorkoutSteps

    fun hasSensor(): Boolean = stepDetectorSensor != null || accelerometerSensor != null

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isTracking || event == null) return

        // SensorEvent.timestamp is in nanoseconds since boot (SystemClock.elapsedRealtimeNanos())
        // Convert to milliseconds to preserve actual hardware step occurrence times and avoid batching spikes
        val eventTimeMs = event.timestamp / 1_000_000L

        if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
            registerStep(eventTimeMs)
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // Accelerometer dynamic magnitude peak detection fallback
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val mag = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()

            // Running step produces impact spike > 13.5 m/s^2 followed by dip
            if (mag > 13.2f && !accelPeakPending && (eventTimeMs - lastPeakTime) > 230L) { // max ~260 spm
                accelPeakPending = true
                lastPeakTime = eventTimeMs
                registerStep(eventTimeMs)
            } else if (mag < 10.0f) {
                accelPeakPending = false
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun registerStep(now: Long) {
        lastStepTime = now
        totalWorkoutSteps++

        stepTimestamps.addLast(now)

        // Prune events outside window
        val cutoff = now - windowDurationMs
        while (stepTimestamps.isNotEmpty() && stepTimestamps.first < cutoff) {
            stepTimestamps.removeFirst()
        }

        // Calculate instantaneous SPM from window
        if (stepTimestamps.size >= 3) {
            val windowSpanMs = (stepTimestamps.last - stepTimestamps.first).coerceAtLeast(1000L)
            val stepsCount = stepTimestamps.size - 1
            val rawSpm = ((stepsCount.toDouble() / windowSpanMs) * 60_000.0).toInt()

            // Fast, authentic updates (75% current raw window, 25% filter) to track true turnover and dips
            val current = _currentCadenceSpm.value
            val smoothed = if (current == 0) rawSpm else (0.75 * rawSpm + 0.25 * current).toInt()
            _currentCadenceSpm.value = smoothed.coerceIn(0, 240)
        }
    }

    /**
     * Call periodically (e.g. every second) from tracking loop to handle cadence decay when runner stops.
     */
    fun tickDecay() {
        if (!isTracking) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastStepTime > 3_500L && _currentCadenceSpm.value > 0) {
            // Decay to zero if no steps in 3.5s
            val current = _currentCadenceSpm.value
            _currentCadenceSpm.value = (current * 0.5).toInt().takeIf { it > 40 } ?: 0
        }
    }
}
