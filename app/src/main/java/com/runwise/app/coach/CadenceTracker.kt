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
import kotlin.math.sqrt

class CadenceTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _currentCadenceSpm = MutableStateFlow(0)
    val currentCadenceSpm: StateFlow<Int> = _currentCadenceSpm.asStateFlow()

    // Step interval tracking for robust, glitch-free cadence calculation
    private val stepIntervalsMs = ArrayDeque<Long>()
    private val maxIntervalsCount = 8

    private var totalWorkoutSteps = 0
    private var isTracking = false
    private var lastStepEventTimeMs = 0L
    private var lastStepWallRealtimeMs = 0L

    // Step counter fallback tracking
    private var initialStepCounterValue = -1
    private var lastStepCounterValue = -1
    private var lastStepCounterEventTimeMs = 0L

    // Accelerometer dynamic magnitude peak detection fallback
    private var accelPeakPending = false
    private var lastPeakTimeMs = 0L

    fun start() {
        if (isTracking) return
        isTracking = true
        stepIntervalsMs.clear()
        totalWorkoutSteps = 0
        _currentCadenceSpm.value = 0
        lastStepEventTimeMs = 0L
        lastStepWallRealtimeMs = SystemClock.elapsedRealtime()
        initialStepCounterValue = -1
        lastStepCounterValue = -1
        lastStepCounterEventTimeMs = 0L
        accelPeakPending = false
        lastPeakTimeMs = 0L

        var registeredAny = false
        if (stepDetectorSensor != null) {
            sensorManager.registerListener(this, stepDetectorSensor, SensorManager.SENSOR_DELAY_FASTEST)
            registeredAny = true
        }
        if (stepCounterSensor != null) {
            sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_UI)
            registeredAny = true
        }
        if (!registeredAny && accelerometerSensor != null) {
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

    fun hasSensor(): Boolean = stepDetectorSensor != null || stepCounterSensor != null || accelerometerSensor != null

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isTracking || event == null) return

        val eventTimeMs = event.timestamp / 1_000_000L

        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                registerStep(eventTimeMs)
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val currentCounter = event.values[0].toInt()
                if (initialStepCounterValue < 0) {
                    initialStepCounterValue = currentCounter
                    lastStepCounterValue = currentCounter
                    lastStepCounterEventTimeMs = eventTimeMs
                } else {
                    val stepDelta = currentCounter - lastStepCounterValue
                    val elapsedMs = eventTimeMs - lastStepCounterEventTimeMs
                    if (stepDelta > 0 && elapsedMs > 0) {
                        // If step detector is missing or lagging by > 3s, use step counter cadence
                        val nowRealtime = SystemClock.elapsedRealtime()
                        if (nowRealtime - lastStepWallRealtimeMs > 3_000L && elapsedMs in 500L..10_000L) {
                            val counterSpm = ((stepDelta.toDouble() / elapsedMs) * 60_000.0).toInt().coerceIn(60, 240)
                            updateCadence(counterSpm)
                            lastStepWallRealtimeMs = nowRealtime
                        }
                    }
                    lastStepCounterValue = currentCounter
                    lastStepCounterEventTimeMs = eventTimeMs
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // Accelerometer dynamic magnitude peak detection fallback
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val mag = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

                val timeSincePeak = eventTimeMs - lastPeakTimeMs
                if (mag > 12.8f && !accelPeakPending && timeSincePeak > 220L) {
                    accelPeakPending = true
                    lastPeakTimeMs = eventTimeMs
                    registerStep(eventTimeMs)
                } else if (mag < 11.2f || timeSincePeak > 380L) {
                    // Prevent deadlock: unlatch if magnitude drops OR if max step interval has elapsed
                    accelPeakPending = false
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    internal fun registerStep(nowEventTimeMs: Long) {
        val nowWallRealtime = SystemClock.elapsedRealtime()
        lastStepWallRealtimeMs = nowWallRealtime
        totalWorkoutSteps++

        if (lastStepEventTimeMs > 0L) {
            val deltaMs = nowEventTimeMs - lastStepEventTimeMs
            // Valid runner step interval bounds: 220ms (max ~272 SPM) to 800ms (min 75 SPM)
            if (deltaMs in 220L..800L) {
                stepIntervalsMs.addLast(deltaMs)
                if (stepIntervalsMs.size > maxIntervalsCount) {
                    stepIntervalsMs.removeFirst()
                }

                // Compute cadence from trimmed average step interval
                if (stepIntervalsMs.size >= 3) {
                    val sorted = stepIntervalsMs.toList().sorted()
                    // Discard single min/max outliers if we have at least 4 samples
                    val trimmed = if (sorted.size >= 4) sorted.subList(1, sorted.size - 1) else sorted
                    val avgIntervalMs = trimmed.average()
                    if (avgIntervalMs > 0.0) {
                        val instantSpm = (60_000.0 / avgIntervalMs).toInt().coerceIn(60, 240)
                        updateCadence(instantSpm)
                    }
                }
            } else if (deltaMs > 800L) {
                // Pause or slow transition: reset interval buffer so old intervals don't skew the next stride
                stepIntervalsMs.clear()
            }
        }

        lastStepEventTimeMs = nowEventTimeMs
    }

    private fun updateCadence(rawSpm: Int) {
        val current = _currentCadenceSpm.value
        // Stable IIR filter: 40% new sample, 60% previous smoothed value to prevent jumpiness and sudden drop spikes
        val smoothed = if (current == 0) rawSpm else (0.40 * rawSpm + 0.60 * current).toInt()
        _currentCadenceSpm.value = smoothed.coerceIn(60, 240)
    }

    /**
     * Call periodically (e.g. every second) from tracking loop to handle cadence decay when runner stops.
     */
    fun tickDecay() {
        if (!isTracking) return
        val now = SystemClock.elapsedRealtime()
        // Grace period of 4.5s to accommodate OS sensor batching before initiating decay
        if (now - lastStepWallRealtimeMs > 4_500L && _currentCadenceSpm.value > 0) {
            val current = _currentCadenceSpm.value
            // Smooth decay: reduce by 25% per second when stopped, rather than instantaneous collapse
            val decayed = (current * 0.75).toInt()
            _currentCadenceSpm.value = if (decayed >= 50) decayed else 0
            if (_currentCadenceSpm.value == 0) {
                stepIntervalsMs.clear()
            }
        }
    }
}
