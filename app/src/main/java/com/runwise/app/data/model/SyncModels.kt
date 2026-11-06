package com.runwise.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SyncResponse(
    val status: String,
    val syncedAt: String,
    val athlete: AthleteProfile,
    val readiness: ReadinessMetrics,
    val trainingLoadHistory: List<TrainingLoadPoint> = emptyList(),
    val trends: TrendsData,
    val plan: RunningPlan,
    val activities: List<ActivityItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class AthleteProfile(
    val name: String,
    val email: String,
    val birthday: String,
    val weightKg: Double,
    val restingHr: Int,
    val maxHr: Int,
    val targetRace: TargetRace,
    val calfGuidance: CalfGuidance
)

@JsonClass(generateAdapter = true)
data class TargetRace(
    val name: String,
    val date: String,
    val distanceMeters: Int,
    val targetPaceSecPerKm: Int,
    val targetPaceFormatted: String,
    val targetTimeFormatted: String,
    val paceToleranceSec: Int = 10,
    val paceMinSecPerKm: Int = 365,
    val paceMaxSecPerKm: Int = 385,
    val paceMinFormatted: String = "6:05",
    val paceMaxFormatted: String = "6:25",
    val targetCadenceSpm: Int = 168,
    val minCadenceAlertSpm: Int = 162
)

@JsonClass(generateAdapter = true)
data class CalfGuidance(
    val note: String,
    val targetReps: String,
    val equipment: String = "2kg Dumbbells"
)

@JsonClass(generateAdapter = true)
data class ReadinessMetrics(
    val ctl: Double,
    val atl: Double,
    val tsb: Double,
    val acwr: Double,
    val monotony: Double,
    val strain: Int,
    val status: String,
    val headline: String,
    val advice: String,
    val recommendation: String
)

@JsonClass(generateAdapter = true)
data class TrainingLoadPoint(
    val date: String,
    val load: Int,
    val ctl: Double,
    val atl: Double,
    val tsb: Double
)

@JsonClass(generateAdapter = true)
data class TrendsData(
    val totals: TotalsData,
    val weekly: List<WeeklyTrend> = emptyList(),
    val monthly: List<MonthlyTrend> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TotalsData(
    val totalActivities: Int,
    val totalDistanceKm: Double,
    val totalMovingTimeSeconds: Long,
    val bySport: Map<String, SportSummary> = emptyMap()
)

@JsonClass(generateAdapter = true)
data class SportSummary(
    val count: Int,
    val distanceKm: Double,
    val movingTimeSeconds: Long,
    val elevationMeters: Int = 0,
    val calories: Int = 0,
    val averagePaceSecPerKm: Int = 0,
    val averagePaceFormatted: String = "-"
)

@JsonClass(generateAdapter = true)
data class WeeklyTrend(
    val week: String,
    val minDate: String,
    val maxDate: String,
    val totalDistanceKm: Double,
    val totalMovingTimeSeconds: Long,
    val totalElevationMeters: Int = 0,
    val totalCalories: Int = 0,
    val bySport: Map<String, SportSummary> = emptyMap()
)

@JsonClass(generateAdapter = true)
data class MonthlyTrend(
    val month: String,
    val totalDistanceKm: Double,
    val totalMovingTimeSeconds: Long,
    val bySport: Map<String, SportSummary> = emptyMap()
)

@JsonClass(generateAdapter = true)
data class RunningPlan(
    val goalTitle: String,
    val targetPace: String,
    val targetTime: String,
    val currentCtl: String = "30.4",
    val currentAtl: String = "44.7",
    val currentTsb: String = "-14.3",
    val targetRaceTsb: String = "+7.5",
    val acwr: String = "1.47",
    val weeks: List<PlanWeek> = emptyList()
)

@JsonClass(generateAdapter = true)
data class PlanWeek(
    val number: Int,
    val title: String,
    val dates: String,
    val targetDistance: String,
    val focus: String,
    val projectedTsb: String,
    val days: List<PlanDay> = emptyList()
)

@JsonClass(generateAdapter = true)
data class PlanDay(
    val date: String,
    val dayName: String,
    val dayNumber: String,
    val title: String,
    val badge: String,
    val category: String,
    val distance: String,
    val pace: String,
    val targetHr: String,
    val description: String,
    val exercises: List<Exercise> = emptyList()
)

@JsonClass(generateAdapter = true)
data class Exercise(
    val name: String,
    val reps: String,
    val type: String
)

@JsonClass(generateAdapter = true)
data class ActivityItem(
    val id: String,
    val name: String,
    val description: String? = null,
    val sportType: String,
    val workoutType: String? = null,
    val startDateTime: String,
    val distanceMeters: Int,
    val distanceKm: Double,
    val movingTimeSeconds: Long,
    val elapsedTimeSeconds: Long,
    val averageSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val averagePaceSecPerKm: Int,
    val averagePaceFormatted: String,
    val averageHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val averageCadence: Int? = null,
    val elevationMeters: Int = 0,
    val calories: Int? = null,
    val encodedPolyline: String? = null,
    val deviceName: String? = null,
    val gpxUrl: String? = null,
    val fitUrl: String? = null,
    val isPhoneRecorded: Boolean = false
) {
    fun isRecordedOnPhone(): Boolean {
        return isPhoneRecorded ||
                deviceName?.contains("Phone", ignoreCase = true) == true ||
                deviceName?.contains("Android", ignoreCase = true) == true ||
                id.startsWith("phone-") ||
                id == "activity-9742edb9-2606-4f06-988c-8d027345fde5"
    }
}

@JsonClass(generateAdapter = true)
data class ActivityUploadRequest(
    val name: String,
    val sportType: String = "Run",
    val workoutType: String? = null,
    val startDateTime: String,
    val distanceMeters: Int,
    val movingTimeSeconds: Long,
    val elapsedTimeSeconds: Long,
    val averageCadence: Int? = null,
    val averageSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val elevationMeters: Int = 0,
    val calories: Int = 0,
    val deviceName: String = "Runwise Android",
    val polyline: String? = null,
    val description: String? = null
)

@JsonClass(generateAdapter = true)
data class ActivityUploadResponse(
    val status: String,
    val id: String? = null,
    val message: String
)

@JsonClass(generateAdapter = true)
data class HealthResponse(
    val status: String,
    val app: String,
    val timestamp: String
)

@JsonClass(generateAdapter = true)
data class ActivityDetailResponse(
    val status: String,
    val activity: ActivityItem,
    val routeCoordinates: List<List<Double>> = emptyList(),
    val streams: ActivityStreamsData = ActivityStreamsData(),
    val splits: List<ActivitySplitItem> = emptyList(),
    val laps: List<ActivityLapItem> = emptyList(),
    val paceZones: List<ZoneDistributionItem> = emptyList(),
    val hrZones: List<ZoneDistributionItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class ActivityStreamsData(
    val paceCurve: List<Double> = emptyList(),
    val hrCurve: List<Int> = emptyList(),
    val cadenceCurve: List<Int> = emptyList(),
    val elevationCurve: List<Double> = emptyList(),
    val distanceCurve: List<Double> = emptyList()
)

@JsonClass(generateAdapter = true)
data class ActivitySplitItem(
    val splitNumber: Int,
    val distanceMeters: Int,
    val movingTimeSeconds: Long,
    val elevationDifference: Int = 0,
    val averageSpeedKmh: Double = 0.0,
    val paceFormatted: String = "-",
    val paceSecPerKm: Int = 0,
    val paceZone: Int = 0
)

@JsonClass(generateAdapter = true)
data class ActivityLapItem(
    val lapNumber: Int,
    val name: String,
    val distanceMeters: Int,
    val movingTimeSeconds: Long,
    val averageSpeedKmh: Double = 0.0,
    val paceFormatted: String = "-",
    val paceSecPerKm: Int = 0,
    val elevationDifference: Int = 0,
    val averageHeartRate: Int? = null,
    val isInterval: Boolean = false
)

@JsonClass(generateAdapter = true)
data class ZoneDistributionItem(
    val name: String,
    val range: String? = null,
    val seconds: Int = 0,
    val percent: Double = 0.0
)

@JsonClass(generateAdapter = true)
data class GenericStatusResponse(
    val status: String,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class DemElevationRequest(
    val coordinates: List<List<Double>>
)

@JsonClass(generateAdapter = true)
data class DemElevationResponse(
    val status: String,
    val elevationGainMeters: Int = 0,
    val minAltitude: Double = 0.0,
    val maxAltitude: Double = 0.0,
    val elevationCurve: List<Double> = emptyList()
)
