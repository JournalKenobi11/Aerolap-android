package com.runwise.app.service.engine

import kotlin.math.roundToInt

/**
 * High-accuracy live pace smoothing filter:
 * 1. Gap reset: if dt > 3000ms, flushes buffer and mutes guidance for 15s.
 * 2. Physical clamp: ignores standing still (<0.8 m/s) and GPS rubber-banding spikes (>6.0 m/s).
 * 3. 4-sample sliding average window cancels 2-second alternating coordinate/Doppler sawtooth oscillation.
 * 4. Exponential Moving Average (EMA) with alpha = 0.22 in speed space (m/s) prevents Jensen's inequality slow bias.
 */
class LivePaceFilter(private val alpha: Double = 0.22) {
    private val window = ArrayDeque<Double>(4)
    private var emaSpeedMps: Double? = null
    private var lastTimestampMs: Long? = null
    private var muteUntilMs: Long = 0L

    @Synchronized
    fun reset() {
        window.clear()
        emaSpeedMps = null
        lastTimestampMs = null
        muteUntilMs = 0L
    }

    @Synchronized
    fun update(rawSpeedMps: Double, nowMs: Long): Pair<Double?, Boolean> {
        val dt = lastTimestampMs?.let { nowMs - it } ?: 1000L
        lastTimestampMs = nowMs

        // 1. Gap reset: mute for 15s after pauses or dropouts (> 3s gap)
        if (dt > 3000L) {
            window.clear()
            emaSpeedMps = null
            muteUntilMs = nowMs + 15000L
            return Pair(null, true)
        }

        val isMuted = nowMs < muteUntilMs

        // 2. Physical clamp: reject standing still (<0.8 m/s) and GPS rubber-banding (>6.0 m/s)
        if (rawSpeedMps < 0.8 || rawSpeedMps > 6.0) {
            val pace = emaSpeedMps?.let { 1000.0 / it }
            return Pair(pace, isMuted)
        }

        // 3. 4-sample sliding average cancels 2-second coordinate/Doppler sawtooth
        if (window.size == 4) window.removeFirst()
        window.addLast(rawSpeedMps)
        if (window.size < 4) {
            return Pair(null, true)
        }

        val avgSpeed = window.average()
        emaSpeedMps = emaSpeedMps?.let { prev ->
            alpha * avgSpeed + (1.0 - alpha) * prev
        } ?: avgSpeed

        val paceSecPerKm = 1000.0 / emaSpeedMps!!
        return Pair(paceSecPerKm, isMuted)
    }

    @Synchronized
    fun getFilteredPaceSecPerKm(): Int? {
        val spd = emaSpeedMps ?: return null
        val pace = 1000.0 / spd
        return if (pace in 1.0..3599.0) pace.roundToInt() else null
    }

    @Synchronized
    fun isMuted(nowMs: Long): Boolean {
        return nowMs < muteUntilMs
    }
}
