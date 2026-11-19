package com.runwise.app

import com.runwise.app.service.engine.LivePaceFilter
import org.junit.Assert.*
import org.junit.Test

class LivePaceFilterTest {

    @Test
    fun testSawtoothCancellation() {
        val filter = LivePaceFilter(alpha = 0.22)
        var now = 1000L

        // 2-second alternating sawtooth: 1.9 m/s, 3.4 m/s (average is 2.65 m/s = 377 s/km = 6:17 /km)
        // Feed 10 alternating samples
        var lastPace: Double? = null
        for (i in 0 until 10) {
            val speed = if (i % 2 == 0) 1.9 else 3.4
            val (pace, _) = filter.update(speed, now)
            if (pace != null) {
                lastPace = pace
            }
            now += 1000L
        }

        assertNotNull("Pace should be available after 4 samples", lastPace)
        // Average speed is 2.65 m/s -> pace is 1000 / 2.65 = 377.35s
        // Filtered pace should converge within ±15s of 377s, NOT oscillating between 294s and 526s!
        assertEquals(377.0, lastPace!!, 20.0)
    }

    @Test
    fun testGapResetMutesFor15Seconds() {
        val filter = LivePaceFilter(alpha = 0.22)

        // Warm up with 4 steady samples
        var now = 1000L
        for (i in 0 until 4) {
            filter.update(2.5, now)
            now += 1000L
        }
        assertFalse(filter.isMuted(now))

        // Jump 4 seconds (> 3000ms gap)
        now += 4000L
        val (pace, isMuted) = filter.update(2.5, now)
        assertTrue("Filter should be muted immediately upon gap detection", isMuted)
        assertNull("Pace should be null during gap reset flush", pace)
        assertTrue("isMuted() should return true for 15s post-gap", filter.isMuted(now + 10_000L))
        assertFalse("isMuted() should expire after 15s", filter.isMuted(now + 16_000L))
    }

    @Test
    fun testPhysicalClampsRejectSpikesAndStops() {
        val filter = LivePaceFilter(alpha = 0.22)
        var now = 1000L

        // Feed steady 2.5 m/s (6:40 /km)
        for (i in 0 until 4) {
            filter.update(2.5, now)
            now += 1000L
        }
        val baselinePace = filter.getFilteredPaceSecPerKm()
        assertNotNull(baselinePace)
        assertEquals(400, baselinePace!!) // 1000 / 2.5 = 400 s/km

        // 1. GPS rubber-banding spike: 9.5 m/s (1:45 /km)
        now += 1000L
        val (spikePace, _) = filter.update(9.5, now)
        // Spike should be clamped: pace should NOT drop to 1:45
        assertEquals(400, spikePace!!.toInt())

        // 2. Standing still: 0.2 m/s (< 0.8 m/s clamp)
        now += 1000L
        val (stopPace, _) = filter.update(0.2, now)
        // Stop should be clamped: pace should NOT explode to infinity
        assertEquals(400, stopPace!!.toInt())
    }

    @Test
    fun testResetClearsState() {
        val filter = LivePaceFilter(alpha = 0.22)
        var now = 1000L
        for (i in 0 until 5) {
            filter.update(2.6, now)
            now += 1000L
        }
        assertNotNull(filter.getFilteredPaceSecPerKm())

        filter.reset()
        assertNull("Filter pace should be null after reset()", filter.getFilteredPaceSecPerKm())
        assertFalse(filter.isMuted(now))
    }
}
