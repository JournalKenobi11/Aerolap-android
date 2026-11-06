package com.runwise.app.util

import com.runwise.app.data.model.CustomWorkout
import com.runwise.app.data.model.PlanDay
import com.runwise.app.data.model.StepType
import com.runwise.app.data.model.TargetType
import com.runwise.app.data.model.WorkoutStep

object PlanWorkoutParser {

    /**
     * Parses any PlanDay into a fully structured, multi-step CustomWorkout.
     * Accurately breaks down:
     * - Interval workouts (warm-up, repetition work steps, walk recoveries, cool-down)
     * - Segmented progressive long runs (e.g. Km 1-3 @ 6:50 | Km 4-7 @ 6:15 | Km 8-8.5 @ 6:40)
     * - Plus-delimited tune-up runs (e.g. 1.5km easy + 1.0km @ 6:15 + 1.0km easy)
     * - Standard base and recovery runs
     */
    fun parsePlanDayToWorkout(day: PlanDay): CustomWorkout {
        val title = day.title
        val desc = day.description
        val paceStr = day.pace
        val distStr = day.distance

        // 1. Try Interval Pattern (e.g. "4 repetitions of 1,000m locked at 6:15/km (90s walk recovery between sets)")
        val intervalWorkout = tryParseIntervalWorkout(day)
        if (intervalWorkout != null && intervalWorkout.steps.size > 1) {
            return intervalWorkout
        }

        // 2. Try Segmented Long Run (e.g. "Km 1–3 @ 6:50 | Km 4–7 @ 6:15 | Km 8–8.5 @ 6:40")
        val segmentedWorkout = tryParseSegmentedWorkout(day)
        if (segmentedWorkout != null && segmentedWorkout.steps.size > 1) {
            return segmentedWorkout
        }

        // 3. Try Plus-Delimited Tune-Up (e.g. "1.5km easy + 1.0km @ 6:15 + 1.0km easy")
        val compoundWorkout = tryParseCompoundWorkout(day)
        if (compoundWorkout != null && compoundWorkout.steps.size > 1) {
            return compoundWorkout
        }

        // 4. Fallback: Single-Step Run with accurate pace and distance
        val totalMeters = parseDistanceToMeters(distStr).takeIf { it > 0 } ?: 5000
        val targetPaceSec = parsePaceToSeconds(paceStr)
        val isWalk = day.category.equals("walk", ignoreCase = true)

        val step = WorkoutStep(
            title = title,
            stepType = if (isWalk) StepType.RUN else if (day.badge.contains("PACE LOCK", ignoreCase = true)) StepType.INTERVAL else StepType.RUN,
            targetType = TargetType.DISTANCE,
            targetDistanceMeters = totalMeters,
            targetPaceSecPerKm = if (isWalk) 0 else targetPaceSec,
            targetCadenceSpm = if (isWalk) 115 else 168
        )

        return CustomWorkout(
            name = "${day.dayName} - $title",
            description = desc,
            steps = listOf(step)
        )
    }

    /**
     * Parses interval sets like:
     * "1.0 km warm-up @ 7:00/km. Then 4 repetitions of 1,000m locked at 6:15/km (90s walk recovery between sets). Use Lap Pace! 0.5 km cool-down."
     */
    private fun tryParseIntervalWorkout(day: PlanDay): CustomWorkout? {
        val text = "${day.pace} ${day.description}"

        // Check for repetitions: "4 repetitions of 1,000m" or "4 × 1 km" or "2 × 1.5 km"
        val repsMatch = Regex("""(\d+)\s*(?:repetitions|reps|times|[x×])\s*(?:of\s*)?([0-9.,]+)\s*(?:km|k|m|meters|1,000m)?""", RegexOption.IGNORE_CASE).find(text)
            ?: return null

        val repeatCount = repsMatch.groupValues[1].toIntOrNull() ?: return null
        if (repeatCount <= 1) return null

        val repDistRaw = repsMatch.groupValues[2].replace(",", "")
        val repDistMeters = if (repDistRaw.toDoubleOrNull() != null) {
            val d = repDistRaw.toDouble()
            if (d <= 10.0) (d * 1000).toInt() else d.toInt()
        } else 1000

        // Parse rep target pace: e.g. "at 6:15/km" or "@ Exactly 6:12–6:18" or "@ 6:10 – 6:15"
        val repPaceSec = parsePaceToSeconds(text, defaultSec = 375)

        // Parse warm-up: e.g. "1.0 km warm-up @ 7:00/km" or "1.5 km warm-up @ 7:00/km"
        val warmupMatch = Regex("""([0-9.,]+)\s*(?:km|k|m)?\s*warm-?up(?:\s*@\s*([0-9]+:[0-9]+))?""", RegexOption.IGNORE_CASE).find(text)
        val warmupDistMeters = warmupMatch?.let {
            val d = it.groupValues[1].replace(",", "").toDoubleOrNull() ?: 1.0
            if (d <= 10.0) (d * 1000).toInt() else d.toInt()
        } ?: 1000

        val warmupPaceSec = warmupMatch?.groupValues?.getOrNull(2)?.let { parsePaceToSeconds(it) }?.takeIf { it > 0 } ?: 420 // default 7:00

        // Parse walk recovery: e.g. "90s walk recovery" or "2 minutes walking rest"
        val recoverySec = if (text.contains("2 minutes", ignoreCase = true)) {
            120
        } else if (text.contains("90s", ignoreCase = true) || text.contains("90 sec", ignoreCase = true)) {
            90
        } else if (text.contains("60s", ignoreCase = true) || text.contains("1 minute", ignoreCase = true)) {
            60
        } else {
            90
        }

        // Parse cool-down: e.g. "0.5 km cool-down" or "1.0 km cool-down"
        val cooldownMatch = Regex("""([0-9.,]+)\s*(?:km|k|m)?\s*cool-?down""", RegexOption.IGNORE_CASE).find(text)
        val cooldownDistMeters = cooldownMatch?.let {
            val d = it.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.5
            if (d <= 10.0) (d * 1000).toInt() else d.toInt()
        } ?: 500

        val steps = mutableListOf<WorkoutStep>()

        // 1. Warm-Up Step
        if (warmupDistMeters > 0) {
            steps.add(
                WorkoutStep(
                    title = "Warm Up",
                    stepType = StepType.WARMUP,
                    targetType = TargetType.DISTANCE,
                    targetDistanceMeters = warmupDistMeters,
                    targetPaceSecPerKm = warmupPaceSec,
                    targetCadenceSpm = 164
                )
            )
        }

        // 2. Interval Repetitions & Recoveries
        for (i in 1..repeatCount) {
            steps.add(
                WorkoutStep(
                    title = "Interval $i of $repeatCount",
                    stepType = StepType.INTERVAL,
                    targetType = TargetType.DISTANCE,
                    targetDistanceMeters = repDistMeters,
                    targetPaceSecPerKm = repPaceSec,
                    targetCadenceSpm = 168
                )
            )

            // Recovery between intervals (and after last interval before cool-down if specified)
            if (i < repeatCount) {
                steps.add(
                    WorkoutStep(
                        title = "Walk Recovery $i",
                        stepType = StepType.RECOVERY,
                        targetType = TargetType.DURATION,
                        targetDurationSeconds = recoverySec,
                        targetPaceSecPerKm = 0, // Open pace for recovery
                        targetCadenceSpm = 115
                    )
                )
            }
        }

        // 3. Cool-Down Step
        if (cooldownDistMeters > 0) {
            steps.add(
                WorkoutStep(
                    title = "Cool Down",
                    stepType = StepType.COOLDOWN,
                    targetType = TargetType.DISTANCE,
                    targetDistanceMeters = cooldownDistMeters,
                    targetPaceSecPerKm = 0, // Open easy cool down
                    targetCadenceSpm = 158
                )
            )
        }

        return CustomWorkout(
            name = "${day.dayName} - ${day.title}",
            description = day.description,
            steps = steps
        )
    }

    /**
     * Parses segmented progressive workouts like:
     * "Km 1–3 @ 6:50 | Km 4–7 @ 6:15 | Km 8–8.5 @ 6:40"
     */
    private fun tryParseSegmentedWorkout(day: PlanDay): CustomWorkout? {
        val paceStr = day.pace
        if (!paceStr.contains("|") || !paceStr.contains("Km", ignoreCase = true)) return null

        val segments = paceStr.split("|").map { it.trim() }
        if (segments.size < 2) return null

        val steps = mutableListOf<WorkoutStep>()
        var previousEndKm = 0.0

        for ((idx, seg) in segments.withIndex()) {
            val match = Regex("""Km\s*([0-9.,]+)[–\-]([0-9.,]+)\s*@\s*([0-9]+:[0-9]+)""", RegexOption.IGNORE_CASE).find(seg)
                ?: continue

            val rawStartKm = match.groupValues[1].toDoubleOrNull() ?: previousEndKm
            val endKm = match.groupValues[2].toDoubleOrNull() ?: (rawStartKm + 1.0)
            val paceSec = parsePaceToSeconds(match.groupValues[3])

            // If the runner wrote 1-based kilometer ranges like "Km 1-3" (meaning 0 to 3 km)
            // or "Km 4-7" after Km 3 (meaning 3 to 7 km = 4 km), anchor to previousEndKm
            val actualStartKm = if (kotlin.math.abs(rawStartKm - (previousEndKm + 1.0)) < 0.1 || (idx == 0 && rawStartKm == 1.0)) {
                previousEndKm
            } else if (rawStartKm >= previousEndKm && kotlin.math.abs(rawStartKm - previousEndKm) < 0.1) {
                previousEndKm
            } else {
                rawStartKm
            }

            val distMeters = ((endKm - actualStartKm) * 1000).toInt().coerceAtLeast(500)
            previousEndKm = endKm

            val stepType = when {
                idx == 0 -> StepType.WARMUP
                idx == segments.size - 1 -> StepType.COOLDOWN
                else -> StepType.INTERVAL
            }

            val stepTitle = when (stepType) {
                StepType.WARMUP -> "Warm-up (Km $rawStartKm-$endKm)"
                StepType.COOLDOWN -> "Cool-down (Km $rawStartKm-$endKm)"
                StepType.INTERVAL -> "Race Pace Lock (Km $rawStartKm-$endKm)"
                else -> "Segment ${idx + 1}"
            }

            steps.add(
                WorkoutStep(
                    title = stepTitle,
                    stepType = stepType,
                    targetType = TargetType.DISTANCE,
                    targetDistanceMeters = distMeters,
                    targetPaceSecPerKm = paceSec,
                    targetCadenceSpm = if (stepType == StepType.INTERVAL) 168 else 162
                )
            )
        }

        if (steps.isEmpty()) return null

        return CustomWorkout(
            name = "${day.dayName} - ${day.title}",
            description = day.description,
            steps = steps
        )
    }

    /**
     * Parses compound plus-delimited tune-up workouts like:
     * "1.5km easy + 1.0km @ 6:15 + 1.0km easy"
     */
    private fun tryParseCompoundWorkout(day: PlanDay): CustomWorkout? {
        val paceStr = day.pace
        if (!paceStr.contains("+")) return null

        val parts = paceStr.split("+").map { it.trim() }
        if (parts.size < 2) return null

        val steps = mutableListOf<WorkoutStep>()

        for ((idx, part) in parts.withIndex()) {
            val distMeters = parseDistanceToMeters(part).takeIf { it > 0 } ?: 1000
            val paceSec = parsePaceToSeconds(part, defaultSec = if (part.contains("easy", ignoreCase = true)) 410 else 375)

            val stepType = when {
                idx == 0 -> StepType.WARMUP
                idx == parts.size - 1 -> StepType.COOLDOWN
                else -> StepType.INTERVAL
            }

            val title = when (stepType) {
                StepType.WARMUP -> "Warm-up"
                StepType.COOLDOWN -> "Cool-down"
                else -> "Pace Lock Rep"
            }

            steps.add(
                WorkoutStep(
                    title = title,
                    stepType = stepType,
                    targetType = TargetType.DISTANCE,
                    targetDistanceMeters = distMeters,
                    targetPaceSecPerKm = paceSec,
                    targetCadenceSpm = if (stepType == StepType.INTERVAL) 168 else 162
                )
            )
        }

        if (steps.isEmpty()) return null

        return CustomWorkout(
            name = "${day.dayName} - ${day.title}",
            description = day.description,
            steps = steps
        )
    }

    fun parseDistanceToMeters(distStr: String): Int {
        val kmMatch = Regex("""([0-9.,]+)\s*(?:km|k)\b""", RegexOption.IGNORE_CASE).find(distStr)
        if (kmMatch != null) {
            val km = kmMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: 5.0
            return (km * 1000).toInt()
        }
        val mMatch = Regex("""([0-9.,]+)\s*(?:m|meters)\b""", RegexOption.IGNORE_CASE).find(distStr)
        if (mMatch != null) {
            return mMatch.groupValues[1].replace(",", "").toIntOrNull() ?: 5000
        }
        return 0
    }

    fun parsePaceToSeconds(paceStr: String, defaultSec: Int = 375): Int {
        // Look for ranges like "6:12–6:18" or "6:50 – 7:05" -> take average
        val rangeMatch = Regex("""([0-9]+):([0-9]+)\s*[–\-]\s*([0-9]+):([0-9]+)""").find(paceStr)
        if (rangeMatch != null) {
            val m1 = rangeMatch.groupValues[1].toIntOrNull() ?: 6
            val s1 = rangeMatch.groupValues[2].toIntOrNull() ?: 15
            val m2 = rangeMatch.groupValues[3].toIntOrNull() ?: 6
            val s2 = rangeMatch.groupValues[4].toIntOrNull() ?: 15
            val sec1 = m1 * 60 + s1
            val sec2 = m2 * 60 + s2
            return (sec1 + sec2) / 2
        }

        // Single pace like "6:15"
        val singleMatch = Regex("""([0-9]+):([0-9]+)""").find(paceStr)
        if (singleMatch != null) {
            val m = singleMatch.groupValues[1].toIntOrNull() ?: 6
            val s = singleMatch.groupValues[2].toIntOrNull() ?: 15
            return m * 60 + s
        }

        if (paceStr.contains("easy", ignoreCase = true)) {
            return 410 // 6:50 min/km
        }

        return defaultSec
    }
}
