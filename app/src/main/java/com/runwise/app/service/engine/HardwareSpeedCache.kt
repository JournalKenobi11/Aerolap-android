package com.runwise.app.service.engine

import android.os.SystemClock

/**
 * Reconstructed from Strava sz40.java:546-552, 1436-1440 (qyb).
 * Unconditionally caches hardware speed when present, with a strict 3000ms freshness window.
 */
class HardwareSpeedCache {
    private var speedMps: Double? = null
    private var timestampMs: Long = 0L

    @Synchronized
    fun update(speed: Float, nowMs: Long = SystemClock.elapsedRealtime()) {
        speedMps = speed.toDouble()
        timestampMs = nowMs
    }

    @Synchronized
    fun getFreshSpeedMps(nowMs: Long = SystemClock.elapsedRealtime()): Double? {
        val spd = speedMps
        return if (spd != null && (nowMs - timestampMs) < 3000L) {
            spd
        } else {
            null
        }
    }

    @Synchronized
    fun clear() {
        speedMps = null
        timestampMs = 0L
    }
}
