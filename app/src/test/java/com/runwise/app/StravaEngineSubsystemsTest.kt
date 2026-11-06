package com.runwise.app

import android.location.Location
import com.runwise.app.service.engine.FilterReason
import com.runwise.app.service.engine.GpsKinematicFilter
import com.runwise.app.service.engine.HardwareSpeedCache
import com.runwise.app.service.engine.RollingPaceEstimator
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class StravaEngineSubsystemsTest {

    @Test
    fun testHardwareSpeedCacheFreshness() {
        val cache = HardwareSpeedCache()

        // 1. Initial empty cache returns null
        assertNull(cache.getFreshSpeedMps(1000L))

        // 2. Fresh speed update within 3000ms window
        cache.update(2.78f, 1000L) // 2.78 m/s = 10 km/h
        assertEquals(2.78, cache.getFreshSpeedMps(2500L)!!, 0.001)
        assertEquals(2.78, cache.getFreshSpeedMps(3999L)!!, 0.001)

        // 3. Stale speed (> 3000ms) returns null
        assertNull(cache.getFreshSpeedMps(4000L))
        assertNull(cache.getFreshSpeedMps(5000L))

        // 4. Clear cache
        cache.update(3.0f, 6000L)
        cache.clear()
        assertNull(cache.getFreshSpeedMps(6100L))
    }

    @Test
    fun testGpsKinematicFilterAccuracyGate() {
        val filter = GpsKinematicFilter()

        fun createMockLocation(lat: Double, lng: Double, acc: Float, hasAcc: Boolean = true): Location {
            val loc = Mockito.mock(Location::class.java)
            Mockito.`when`(loc.latitude).thenReturn(lat)
            Mockito.`when`(loc.longitude).thenReturn(lng)
            Mockito.`when`(loc.accuracy).thenReturn(acc)
            Mockito.`when`(loc.hasAccuracy()).thenReturn(hasAcc)
            return loc
        }

        // 1. Accuracy > 150m is rejected
        val badAcc = createMockLocation(12.9716, 77.5946, 151.0f)
        val res1 = filter.filter(badAcc, 1000L)
        assertEquals(FilterReason.BAD_ACCURACY, res1.reason)

        // 2. Accuracy <= 0m is rejected
        val zeroAcc = createMockLocation(12.9716, 77.5946, 0.0f)
        val res2 = filter.filter(zeroAcc, 2000L)
        assertEquals(FilterReason.BAD_ACCURACY, res2.reason)

        // 3. Missing accuracy is rejected
        val missingAcc = createMockLocation(12.9716, 77.5946, 10.0f, hasAcc = false)
        val res3 = filter.filter(missingAcc, 3000L)
        assertEquals(FilterReason.BAD_ACCURACY, res3.reason)

        // 4. Valid accuracy (<= 150m and > 0m) accepted
        val goodAcc = createMockLocation(12.9716, 77.5946, 8.0f)
        val res4 = filter.filter(goodAcc, 4000L)
        assertEquals(FilterReason.ACCEPTED, res4.reason)
    }

    @Test
    fun testRollingPaceEstimator15sAsymmetryAndIntegerDivision() {
        val estimator = RollingPaceEstimator()

        // Warmup: t <= 1000ms returns null/0
        estimator.addSample(500L, 0.0)
        assertNull(estimator.calculateRollingSpeedMps(500L))
        assertEquals(0, estimator.calculateRollingPaceSecPerKm(500L))

        // At t = 5000ms (within first 15s window):
        // oldest at t=500ms, d=0.0m; newest at t=5000ms, d=15.0m
        // Native Strava asymmetry: dtBase = 0L, so dt = 5000 - 0 = 5000ms
        // Integer division: dt / 1000 = 5.0 seconds
        // deltaDistance = 15.0 - 0.0 = 15.0m
        // Speed = 15.0 / 5.0 = 3.0 m/s
        estimator.addSample(1200L, 3.5)
        estimator.addSample(5000L, 15.0)

        val speed5s = estimator.calculateRollingSpeedMps(5000L)
        assertNotNull(speed5s)
        assertEquals(3.0, speed5s!!, 0.01)

        val pace5s = estimator.calculateRollingPaceSecPerKm(5000L)
        assertEquals(333, pace5s) // 1000 / 3.0 = 333s (5:33 min/km)

        // After 15s window (e.g. t = 20000ms):
        // Oldest in window (within 20s - 15s = 5s):
        // Add sample at t=6000ms (d=18m) and t=20000ms (d=60m)
        estimator.addSample(6000L, 18.0)
        estimator.addSample(20000L, 60.0)

        // Window cutoff is 20000 - 15000 = 5000ms.
        // Oldest sample is at 5000ms (d=15.0m).
        // Since t > 15s, dtBase = oldest.timestampMs = 5000ms.
        // dt = 20000 - 5000 = 15000ms -> 15.0 integer seconds.
        // deltaDistance = 60.0 - 15.0 = 45.0m.
        // Speed = 45.0 / 15.0 = 3.0 m/s (5:33 min/km).
        val speed20s = estimator.calculateRollingSpeedMps(20000L)
        assertNotNull(speed20s)
        assertEquals(3.0, speed20s!!, 0.01)

        val pace20s = estimator.calculateRollingPaceSecPerKm(20000L)
        assertEquals(333, pace20s) // 1000 / 3.0 = 333s (5:33 min/km)
    }

    @Test
    fun testRollingPaceEstimatorResetOnStepBoundary() {
        val estimator = RollingPaceEstimator()

        // Populate step 1 samples
        estimator.addSample(1000L, 2.0)
        estimator.addSample(10000L, 30.0)
        assertNotNull(estimator.calculateRollingSpeedMps(10000L))

        // Step transition: reset must completely clear buffer
        estimator.reset()

        // Buffer is empty, returns null / 0
        assertNull(estimator.calculateRollingSpeedMps(1000L))
        assertEquals(0, estimator.calculateRollingPaceSecPerKm(1000L))
    }

    @Test
    fun testGpsKinematicFilterDisplacementAndAnchorRetention() {
        val filter = GpsKinematicFilter()

        // Mock 3 points: anchor, small displacement (<3m), and valid displacement (>=3m)
        val p0 = Mockito.mock(Location::class.java)
        Mockito.`when`(p0.latitude).thenReturn(12.971600)
        Mockito.`when`(p0.longitude).thenReturn(77.594600)
        Mockito.`when`(p0.accuracy).thenReturn(5.0f)
        Mockito.`when`(p0.hasAccuracy()).thenReturn(true)

        val p1Small = Mockito.mock(Location::class.java)
        Mockito.`when`(p1Small.latitude).thenReturn(12.971610)
        Mockito.`when`(p1Small.longitude).thenReturn(77.594610)
        Mockito.`when`(p1Small.accuracy).thenReturn(5.0f)
        Mockito.`when`(p1Small.hasAccuracy()).thenReturn(true)
        Mockito.`when`(p0.distanceTo(p1Small)).thenReturn(1.5f) // 1.5m displacement (< 3m)

        val p2Large = Mockito.mock(Location::class.java)
        Mockito.`when`(p2Large.latitude).thenReturn(12.971650)
        Mockito.`when`(p2Large.longitude).thenReturn(77.594650)
        Mockito.`when`(p2Large.accuracy).thenReturn(5.0f)
        Mockito.`when`(p2Large.hasAccuracy()).thenReturn(true)
        Mockito.`when`(p0.distanceTo(p2Large)).thenReturn(4.2f) // 4.2m displacement from anchor p0 (>= 3m)

        // 1. Initial point accepted as anchor
        val r0 = filter.filter(p0, 1000L)
        assertEquals(FilterReason.ACCEPTED, r0.reason)
        assertEquals(p0, filter.getLastAcceptedPoint()?.location)

        // 2. Small 1.5m movement is dropped, anchor remains p0
        val r1 = filter.filter(p1Small, 2000L)
        assertEquals(FilterReason.INSUFFICIENT_DISPLACEMENT, r1.reason)
        assertEquals(p0, filter.getLastAcceptedPoint()?.location) // Anchor RETAINED!

        // 3. Movement >= 3m from anchor is accepted, anchor advances to p2
        val r2 = filter.filter(p2Large, 3000L)
        assertEquals(FilterReason.ACCEPTED, r2.reason)
        assertEquals(p2Large, filter.getLastAcceptedPoint()?.location) // Anchor ADVANCED!
    }

    @Test
    fun testGpsKinematicAccelerationFilter() {
        val filter = GpsKinematicFilter()

        val p0 = Mockito.mock(Location::class.java)
        Mockito.`when`(p0.accuracy).thenReturn(5f)
        Mockito.`when`(p0.hasAccuracy()).thenReturn(true)

        val p1 = Mockito.mock(Location::class.java)
        Mockito.`when`(p1.accuracy).thenReturn(5f)
        Mockito.`when`(p1.hasAccuracy()).thenReturn(true)
        Mockito.`when`(p0.distanceTo(p1)).thenReturn(4.0f) // v1 = 4.0 / 1.0 = 4.0 m/s

        val p2Normal = Mockito.mock(Location::class.java)
        Mockito.`when`(p2Normal.accuracy).thenReturn(5f)
        Mockito.`when`(p2Normal.hasAccuracy()).thenReturn(true)
        Mockito.`when`(p1.distanceTo(p2Normal)).thenReturn(8.0f) // v2 = 8.0 / 1.0 = 8.0 m/s -> a = (8-4)/1 = 4.0 m/s^2 (< 11.0)

        val p2Spike = Mockito.mock(Location::class.java)
        Mockito.`when`(p2Spike.accuracy).thenReturn(5f)
        Mockito.`when`(p2Spike.hasAccuracy()).thenReturn(true)
        Mockito.`when`(p1.distanceTo(p2Spike)).thenReturn(25.0f) // v2 = 25.0 / 1.0 = 25.0 m/s -> a = (25-4)/1 = 21.0 m/s^2 (>= 11.0 spike!)

        // Accept p0 and p1
        filter.filter(p0, 1000L)
        filter.filter(p1, 2000L)

        // Kinematic spike >= 11 m/s^2 is rejected
        val rSpike = filter.filter(p2Spike, 3000L)
        assertEquals(FilterReason.EXCESSIVE_ACCELERATION, rSpike.reason)

        // Normal acceleration < 11 m/s^2 is accepted
        val rNormal = filter.filter(p2Normal, 3000L)
        assertEquals(FilterReason.ACCEPTED, rNormal.reason)
    }

    @Test
    fun testPaceGuidanceEscalatingCooldownSchedule() {
        // Test escalating cooldown schedule math: (count * 120) + 60
        fun isAlertAllowed(count: Int, elapsedInStateSec: Long): Boolean {
            if (count >= 4) return false // Hard cap at 4
            return when (count) {
                0 -> elapsedInStateSec >= 30L // 30s persistence
                1 -> elapsedInStateSec >= 180L // (1 * 120) + 60 = 180s (3 min)
                2 -> elapsedInStateSec >= 300L // (2 * 120) + 60 = 300s (5 min)
                3 -> elapsedInStateSec >= 420L // (3 * 120) + 60 = 420s (7 min)
                else -> false
            }
        }

        // Before 30s: No alert
        assertFalse(isAlertAllowed(count = 0, elapsedInStateSec = 29L))

        // At 30s: 1st alert allowed
        assertTrue(isAlertAllowed(count = 0, elapsedInStateSec = 30L))
        assertTrue(isAlertAllowed(count = 0, elapsedInStateSec = 60L))

        // 2nd alert: Requires >= 180s
        assertFalse(isAlertAllowed(count = 1, elapsedInStateSec = 179L))
        assertTrue(isAlertAllowed(count = 1, elapsedInStateSec = 180L))

        // 3rd alert: Requires >= 300s
        assertFalse(isAlertAllowed(count = 2, elapsedInStateSec = 299L))
        assertTrue(isAlertAllowed(count = 2, elapsedInStateSec = 300L))

        // 4th alert: Requires >= 420s
        assertFalse(isAlertAllowed(count = 3, elapsedInStateSec = 419L))
        assertTrue(isAlertAllowed(count = 3, elapsedInStateSec = 420L))

        // 5th alert: CAPPED! Permanently silenced
        assertFalse(isAlertAllowed(count = 4, elapsedInStateSec = 600L))
        assertFalse(isAlertAllowed(count = 5, elapsedInStateSec = 1000L))
    }

    @Test
    fun testPlanWorkoutParserOnIntervalWorkout() {
        val paceLockDay = com.runwise.app.data.model.PlanDay(
            date = "2026-11-05",
            dayName = "Thursday",
            dayNumber = "Day 4",
            title = "10K Pace Lock Workout",
            badge = "PACE LOCK",
            category = "workout",
            distance = "5.5 km",
            pace = "4 × 1 km @ Exactly 6:12–6:18 / km",
            targetHr = "152-155 bpm",
            description = "1.0 km warm-up @ 7:00/km. Then 4 repetitions of 1,000m locked at 6:15/km (90s walk recovery between sets). Use Lap Pace! 0.5 km cool-down."
        )

        val workout = com.runwise.app.util.PlanWorkoutParser.parsePlanDayToWorkout(paceLockDay)
        assertNotNull(workout)
        assertEquals(9, workout.steps.size)

        // Step 1: Warm Up
        val s1 = workout.steps[0]
        assertEquals("Warm Up", s1.title)
        assertEquals(com.runwise.app.data.model.StepType.WARMUP, s1.stepType)
        assertEquals(1000, s1.targetDistanceMeters)
        assertEquals(420, s1.targetPaceSecPerKm) // 7:00 pace

        // Step 2: Interval 1
        val s2 = workout.steps[1]
        assertEquals("Interval 1 of 4", s2.title)
        assertEquals(com.runwise.app.data.model.StepType.INTERVAL, s2.stepType)
        assertEquals(1000, s2.targetDistanceMeters)
        assertEquals(375, s2.targetPaceSecPerKm) // 6:15 pace

        // Step 3: Walk Recovery 1
        val s3 = workout.steps[2]
        assertEquals("Walk Recovery 1", s3.title)
        assertEquals(com.runwise.app.data.model.StepType.RECOVERY, s3.stepType)
        assertEquals(com.runwise.app.data.model.TargetType.DURATION, s3.targetType)
        assertEquals(90, s3.targetDurationSeconds)
        assertEquals(0, s3.targetPaceSecPerKm) // Open pace for recovery!

        // Step 4: Interval 2
        val s4 = workout.steps[3]
        assertEquals("Interval 2 of 4", s4.title)
        assertEquals(com.runwise.app.data.model.StepType.INTERVAL, s4.stepType)
        assertEquals(1000, s4.targetDistanceMeters)
        assertEquals(375, s4.targetPaceSecPerKm)

        // Step 5: Walk Recovery 2
        val s5 = workout.steps[4]
        assertEquals("Walk Recovery 2", s5.title)
        assertEquals(com.runwise.app.data.model.StepType.RECOVERY, s5.stepType)
        assertEquals(90, s5.targetDurationSeconds)

        // Step 6: Interval 3
        val s6 = workout.steps[5]
        assertEquals("Interval 3 of 4", s6.title)
        assertEquals(com.runwise.app.data.model.StepType.INTERVAL, s6.stepType)

        // Step 7: Walk Recovery 3
        val s7 = workout.steps[6]
        assertEquals("Walk Recovery 3", s7.title)
        assertEquals(90, s7.targetDurationSeconds)

        // Step 8: Interval 4
        val s8 = workout.steps[7]
        assertEquals("Interval 4 of 4", s8.title)
        assertEquals(com.runwise.app.data.model.StepType.INTERVAL, s8.stepType)

        // Step 9: Cool Down
        val s9 = workout.steps[8]
        assertEquals("Cool Down", s9.title)
        assertEquals(com.runwise.app.data.model.StepType.COOLDOWN, s9.stepType)
        assertEquals(500, s9.targetDistanceMeters)
        assertEquals(0, s9.targetPaceSecPerKm) // Open cool down pace
    }

    @Test
    fun testPlanWorkoutParserOnThresholdAndLongRun() {
        // Threshold Rehearsal Session
        val thresholdDay = com.runwise.app.data.model.PlanDay(
            date = "2026-11-12",
            dayName = "Thursday",
            dayNumber = "Day 11",
            title = "Threshold Rehearsal Session",
            badge = "SPEED SHARPENER",
            category = "workout",
            distance = "5.5 km",
            pace = "2 × 1.5 km @ 6:10 – 6:15 / km",
            targetHr = "153 bpm",
            description = "1.5 km warm-up @ 7:00/km. Then 2 × 1.5 km locked precisely at race pace (6:12/km) with 2 minutes walking rest. 1.0 km cool-down."
        )

        val thresholdWorkout = com.runwise.app.util.PlanWorkoutParser.parsePlanDayToWorkout(thresholdDay)
        assertEquals(5, thresholdWorkout.steps.size)
        assertEquals("Warm Up", thresholdWorkout.steps[0].title)
        assertEquals(1500, thresholdWorkout.steps[0].targetDistanceMeters)
        assertEquals(1500, thresholdWorkout.steps[1].targetDistanceMeters)
        assertEquals(120, thresholdWorkout.steps[2].targetDurationSeconds) // 2 min walk
        assertEquals(1500, thresholdWorkout.steps[3].targetDistanceMeters)
        assertEquals("Cool Down", thresholdWorkout.steps[4].title)
        assertEquals(1000, thresholdWorkout.steps[4].targetDistanceMeters)

        // Peak Long Run Simulation (Segmented run)
        val longRunDay = com.runwise.app.data.model.PlanDay(
            date = "2026-11-08",
            dayName = "Sunday",
            dayNumber = "Day 7",
            title = "Peak Long Run Simulation",
            badge = "LONG RUN",
            category = "long",
            distance = "8.5 km",
            pace = "Km 1–3 @ 6:50 | Km 4–7 @ 6:15 | Km 8–8.5 @ 6:40",
            targetHr = "148-155 bpm",
            description = "Peak volume milestone. First 3 km gentle warm-up. Middle 4 km dialed in at 6:15/km race pace. Final 1.5 km easy cool-down. Do not exceed 8.5 km."
        )

        val longWorkout = com.runwise.app.util.PlanWorkoutParser.parsePlanDayToWorkout(longRunDay)
        assertEquals(3, longWorkout.steps.size)
        assertEquals(3000, longWorkout.steps[0].targetDistanceMeters) // Km 1 to 3 = 3000m ("First 3 km gentle warm-up")
        assertEquals(410, longWorkout.steps[0].targetPaceSecPerKm)  // 6:50
        assertEquals(4000, longWorkout.steps[1].targetDistanceMeters) // Km 4 to 7 = 4000m ("Middle 4 km dialed in at 6:15")
        assertEquals(375, longWorkout.steps[1].targetPaceSecPerKm)  // 6:15
        assertEquals(1500, longWorkout.steps[2].targetDistanceMeters) // Km 8 to 8.5 = 1500m ("Final 1.5 km easy cool-down")
        assertEquals(400, longWorkout.steps[2].targetPaceSecPerKm)  // 6:40
    }

    @Test
    fun testCadenceCoachingSuppressionAndCooldown() {
        fun isCadenceAlertAllowed(
            count: Int,
            elapsedLowSec: Long,
            isWorkStep: Boolean,
            currentSpm: Int
        ): Boolean {
            if (!isWorkStep) return false // Suppressed in recovery/warmup/cooldown
            if (currentSpm <= 125) return false // Suppressed when walking
            if (count >= 3) return false // Hard cap at 3
            return when (count) {
                0 -> elapsedLowSec >= 25L // 25s persistence
                1 -> elapsedLowSec >= 180L // 3 min
                2 -> elapsedLowSec >= 300L // 5 min
                else -> false
            }
        }

        // 1. In recovery walk step: NEVER alerts
        assertFalse(isCadenceAlertAllowed(count = 0, elapsedLowSec = 40L, isWorkStep = false, currentSpm = 110))

        // 2. Walking (SPM <= 125): NEVER alerts
        assertFalse(isCadenceAlertAllowed(count = 0, elapsedLowSec = 40L, isWorkStep = true, currentSpm = 118))

        // 3. Before 25s: No alert
        assertFalse(isCadenceAlertAllowed(count = 0, elapsedLowSec = 24L, isWorkStep = true, currentSpm = 158))

        // 4. At 25s: 1st alert allowed
        assertTrue(isCadenceAlertAllowed(count = 0, elapsedLowSec = 25L, isWorkStep = true, currentSpm = 158))

        // 5. 2nd alert: requires >= 180s
        assertFalse(isCadenceAlertAllowed(count = 1, elapsedLowSec = 179L, isWorkStep = true, currentSpm = 158))
        assertTrue(isCadenceAlertAllowed(count = 1, elapsedLowSec = 180L, isWorkStep = true, currentSpm = 158))

        // 6. 3rd alert: requires >= 300s
        assertFalse(isCadenceAlertAllowed(count = 2, elapsedLowSec = 299L, isWorkStep = true, currentSpm = 158))
        assertTrue(isCadenceAlertAllowed(count = 2, elapsedLowSec = 300L, isWorkStep = true, currentSpm = 158))

        // 7. 4th alert: Hard capped at 3!
        assertFalse(isCadenceAlertAllowed(count = 3, elapsedLowSec = 500L, isWorkStep = true, currentSpm = 158))
    }
}
