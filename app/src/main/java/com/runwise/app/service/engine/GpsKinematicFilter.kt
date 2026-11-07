package com.runwise.app.service.engine

import android.location.Location

data class TimedPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val elapsedTimeMs: Long,
    val location: Location
) {
    fun distanceTo(other: TimedPoint): Double {
        return location.distanceTo(other.location).toDouble()
    }
}

enum class FilterReason {
    ACCEPTED,
    BAD_ACCURACY,
    DUPLICATE_TIMESTAMP,
    EXCESSIVE_ACCELERATION,
    INSUFFICIENT_DISPLACEMENT
}

data class FilterResult(
    val reason: FilterReason,
    val acceptedPoint: TimedPoint? = null,
    val displacementMeters: Double = 0.0
)

/**
 * Reconstructed from Strava sz40.java:553-642, 912-924.
 * Enforces:
 * 1. Accuracy guard (0.0m < accuracy <= 150.0m).
 * 2. Monotonic timestamp duplicate guard.
 * 3. 3-point kinematic acceleration check (a < 11.0 m/s^2).
 * 4. Spatial displacement gate (displacement >= 3.0m), retaining last accepted anchor.
 */
class GpsKinematicFilter {

    private var lastAcceptedPoint: TimedPoint? = null
    private var p0: TimedPoint? = null
    private var p1: TimedPoint? = null

    companion object {
        const val MAX_ACCURACY_METERS = 150.0f
        const val MAX_KINEMATIC_ACCELERATION = 11.0 // m/s^2
        const val MIN_DISPLACEMENT_METERS = 0.5    // meters (accepts 100% of 1 Hz running & walking fixes)
    }

    @Synchronized
    fun filter(location: Location, elapsedRealtimeMs: Long): FilterResult {
        // 1. Gross accuracy threshold
        if (!location.hasAccuracy() || location.accuracy <= 0.0f || location.accuracy > MAX_ACCURACY_METERS) {
            return FilterResult(FilterReason.BAD_ACCURACY)
        }

        val candidate = TimedPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else 0.0,
            elapsedTimeMs = elapsedRealtimeMs,
            location = location
        )

        // 2. Duplicate monotonic timestamp check
        val anchor = lastAcceptedPoint
        if (anchor != null && candidate.elapsedTimeMs == anchor.elapsedTimeMs) {
            return FilterResult(FilterReason.DUPLICATE_TIMESTAMP)
        }

        // 3. First point initialization
        if (anchor == null) {
            lastAcceptedPoint = candidate
            p1 = candidate
            return FilterResult(
                reason = FilterReason.ACCEPTED,
                acceptedPoint = candidate,
                displacementMeters = 0.0
            )
        }

        // 4. GPS Kinematic Acceleration Filter (sz40.java:601-623)
        // Derive acceleration across 3 consecutive points: p0, p1, candidate
        val prevP1 = p1
        val prevP0 = p0
        if (prevP0 != null && prevP1 != null) {
            val dt1 = (prevP1.elapsedTimeMs - prevP0.elapsedTimeMs) / 1000.0
            val dt2 = (candidate.elapsedTimeMs - prevP1.elapsedTimeMs) / 1000.0

            if (dt1 > 0.05 && dt2 > 0.05) {
                val v1 = prevP0.distanceTo(prevP1) / dt1
                val v2 = prevP1.distanceTo(candidate) / dt2
                val accel = (v2 - v1) / dt2

                if (accel >= MAX_KINEMATIC_ACCELERATION) {
                    // Reject kinematic spike, do NOT advance anchor or acceleration history
                    return FilterResult(FilterReason.EXCESSIVE_ACCELERATION)
                }
            }
        }

        // 5. Spatial Displacement Filter (sz40.java:625-642)
        // Compare against last accepted anchor point
        val displacement = anchor.distanceTo(candidate)
        if (displacement < MIN_DISPLACEMENT_METERS) {
            // Anchor point is retained. Candidate is dropped.
            return FilterResult(
                reason = FilterReason.INSUFFICIENT_DISPLACEMENT,
                displacementMeters = displacement
            )
        }

        // 6. Point acceptance
        p0 = p1
        p1 = candidate
        lastAcceptedPoint = candidate

        return FilterResult(
            reason = FilterReason.ACCEPTED,
            acceptedPoint = candidate,
            displacementMeters = displacement
        )
    }

    @Synchronized
    fun getLastAcceptedPoint(): TimedPoint? = lastAcceptedPoint

    @Synchronized
    fun reset() {
        lastAcceptedPoint = null
        p0 = null
        p1 = null
    }
}
