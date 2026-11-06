package com.runwise.app.service.engine

import java.util.ArrayDeque
import kotlin.math.roundToInt

/**
 * Reconstructed from Strava defpackage.n200.a(long r8).
 * Bytecode-exact implementation of 15-second rolling pace calculation:
 * 1. 15-second sliding window deque.
 * 2. Warmup guard: returns null / 0 if timerTimeMs <= 1000L or fewer than 2 points.
 * 3. Native startup asymmetry: if timerTimeMs <= 15000L, subtracts 0L instead of oldest.timestampMs.
 * 4. Integer truncation: dt / 1000 before double division.
 * 5. Upper clamp: paces > 3599s (59:59) return 0 ("-:--").
 */
class RollingPaceEstimator {
    private data class Sample(val timestampMs: Long, val distanceMeters: Double)

    private val deque = ArrayDeque<Sample>()

    @Synchronized
    fun reset() {
        deque.clear()
    }

    @Synchronized
    fun addSample(timerTimeMs: Long, distanceMeters: Double) {
        deque.addLast(Sample(timerTimeMs, distanceMeters))
    }

    @Synchronized
    fun calculateRollingSpeedMps(timerTimeMs: Long): Double? {
        val windowCutoff = timerTimeMs - 15000L

        // Prune points older than 15-second window
        while (!deque.isEmpty() && deque.first.timestampMs < windowCutoff) {
            deque.removeFirst()
        }

        val oldest = deque.peekFirst()
        val newest = deque.peekLast()

        if (oldest == null || newest == null || oldest == newest) {
            return null
        }

        // Native Strava asymmetry: if timer <= 15s, subtract 0L instead of oldest.timestampMs
        val dtBase = if (timerTimeMs > 15000L) oldest.timestampMs else 0L
        val dtMs = newest.timestampMs - dtBase
        val deltaDistanceMeters = newest.distanceMeters - oldest.distanceMeters

        // Warmup guard: first 1000ms returns null
        if (timerTimeMs <= 1000L) {
            return null
        }

        // Exact bytecode integer division: dt / 1000 before double conversion
        val truncatedSeconds = dtMs / 1000L
        val secondsDouble = truncatedSeconds.toDouble()

        if (secondsDouble <= 0.0) {
            return null
        }

        val speed = deltaDistanceMeters / secondsDouble
        return if (speed > 0.0) speed else null
    }

    @Synchronized
    fun calculateRollingPaceSecPerKm(timerTimeMs: Long): Int {
        val speedMps = calculateRollingSpeedMps(timerTimeMs) ?: return 0
        val rawPace = 1000.0 / speedMps
        return if (rawPace in 1.0..3599.0) {
            rawPace.roundToInt()
        } else {
            0
        }
    }
}
