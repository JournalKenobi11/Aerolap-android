package com.runwise.app.data.model

import com.squareup.moshi.JsonClass
import java.util.UUID

enum class StepType(val displayName: String, val badgeColorHex: Long) {
    WARMUP("Warm Up", 0xFFFFA000),         // Amber
    RUN("Run / Pace", 0xFFF26822),        // Runwise Orange
    INTERVAL("Interval Rep", 0xFFE53935),    // Speed Red
    RECOVERY("Recovery / Jog", 0xFF00C853),  // Green
    COOLDOWN("Cool Down", 0xFF00B0FF)      // Blue
}

enum class TargetType {
    DISTANCE,
    DURATION,
    OPEN
}

@JsonClass(generateAdapter = true)
data class WorkoutStep(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Lap",
    val stepType: StepType = StepType.RUN,
    val targetType: TargetType = TargetType.DISTANCE,
    val targetDistanceMeters: Int = 1000,
    val targetDurationSeconds: Int = 300,
    val targetPaceSecPerKm: Int = 375, // e.g. 6:15 min/km = 375s
    val paceToleranceSec: Int = 10,
    val targetCadenceSpm: Int = 168
) {
    val formattedPace: String get() {
        if (targetPaceSecPerKm <= 0) return "Open"
        val m = targetPaceSecPerKm / 60
        val s = targetPaceSecPerKm % 60
        return "%d:%02d".format(m, s)
    }

    val targetDescription: String get() = when (targetType) {
        TargetType.DISTANCE -> if (targetDistanceMeters >= 1000) "%.1f km".format(targetDistanceMeters / 1000.0) else "${targetDistanceMeters}m"
        TargetType.DURATION -> "${targetDurationSeconds / 60} min"
        TargetType.OPEN -> "Open Lap"
    }
}

@JsonClass(generateAdapter = true)
data class CustomWorkout(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val steps: List<WorkoutStep> = emptyList(),
    val isPreset: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    val totalDistanceMeters: Int get() = steps.filter { it.targetType == TargetType.DISTANCE }.sumOf { it.targetDistanceMeters }
    val formattedTotalDistance: String get() {
        val dist = totalDistanceMeters
        return if (dist > 0) "%.1f km".format(dist / 1000.0) else "Open Distance"
    }

    val primaryPaceFormatted: String get() {
        val runStep = steps.firstOrNull { it.stepType == StepType.RUN || it.stepType == StepType.INTERVAL }
            ?: steps.firstOrNull()
        return runStep?.formattedPace ?: "6:15"
    }
}
