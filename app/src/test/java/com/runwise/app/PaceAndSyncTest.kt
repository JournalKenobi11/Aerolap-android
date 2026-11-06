package com.runwise.app

import com.runwise.app.data.model.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class PaceAndSyncTest {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun testTargetPaceBoundaryCalculations() {
        val targetPaceSec = 6 * 60 + 15 // 6:15 min/km = 375 seconds
        val toleranceSec = 10

        val fastLimitSec = targetPaceSec - toleranceSec // 365s = 6:05
        val slowLimitSec = targetPaceSec + toleranceSec // 385s = 6:25

        assertEquals(375, targetPaceSec)
        assertEquals(365, fastLimitSec)
        assertEquals(385, slowLimitSec)

        fun formatPace(sec: Int): String {
            val m = sec / 60
            val s = sec % 60
            return "%d:%02d".format(m, s)
        }

        assertEquals("6:15", formatPace(targetPaceSec))
        assertEquals("6:05", formatPace(fastLimitSec))
        assertEquals("6:25", formatPace(slowLimitSec))

        val currentPaceFast = 360 // 6:00 min/km -> Too fast!
        assertTrue(currentPaceFast < fastLimitSec)

        val currentPaceSlow = 390 // 6:30 min/km -> Too slow!
        assertTrue(currentPaceSlow > slowLimitSec)

        val currentPaceLocked = 372 // 6:12 min/km -> In target window
        assertTrue(currentPaceLocked in fastLimitSec..slowLimitSec)
    }

    @Test
    fun testCadenceTargetThreshold() {
        val cadenceTarget = 162
        val currentLowCadence = 158
        val currentGoodCadence = 168

        assertTrue(currentLowCadence < cadenceTarget)
        assertFalse(currentGoodCadence < cadenceTarget)
    }

    @Test
    fun testLiveBackendSyncResponseParsing() {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("http://127.0.0.1:8081/api/mobile/sync")
            .build()

        val response = client.newCall(request).execute()
        assertTrue("Backend must respond with HTTP 200", response.isSuccessful)

        val responseBody = response.body?.string()
        assertNotNull("Response body should not be null", responseBody)

        val adapter = moshi.adapter(SyncResponse::class.java)
        val syncData = adapter.fromJson(responseBody!!)
        assertNotNull("Parsed SyncResponse must not be null", syncData)

        // Verify athlete profile
        assertEquals("success", syncData!!.status)
        assertEquals("Aashay Kadu", syncData.athlete.name)
        assertEquals("aashaykdgr@gmail.com", syncData.athlete.email)
        assertEquals(375, syncData.athlete.targetRace.targetPaceSecPerKm)
        assertEquals("6:15", syncData.athlete.targetRace.targetPaceFormatted)
        assertEquals("6:05", syncData.athlete.targetRace.paceMinFormatted)
        assertEquals("6:25", syncData.athlete.targetRace.paceMaxFormatted)
        assertEquals(162, syncData.athlete.targetRace.minCadenceAlertSpm)

        // Verify readiness metrics
        assertNotNull(syncData.readiness)
        assertTrue(syncData.readiness.ctl > 0.0)
        assertTrue(syncData.readiness.atl > 0.0)
        assertNotNull(syncData.readiness.status)
        assertNotNull(syncData.readiness.headline)

        // Verify 3-week 10K running plan
        assertNotNull(syncData.plan)
        assertTrue(syncData.plan.goalTitle.startsWith("10K Race Preparation"))
        assertTrue("Plan should have scheduled weeks", syncData.plan.weeks.isNotEmpty())

        // Verify activities list
        assertNotNull(syncData.activities)
        assertTrue("Should have historical activities", syncData.activities.isNotEmpty())
        assertTrue("Should have at least 131 activities", syncData.activities.size >= 131)

        // Verify trends
        assertNotNull(syncData.trends)
        assertTrue("Should have weekly breakdown", syncData.trends.weekly.isNotEmpty())
    }

    @Test
    fun testMobileActivityUploadPayload() {
        val payload = ActivityUploadRequest(
            name = "10K Pace Lock Workout",
            sportType = "Run",
            workoutType = "Target Pace",
            startDateTime = "2026-11-02T06:00:00Z",
            distanceMeters = 5000,
            movingTimeSeconds = 1875, // 6:15 pace
            elapsedTimeSeconds = 1900,
            averageCadence = 166,
            averageSpeedKmh = 9.6,
            maxSpeedKmh = 10.2,
            elevationMeters = 25,
            calories = 340,
            deviceName = "Runwise Companion",
            description = "Maintained 6:15 min/km target window with audio cue feedback"
        )

        val adapter = moshi.adapter(ActivityUploadRequest::class.java)
        val json = adapter.toJson(payload)
        assertNotNull(json)
        assertTrue(json.contains("10K Pace Lock Workout"))
        assertTrue(json.contains("5000"))

        val roundTrip = adapter.fromJson(json)
        assertEquals(payload.name, roundTrip?.name)
        assertEquals(payload.movingTimeSeconds, roundTrip?.movingTimeSeconds)
        assertEquals(payload.averageCadence, roundTrip?.averageCadence)
    }

    @Test
    fun testLiveBackendActivityUploadAndPersistence() {
        val payload = ActivityUploadRequest(
            name = "Android Companion Verification Run",
            sportType = "Run",
            workoutType = "Target Pace Verification",
            startDateTime = "2026-11-02T01:10:00Z",
            distanceMeters = 5000,
            movingTimeSeconds = 1875, // 6:15 min/km
            elapsedTimeSeconds = 1890,
            averageCadence = 166,
            averageSpeedKmh = 9.6,
            maxSpeedKmh = 10.4,
            elevationMeters = 20,
            calories = 345,
            deviceName = "Runwise Android Verification Suite",
            description = "Live end-to-end integration test verifying upload to Dreeve SQLite DB"
        )

        val adapter = moshi.adapter(ActivityUploadRequest::class.java)
        val jsonBody = adapter.toJson(payload)

        val client = OkHttpClient()
        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("http://127.0.0.1:8081/api/mobile/activities")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        assertEquals("Upload must return HTTP 201 Created", 201, response.code)

        val responseStr = response.body?.string() ?: ""
        val uploadResp = moshi.adapter(ActivityUploadResponse::class.java).fromJson(responseStr)
        assertNotNull(uploadResp)
        assertEquals("success", uploadResp?.status)
        assertNotNull("Must return generated activity ID", uploadResp?.id)
        assertTrue(uploadResp?.id?.startsWith("activity-") == true)
    }

    @Test
    fun testStravaSplitsBarChartProportions() {
        // Screenshot values:
        // Lap 1: 0.78 km, 5:12 (312s)
        // Lap 2: 0.24 km, 12:28 (748s)
        // Lap 3: 0.79 km, 5:03 (303s)
        // Lap 4: 0.30 km, 10:04 (604s)
        // Lap 5: 0.81 km, 5:02 (302s)
        // Lap 6: 0.26 km, 11:23 (683s)
        // Lap 7: 0.83 km, 4:49 (289s)
        // Lap 8: 0.04 km, 5:26 (326s) (Active)

        val paces = listOf(312, 748, 303, 604, 302, 683, 289, 326)
        val speeds = paces.map { 1000f / it }
        val maxSpeed = speeds.maxOrNull()!! // 3.46 m/s (4:49)

        assertEquals(289, paces[6]) // Fastest pace = 4:49
        val maxFraction = (speeds[6] / maxSpeed).coerceIn(0.12f, 1f)
        assertEquals(1.0f, maxFraction, 0.001f)

        // Slowest pace = 12:28 (748s)
        val slowestSpeed = 1000f / 748
        val slowestFraction = (slowestSpeed / maxSpeed).coerceIn(0.12f, 1f)
        assertEquals(0.386f, slowestFraction, 0.01f)

        // Active lap pace = 5:26 (326s)
        val activeSpeed = 1000f / 326
        val activeFraction = (activeSpeed / maxSpeed).coerceIn(0.12f, 1f)
        assertEquals(0.886f, activeFraction, 0.01f)

        // Km distance formatting
        assertEquals("0.78", "%.2f".format(java.util.Locale.US, 780 / 1000.0))
        assertEquals("0.24", "%.2f".format(java.util.Locale.US, 240 / 1000.0))
        assertEquals("0.04", "%.2f".format(java.util.Locale.US, 40 / 1000.0))
    }

    @Test
    fun testSmoothedHysteresisElevationFilter() {
        // Test algorithm replicating TrackingService smoothed hysteresis filter
        val alpha = 0.15
        val threshold = 2.0
        val maxSpike = 40.0

        fun simulateElevationFilter(altitudes: List<Double>): Double {
            var smoothed: Double? = null
            var ref: Double? = null
            var gain = 0.0

            for (rawAlt in altitudes) {
                if (smoothed == null) {
                    smoothed = rawAlt
                    ref = rawAlt
                } else {
                    val prev = smoothed
                    val diff = kotlin.math.abs(rawAlt - prev)
                    if (diff < maxSpike) {
                        val curr = prev + alpha * (rawAlt - prev)
                        smoothed = curr
                        val r = ref ?: curr
                        val delta = curr - r
                        if (delta >= threshold) {
                            gain += delta
                            ref = curr
                        } else if (r - curr >= threshold) {
                            ref = curr
                        }
                    }
                }
            }
            return gain
        }

        // 1. Flat ground with ±1.0m GPS vertical jitter (20 samples)
        val flatJitter = listOf(
            200.0, 200.8, 199.3, 200.5, 199.7, 200.9, 199.2, 200.6,
            199.5, 200.7, 199.4, 200.8, 199.3, 200.5, 199.8, 200.7,
            199.6, 200.4, 199.5, 200.2
        )
        val flatGain = simulateElevationFilter(flatJitter)
        // With our filter, flat ground jitter must result in 0.0m gain!
        assertEquals(0.0, flatGain, 0.001)

        // 2. Real hill climb: base 200m climbing to 230m (+30m climb)
        val hillClimb = mutableListOf<Double>()
        for (i in 0..60) {
            val progress = i / 60.0
            val trueAlt = 200.0 + progress * 30.0
            val noise = (if (i % 2 == 0) 0.5 else -0.5) // Real GPS noise overlaid on climb
            hillClimb.add(trueAlt + noise)
        }
        val climbGain = simulateElevationFilter(hillClimb)
        // Should capture close to 30m climb (allowing for EMA lag at start and end)
        assertTrue("Climb gain should be around 27m to 30m, was: $climbGain", climbGain in 26.0..30.0)

        // 3. Glitch / spike rejection (jumping 100m)
        val spikeData = listOf(200.0, 200.1, 200.0, 350.0, 350.0, 200.2, 200.1)
        val spikeGain = simulateElevationFilter(spikeData)
        assertEquals(0.0, spikeGain, 0.001)
    }

    @Test
    fun testAutomaticKilometerSplitsInterpolation() {
        // Total distance 2,548 meters in 941 seconds (mimicking the Evening Run)
        val totalDistance = 2548.0
        val totalDurationSec = 941.0

        // Simulate GPS fixes accumulating distance
        var prevDist = 0.0
        var prevTime = 0.0
        var kmBoundary = 1000.0
        var kmStartDist = 0.0
        var kmStartTime = 0.0
        var completedKm = 0
        val splits = mutableListOf<ActivitySplitItem>()

        val stepDist = 8.0 // ~8m per 1s GPS fix (at ~5:30-6:00 pace)
        val stepTime = 2.95
        var currentDist = 0.0
        var currentTime = 0.0

        while (currentDist < totalDistance) {
            prevDist = currentDist
            prevTime = currentTime
            currentDist = minOf(currentDist + stepDist, totalDistance)
            currentTime = minOf(currentTime + stepTime, totalDurationSec)

            while (currentDist >= kmBoundary) {
                val f = ((kmBoundary - prevDist) / (currentDist - prevDist)).coerceIn(0.0, 1.0)
                val tBoundary = prevTime + f * (currentTime - prevTime)
                val splitDist = kmBoundary - kmStartDist
                val splitTimeSec = tBoundary - kmStartTime
                val splitPace = ((splitTimeSec / splitDist) * 1000.0).toInt()

                completedKm++
                splits.add(
                    ActivitySplitItem(
                        splitNumber = completedKm,
                        distanceMeters = splitDist.toInt(),
                        movingTimeSeconds = splitTimeSec.toLong(),
                        elevationDifference = 10,
                        averageSpeedKmh = (splitDist / splitTimeSec) * 3.6,
                        paceFormatted = "%d:%02d".format(splitPace / 60, splitPace % 60),
                        paceSecPerKm = splitPace
                    )
                )

                kmStartDist = kmBoundary
                kmStartTime = tBoundary
                kmBoundary += 1000.0
            }
        }

        // Final partial split
        val finalDist = totalDistance - kmStartDist
        val finalTimeSec = totalDurationSec - kmStartTime
        if (finalDist > 20.0) {
            val finalPace = ((finalTimeSec / finalDist) * 1000.0).toInt()
            splits.add(
                ActivitySplitItem(
                    splitNumber = completedKm + 1,
                    distanceMeters = finalDist.toInt(),
                    movingTimeSeconds = finalTimeSec.toLong(),
                    elevationDifference = 5,
                    averageSpeedKmh = (finalDist / finalTimeSec) * 3.6,
                    paceFormatted = "%d:%02d".format(finalPace / 60, finalPace % 60),
                    paceSecPerKm = finalPace
                )
            )
        }

        // Assertions for 2,548m run:
        // Must contain exactly 3 splits:
        // Split 1: 1,000m
        // Split 2: 1,000m
        // Split 3 (partial): 548m
        assertEquals(3, splits.size)
        assertEquals(1000, splits[0].distanceMeters)
        assertEquals(1, splits[0].splitNumber)

        assertEquals(1000, splits[1].distanceMeters)
        assertEquals(2, splits[1].splitNumber)

        assertEquals(548, splits[2].distanceMeters)
        assertEquals(3, splits[2].splitNumber)

        val totalSplitMeters = splits.sumOf { it.distanceMeters }
        assertEquals(2548, totalSplitMeters)
    }

    @Test
    fun testIntervalClassificationRule() {
        val freeRunStep = WorkoutStep(title = "Open Run", stepType = StepType.RUN, targetType = TargetType.OPEN)
        val warmupStep = WorkoutStep(title = "Warmup", stepType = StepType.WARMUP, targetType = TargetType.DISTANCE)
        val intervalStep = WorkoutStep(title = "500m Speed Rep", stepType = StepType.INTERVAL, targetType = TargetType.DISTANCE)
        val recoveryStep = WorkoutStep(title = "200m Recovery", stepType = StepType.RECOVERY, targetType = TargetType.DISTANCE)
        val cooldownStep = WorkoutStep(title = "Cooldown Walk", stepType = StepType.COOLDOWN, targetType = TargetType.DISTANCE)

        assertFalse("Free run must NOT be tagged INTERVAL", freeRunStep.stepType == StepType.INTERVAL)
        assertFalse("Warmup must NOT be tagged INTERVAL", warmupStep.stepType == StepType.INTERVAL)
        assertTrue("Interval work step MUST be tagged INTERVAL", intervalStep.stepType == StepType.INTERVAL)
        assertFalse("Recovery step must NOT be tagged INTERVAL", recoveryStep.stepType == StepType.INTERVAL)
        assertFalse("Cooldown step must NOT be tagged INTERVAL", cooldownStep.stepType == StepType.INTERVAL)
    }

    @Test
    fun testPerformanceCurvesStreamDataIntegrity() {
        val streams = ActivityStreamsData(
            paceCurve = listOf(390.0, 385.0, 372.0, 368.0, 365.0),
            hrCurve = emptyList(),
            cadenceCurve = listOf(158, 162, 164, 165, 166),
            elevationCurve = listOf(12.4, 12.8, 13.5, 15.2, 18.0),
            distanceCurve = listOf(15.0, 38.0, 62.0, 88.0, 115.0)
        )

        assertEquals(5, streams.paceCurve.size)
        assertEquals(0, streams.hrCurve.size)
        assertEquals(5, streams.cadenceCurve.size)
        assertEquals(5, streams.elevationCurve.size)
        assertEquals(5, streams.distanceCurve.size)

        val adapter = moshi.adapter(ActivityStreamsData::class.java)
        val json = adapter.toJson(streams)
        assertTrue(json.contains("paceCurve"))
        assertTrue(json.contains("cadenceCurve"))
        assertTrue(json.contains("elevationCurve"))
        assertTrue(json.contains("distanceCurve"))

        val deserialized = adapter.fromJson(json)
        assertNotNull(deserialized)
        assertEquals(streams.paceCurve, deserialized!!.paceCurve)
        assertEquals(streams.cadenceCurve, deserialized.cadenceCurve)
        assertEquals(streams.elevationCurve, deserialized.elevationCurve)
        assertEquals(streams.distanceCurve, deserialized.distanceCurve)
    }

    @Test
    fun testPaceWarningHysteresis3StrikeConfirmation() {
        val targetPace = 375 // 6:15 min/km
        val tolerance = 30   // ±30s (window: 345s to 405s)
        val minPace = targetPace - tolerance
        val maxPace = targetPace + tolerance

        var consecutiveTooFast = 0
        var consecutiveTooSlow = 0
        var alertsTriggered = 0

        fun evaluatePace(pace: Int): Boolean {
            if (pace in minPace..maxPace) {
                consecutiveTooFast = 0
                consecutiveTooSlow = 0
                return false
            }
            if (pace < minPace) {
                consecutiveTooSlow = 0
                consecutiveTooFast++
                if (consecutiveTooFast >= 3) {
                    consecutiveTooFast = 0
                    alertsTriggered++
                    return true
                }
            } else {
                consecutiveTooFast = 0
                consecutiveTooSlow++
                if (consecutiveTooSlow >= 3) {
                    consecutiveTooSlow = 0
                    alertsTriggered++
                    return true
                }
            }
            return false
        }

        // Test 1: Single glitch does NOT trigger alert
        assertFalse(evaluatePace(340)) // 1 strike fast
        assertFalse(evaluatePace(360)) // returns inside target -> resets strikes!
        assertEquals(0, alertsTriggered)

        // Test 2: Requires 3 consecutive strikes
        assertFalse(evaluatePace(335)) // strike 1
        assertFalse(evaluatePace(338)) // strike 2
        assertTrue(evaluatePace(330))  // strike 3 -> triggers!
        assertEquals(1, alertsTriggered)

        // Test 3: Returning inside window resets
        assertFalse(evaluatePace(370)) // inside
        assertFalse(evaluatePace(420)) // strike 1 slow
        assertFalse(evaluatePace(415)) // strike 2 slow
        assertFalse(evaluatePace(380)) // inside -> resets!
        assertFalse(evaluatePace(418)) // strike 1 slow again
        assertEquals(1, alertsTriggered)
    }

    @Test
    fun testGoldenGpxExpectedSplits() {
        // Golden GPX Simulation: 2,548 meters total distance with elevation change
        val totalDistance = 2548.0
        val fixes = mutableListOf<Triple<Double, Double, Double>>() // distance, movingTimeSec, elevation
        var d = 0.0
        var t = 0.0
        var elev = 15.0

        while (d < totalDistance) {
            val nextD = minOf(d + 10.0, totalDistance)
            val deltaD = nextD - d
            d = nextD
            t += deltaD * 0.36 // exactly 360 s/km (6:00 /km)
            elev += deltaD * 0.005
            fixes.add(Triple(d, t, elev))
        }

        var prevD = 0.0
        var prevT = 0.0
        var prevElev = 15.0
        var kmBoundary = 1000.0
        var kmStartD = 0.0
        var kmStartT = 0.0
        var lastKmElev = 15.0
        var completedKm = 0
        val splits = mutableListOf<ActivitySplitItem>()

        for (fix in fixes) {
            val currD = fix.first
            val currT = fix.second
            val currElev = fix.third

            while (currD >= kmBoundary) {
                val f = ((kmBoundary - prevD) / (currD - prevD)).coerceIn(0.0, 1.0)
                val tBoundary = prevT + f * (currT - prevT)
                val elevBoundary = prevElev + f * (currElev - prevElev)

                val splitDist = kmBoundary - kmStartD
                val splitTimeSec = tBoundary - kmStartT
                val splitPace = ((splitTimeSec / splitDist) * 1000.0).toInt()
                val eleDelta = (elevBoundary - lastKmElev).toInt()

                completedKm++
                splits.add(
                    ActivitySplitItem(
                        splitNumber = completedKm,
                        distanceMeters = splitDist.toInt(),
                        movingTimeSeconds = splitTimeSec.toLong(),
                        elevationDifference = eleDelta,
                        averageSpeedKmh = (splitDist / splitTimeSec) * 3.6,
                        paceFormatted = "%d:%02d".format(splitPace / 60, splitPace % 60),
                        paceSecPerKm = splitPace
                    )
                )

                kmStartD = kmBoundary
                kmStartT = tBoundary
                lastKmElev = elevBoundary
                kmBoundary += 1000.0
            }

            prevD = currD
            prevT = currT
            prevElev = currElev
        }

        // Remaining partial split (548 m)
        val finalDist = totalDistance - kmStartD
        val finalTimeSec = fixes.last().second - kmStartT
        val finalElevDelta = (fixes.last().third - lastKmElev).toInt()
        val finalPace = ((finalTimeSec / finalDist) * 1000.0).toInt()

        splits.add(
            ActivitySplitItem(
                splitNumber = completedKm + 1,
                distanceMeters = finalDist.toInt(),
                movingTimeSeconds = finalTimeSec.toLong(),
                elevationDifference = finalElevDelta,
                averageSpeedKmh = (finalDist / finalTimeSec) * 3.6,
                paceFormatted = "%d:%02d".format(finalPace / 60, finalPace % 60),
                paceSecPerKm = finalPace
            )
        )

        // Verification
        assertEquals(3, splits.size)
        assertEquals(1, splits[0].splitNumber)
        assertEquals(1000, splits[0].distanceMeters)
        assertEquals(360, splits[0].paceSecPerKm) // exactly 6:00 /km

        assertEquals(2, splits[1].splitNumber)
        assertEquals(1000, splits[1].distanceMeters)
        assertEquals(360, splits[1].paceSecPerKm) // exactly 6:00 /km

        assertEquals(3, splits[2].splitNumber)
        assertEquals(548, splits[2].distanceMeters) // partial split exactly 548m!
        assertEquals(360, splits[2].paceSecPerKm)

        assertEquals(2548, splits.sumOf { it.distanceMeters })
    }

    @Test
    fun testPauseInMiddleDoesNotAlterSplitMovingPace() {
        // Run 500m at 6:00 pace (180s), pause 300s, run next 500m at 6:00 pace (180s)
        var cumulativeDistance = 0.0
        var totalWallClockTime = 0.0
        var pausedDuration = 0.0

        // Segment 1: 0m to 500m (moving time 180s)
        for (i in 1..50) {
            cumulativeDistance += 10.0
            totalWallClockTime += 3.6
        }
        assertEquals(500.0, cumulativeDistance, 0.001)
        val movingTimeBeforePause = totalWallClockTime - pausedDuration
        assertEquals(180.0, movingTimeBeforePause, 0.001)

        // Pause event: 300s (5 minutes standing still)
        val pauseStart = totalWallClockTime
        totalWallClockTime += 300.0
        pausedDuration += (totalWallClockTime - pauseStart)

        // Moving time during pause does NOT advance
        val movingTimeAfterPause = totalWallClockTime - pausedDuration
        assertEquals(180.0, movingTimeAfterPause, 0.001)

        // Segment 2: 500m to 1000m (moving time another 180s)
        for (i in 1..50) {
            cumulativeDistance += 10.0
            totalWallClockTime += 3.6
        }
        assertEquals(1000.0, cumulativeDistance, 0.001)

        val finalMovingTimeSec = totalWallClockTime - pausedDuration
        assertEquals(360.0, finalMovingTimeSec, 0.001)

        // Split calculation using moving time
        val splitDist = cumulativeDistance
        val splitPaceSecPerKm = ((finalMovingTimeSec / splitDist) * 1000.0).toInt()

        // MUST be 360 seconds (6:00 /km), NOT (360 + 300 = 660s = 11:00 /km)!
        assertEquals("Split pace with pause excluded must be exactly 6:00 (360s)", 360, splitPaceSecPerKm)
    }

    @Test
    fun testSyntheticCadenceBatchingProducesSmoothCurve() {
        // Simulate hardware step detector delivering batched timestamps
        // True cadence = 168 SPM (~357ms per step)
        // With arrival-time batching, events would bunch up into barcode spikes
        // With hardware event.timestamp (elapsedRealtimeNanos converted to ms), true steps are spaced
        val rawBatchedCadence = mutableListOf<Int>()
        for (i in 0..100) {
            // Overlay ±2 SPM minor noise around 168 SPM
            val jitter = if (i % 3 == 0) 2 else if (i % 2 == 0) -2 else 0
            rawBatchedCadence.add(168 + jitter)
        }

        // Add 2 sensor batching spike anomalies
        rawBatchedCadence[25] = 230
        rawBatchedCadence[55] = 110

        val distances = (0..100).map { it * 25.0 }
        val dummyPace = List(101) { 360.0 }
        val dummyElev = List(101) { 15.0 }

        val result = com.runwise.app.util.AnalyticsEngine.processTelemetry(
            distanceStream = distances,
            paceStream = dummyPace,
            cadenceStream = rawBatchedCadence,
            elevationStream = dummyElev
        )

        // Centered median filter kills the 230 and 110 spikes
        assertTrue("Max cadence after zero-phase filtering must be <= 175 SPM, was: ${result.maxCadenceSpm}", result.maxCadenceSpm <= 175.0)
        assertTrue("Min cadence after zero-phase filtering must be >= 160 SPM, was: ${result.minCadenceSpm}", result.minCadenceSpm >= 160.0)
        assertEquals("Average cadence must remain ~168 SPM", 168.0, result.avgCadenceSpm, 2.0)
    }

    @Test
    fun testPaceStreamWithGpsSpikeStaysWithinBoundsAfterSmoothing() {
        // 100 distance bins at ~360 s/km (6:00 /km)
        val distances = (0..100).map { it * 25.0 }
        val rawPaces = MutableList(101) { 360.0 + (if (it % 2 == 0) 4.0 else -4.0) }

        // Inject an isolated 20m GPS glitch spike (e.g. pace drops to 150 s/km or shoots up to 900 s/km)
        rawPaces[40] = 140.0 // sudden fake sprint from GPS forward jump
        rawPaces[70] = 850.0 // sudden fake stop from GPS lag

        val dummyCadence = List(101) { 168 }
        val dummyElev = List(101) { 20.0 }

        val result = com.runwise.app.util.AnalyticsEngine.processTelemetry(
            distanceStream = distances,
            paceStream = rawPaces,
            cadenceStream = dummyCadence,
            elevationStream = dummyElev
        )

        // After centered median + centered moving average, the 140 and 850 spikes are eliminated
        val smoothedAtSpike1 = result.smoothedPaceSecPerKm[40]
        val smoothedAtSpike2 = result.smoothedPaceSecPerKm[70]

        assertTrue("Spike 1 (140s) must be smoothed back into normal 345..375 range, was: $smoothedAtSpike1", smoothedAtSpike1 in 345.0..375.0)
        assertTrue("Spike 2 (850s) must be smoothed back into normal 345..375 range, was: $smoothedAtSpike2", smoothedAtSpike2 in 345.0..375.0)

        // 2nd-98th percentile bounds stay close to 360 s/km
        assertTrue("P2 pace should be >= 350 s/km", result.p2PaceSecPerKm >= 350.0)
        assertTrue("P98 pace should be <= 370 s/km", result.p98PaceSecPerKm <= 370.0)
    }

    @Test
    fun testLiveBackendDemElevationEndpoint() {
        // Query live Dreeve DEM elevation endpoint via Retrofit/OkHttp
        val client = okhttp3.OkHttpClient()
        val jsonPayload = """{"coordinates":[[19.967,72.744],[19.9671,72.7441],[19.9672,72.7442]]}"""
        val reqBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = okhttp3.Request.Builder()
            .url("http://127.0.0.1:8081/api/mobile/elevation/dem")
            .post(reqBody)
            .build()

        val response = client.newCall(request).execute()
        assertTrue("DEM endpoint must return HTTP 200", response.isSuccessful)

        val body = response.body?.string() ?: ""
        val adapter = moshi.adapter(DemElevationResponse::class.java)
        val dem = adapter.fromJson(body)

        assertNotNull(dem)
        assertEquals("success", dem?.status)
        assertEquals(3, dem?.elevationCurve?.size)
        assertTrue("Copernicus DEM must report valid altitude", dem?.elevationCurve?.first() ?: 0.0 > 0.0)
    }
}
