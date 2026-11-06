package com.runwise.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import com.runwise.app.data.api.ApiClient
import com.runwise.app.data.model.*
import com.runwise.app.util.PolylineUtils
import com.squareup.moshi.Types
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.roundToInt

class RunwiseRepository private constructor(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("runwise_prefs", Context.MODE_PRIVATE)
    private val moshi = ApiClient.getMoshi()
    private val syncAdapter = moshi.adapter(SyncResponse::class.java)
    private val detailAdapter = moshi.adapter(ActivityDetailResponse::class.java)
    private val detailMemoryCache = java.util.concurrent.ConcurrentHashMap<String, ActivityDetailResponse>()

    private val workoutListAdapter = moshi.adapter<List<CustomWorkout>>(
        Types.newParameterizedType(List::class.java, CustomWorkout::class.java)
    )

    private val activityListAdapter = moshi.adapter<List<ActivityItem>>(
        Types.newParameterizedType(List::class.java, ActivityItem::class.java)
    )

    private val _syncData = MutableStateFlow<SyncResponse?>(null)
    val syncData: StateFlow<SyncResponse?> = _syncData.asStateFlow()

    private val _localActivities = MutableStateFlow<List<ActivityItem>>(emptyList())
    val localActivities: StateFlow<List<ActivityItem>> = _localActivities.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    private val _customWorkouts = MutableStateFlow<List<CustomWorkout>>(emptyList())
    val customWorkouts: StateFlow<List<CustomWorkout>> = _customWorkouts.asStateFlow()

    init {
        loadCachedData()
        loadSavedWorkouts()
        loadLocalActivities()
    }

    fun getServerUrl(): String {
        return prefs.getString(
            PREF_SERVER_URL,
            "http://100.66.54.63:8081" // Default Tailscale homelab. Fallback: http://127.0.0.1:8081
        ) ?: "http://100.66.54.63:8081"
    }

    fun setServerUrl(url: String) {
        val cleanUrl = url.trim().removeSuffix("/")
        prefs.edit().putString(PREF_SERVER_URL, cleanUrl).apply()
    }

    fun getMinCadenceAlert(): Int {
        return prefs.getInt(PREF_MIN_CADENCE, 162)
    }

    fun setMinCadenceAlert(cadence: Int) {
        prefs.edit().putInt(PREF_MIN_CADENCE, cadence).apply()
    }

    fun getAudioIntervalMeters(): Int {
        return prefs.getInt(PREF_AUDIO_INTERVAL_METERS, 500) // 500m laps
    }

    fun setAudioIntervalMeters(meters: Int) {
        prefs.edit().putInt(PREF_AUDIO_INTERVAL_METERS, meters).apply()
    }

    fun isAudio500mCueEnabled(): Boolean {
        return prefs.getBoolean(PREF_AUDIO_500M_CUE, false) // Default false (opt-in to avoid chatty cues)
    }

    fun setAudio500mCueEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_AUDIO_500M_CUE, enabled).apply()
    }

    fun sync(onComplete: ((Boolean, String?) -> Unit)? = null) {
        if (_isSyncing.value) return

        CoroutineScope(Dispatchers.IO).launch {
            _isSyncing.value = true
            _syncError.value = null
            try {
                val baseUrl = getServerUrl()
                val api = ApiClient.create(baseUrl)

                val response = api.sync()
                _syncData.value = response
                saveCachedData(response)

                withContext(Dispatchers.Main) {
                    onComplete?.invoke(true, null)
                }
            } catch (e: Exception) {
                val errorMsg = e.localizedMessage ?: "Sync connection failed"
                _syncError.value = errorMsg
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false, errorMsg)
                }
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun savePhoneRecordedWorkout(
        sportType: String,
        workoutType: String,
        distanceMeters: Int,
        movingTimeSeconds: Long,
        elapsedTimeSeconds: Long,
        avgCadence: Int?,
        elevationGainMeters: Int,
        calories: Int,
        routePoints: List<Pair<Double, Double>>,
        customName: String? = null,
        completedLaps: List<com.runwise.app.service.LapSnapshot> = emptyList(),
        streams: ActivityStreamsData = ActivityStreamsData(),
        kmSplits: List<ActivitySplitItem> = emptyList()
    ): String {
        val id = "phone-" + UUID.randomUUID().toString()
        val now = Date()
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(now)
        val nowDisplay = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)
        val dateTitle = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(now)
        val activityName = if (!customName.isNullOrBlank()) customName.trim() else "$sportType - $dateTitle"

        val avgSpeedKmh = if (movingTimeSeconds > 0) (distanceMeters.toDouble() / movingTimeSeconds) * 3.6 else 0.0
        val maxSpeedKmh = avgSpeedKmh * 1.15
        val paceSecPerKm = if (distanceMeters > 50 && movingTimeSeconds > 0) ((movingTimeSeconds.toDouble() / distanceMeters) * 1000).roundToInt() else 0
        val paceFormatted = if (paceSecPerKm > 0) String.format(Locale.US, "%d:%02d", paceSecPerKm / 60, paceSecPerKm % 60) else "-"
        val encodedPoly = if (routePoints.isNotEmpty()) PolylineUtils.encodePolyline(routePoints) else null

        val item = ActivityItem(
            id = id,
            name = activityName,
            description = "Locally recorded $sportType workout ($workoutType). Elevation gain: +${elevationGainMeters}m.",
            sportType = sportType,
            workoutType = workoutType,
            startDateTime = nowDisplay,
            distanceMeters = distanceMeters,
            distanceKm = distanceMeters / 1000.0,
            movingTimeSeconds = movingTimeSeconds,
            elapsedTimeSeconds = elapsedTimeSeconds,
            averageSpeedKmh = avgSpeedKmh,
            maxSpeedKmh = maxSpeedKmh,
            averagePaceSecPerKm = paceSecPerKm,
            averagePaceFormatted = paceFormatted,
            averageCadence = avgCadence,
            elevationMeters = elevationGainMeters,
            calories = calories,
            encodedPolyline = encodedPoly,
            deviceName = "Runwise Phone Recording",
            isPhoneRecorded = true
        )

        // Generate full ActivityDetailResponse for local inspection (map, splits, laps, elevation, streams)
        val routeCoords = routePoints.map { listOf(it.first, it.second) }
        val splitsList = if (kmSplits.isNotEmpty()) {
            kmSplits
        } else if (completedLaps.isNotEmpty()) {
            completedLaps.map { lap ->
                val lapSpeedKmh = if (lap.timeSeconds > 0) (lap.distanceMeters.toDouble() / lap.timeSeconds) * 3.6 else 0.0
                ActivitySplitItem(
                    splitNumber = lap.lapNumber,
                    distanceMeters = lap.distanceMeters,
                    movingTimeSeconds = lap.timeSeconds,
                    elevationDifference = 0,
                    averageSpeedKmh = lapSpeedKmh,
                    paceFormatted = lap.paceFormatted,
                    paceSecPerKm = lap.paceSecPerKm
                )
            }
        } else {
            listOf(
                ActivitySplitItem(
                    splitNumber = 1,
                    distanceMeters = distanceMeters,
                    movingTimeSeconds = movingTimeSeconds,
                    elevationDifference = elevationGainMeters,
                    averageSpeedKmh = avgSpeedKmh,
                    paceFormatted = paceFormatted,
                    paceSecPerKm = paceSecPerKm
                )
            )
        }

        val lapsList = if (completedLaps.isNotEmpty()) {
            completedLaps.map { lap ->
                val lapSpeedKmh = if (lap.timeSeconds > 0) (lap.distanceMeters.toDouble() / lap.timeSeconds) * 3.6 else 0.0
                ActivityLapItem(
                    lapNumber = lap.lapNumber,
                    name = lap.stepTitle ?: "Lap ${lap.lapNumber}",
                    distanceMeters = lap.distanceMeters,
                    movingTimeSeconds = lap.timeSeconds,
                    averageSpeedKmh = lapSpeedKmh,
                    paceFormatted = lap.paceFormatted,
                    paceSecPerKm = lap.paceSecPerKm,
                    elevationDifference = 0,
                    isInterval = lap.isInterval
                )
            }
        } else {
            listOf(
                ActivityLapItem(
                    lapNumber = 1,
                    name = activityName,
                    distanceMeters = distanceMeters,
                    movingTimeSeconds = movingTimeSeconds,
                    averageSpeedKmh = avgSpeedKmh,
                    paceFormatted = paceFormatted,
                    paceSecPerKm = paceSecPerKm,
                    elevationDifference = elevationGainMeters,
                    isInterval = false
                )
            )
        }

        val detail = ActivityDetailResponse(
            status = "success",
            activity = item,
            routeCoordinates = routeCoords,
            streams = streams,
            splits = splitsList,
            laps = lapsList
        )
        saveDetailToDiskCache(id, detail)

        // Asynchronously query server Copernicus 30m DEM for authoritative ground elevation
        if (routePoints.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dem = fetchDemElevation(routePoints)
                    if (dem != null && dem.elevationCurve.isNotEmpty()) {
                        val enrichedStreams = streams.copy(elevationCurve = dem.elevationCurve)
                        val enrichedDetail = detail.copy(
                            streams = enrichedStreams,
                            activity = item.copy(elevationMeters = dem.elevationGainMeters)
                        )
                        saveDetailToDiskCache(id, enrichedDetail)
                    }
                } catch (_: Exception) {}
            }
        }

        // Generate and save standalone GPX file
        val gpxXml = PolylineUtils.generateGpxXml(
            activityName = activityName,
            sportType = sportType,
            startTimeIso = nowIso,
            routePoints = routePoints,
            elevationMeters = elevationGainMeters
        )
        saveGpxFile(id, activityName, gpxXml)

        // Append to local activities and persist
        val current = _localActivities.value.toMutableList()
        current.add(0, item)
        _localActivities.value = current
        persistLocalActivities(current)

        return id
    }

    suspend fun fetchDemElevation(coordinates: List<Pair<Double, Double>>): DemElevationResponse? {
        if (coordinates.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            try {
                val api = ApiClient.create(getServerUrl())
                val req = DemElevationRequest(coordinates = coordinates.map { listOf(it.first, it.second) })
                api.getDemElevation(req)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun saveGpxFile(activityId: String, activityName: String, gpxXml: String) {
        try {
            val appDir = File(context.getExternalFilesDir(null), "Runwise")
            if (!appDir.exists()) appDir.mkdirs()
            File(appDir, "$activityId.gpx").writeText(gpxXml)
        } catch (_: Exception) {}

        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val publicDir = File(downloadsDir, "Runwise")
            if (!publicDir.exists()) publicDir.mkdirs()
            val safeName = activityName.replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
            File(publicDir, "$safeName.gpx").writeText(gpxXml)
        } catch (_: Exception) {}
    }

    fun getCachedActivityDetail(activityId: String): ActivityDetailResponse? {
        detailMemoryCache[activityId]?.let { return it }
        try {
            val file = File(context.cacheDir, "activity_detail_$activityId.json")
            if (file.exists() && file.length() > 20) {
                val json = file.readText()
                val parsed = detailAdapter.fromJson(json)
                if (parsed != null) {
                    detailMemoryCache[activityId] = parsed
                    return parsed
                }
            }
        } catch (_: Exception) {}

        // Fallback to local recorded activity if present
        val localItem = _localActivities.value.find { it.id == activityId }
        if (localItem != null) {
            val routeCoords = if (!localItem.encodedPolyline.isNullOrBlank()) {
                PolylineUtils.decodePolyline(localItem.encodedPolyline).map { listOf(it.first, it.second) }
            } else emptyList()
            return ActivityDetailResponse(
                status = "local_summary",
                activity = localItem,
                routeCoordinates = routeCoords,
                splits = listOf(
                    ActivitySplitItem(
                        splitNumber = 1,
                        distanceMeters = localItem.distanceMeters,
                        movingTimeSeconds = localItem.movingTimeSeconds,
                        elevationDifference = localItem.elevationMeters,
                        averageSpeedKmh = localItem.averageSpeedKmh,
                        paceFormatted = localItem.averagePaceFormatted,
                        paceSecPerKm = localItem.averagePaceSecPerKm
                    )
                ),
                laps = listOf(
                    ActivityLapItem(
                        lapNumber = 1,
                        name = localItem.name,
                        distanceMeters = localItem.distanceMeters,
                        movingTimeSeconds = localItem.movingTimeSeconds,
                        averageSpeedKmh = localItem.averageSpeedKmh,
                        paceFormatted = localItem.averagePaceFormatted,
                        paceSecPerKm = localItem.averagePaceSecPerKm,
                        averageHeartRate = localItem.averageHeartRate,
                        isInterval = false
                    )
                )
            )
        }

        // Fallback to basic summary if available in syncData
        val item = _syncData.value?.activities?.find { it.id == activityId }
        if (item != null) {
            val routeCoords = if (!item.encodedPolyline.isNullOrBlank()) {
                PolylineUtils.decodePolyline(item.encodedPolyline).map { listOf(it.first, it.second) }
            } else emptyList()
            return ActivityDetailResponse(
                status = "cached_summary",
                activity = item,
                routeCoordinates = routeCoords,
                splits = listOf(
                    ActivitySplitItem(
                        splitNumber = 1,
                        distanceMeters = item.distanceMeters,
                        movingTimeSeconds = item.movingTimeSeconds,
                        elevationDifference = item.elevationMeters,
                        averageSpeedKmh = item.averageSpeedKmh,
                        paceFormatted = item.averagePaceFormatted,
                        paceSecPerKm = item.averagePaceSecPerKm
                    )
                ),
                laps = listOf(
                    ActivityLapItem(
                        lapNumber = 1,
                        name = item.name,
                        distanceMeters = item.distanceMeters,
                        movingTimeSeconds = item.movingTimeSeconds,
                        averageSpeedKmh = item.averageSpeedKmh,
                        paceFormatted = item.averagePaceFormatted,
                        paceSecPerKm = item.averagePaceSecPerKm,
                        averageHeartRate = item.averageHeartRate,
                        isInterval = false
                    )
                )
            )
        }
        return null
    }

    private fun saveDetailToDiskCache(activityId: String, detail: ActivityDetailResponse) {
        detailMemoryCache[activityId] = detail
        try {
            val file = File(context.cacheDir, "activity_detail_$activityId.json")
            file.writeText(detailAdapter.toJson(detail))
        } catch (_: Exception) {}
    }

    fun prefetchRecentActivityDetails(activityIds: List<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            for (id in activityIds) {
                if (detailMemoryCache.containsKey(id)) continue
                val file = File(context.cacheDir, "activity_detail_$id.json")
                if (file.exists() && file.length() > 50) {
                    try {
                        val parsed = detailAdapter.fromJson(file.readText())
                        if (parsed != null) {
                            detailMemoryCache[id] = parsed
                            continue
                        }
                    } catch (_: Exception) {}
                }

                try {
                    val api = ApiClient.create(getServerUrl())
                    val response = api.getActivityDetail(id)
                    saveDetailToDiskCache(id, response)
                } catch (_: Exception) {}
            }
        }
    }

    suspend fun getActivityDetail(activityId: String): Result<ActivityDetailResponse> = withContext(Dispatchers.IO) {
        val cached = getCachedActivityDetail(activityId)
        if (cached != null) {
            return@withContext Result.success(cached)
        }

        val isLocal = _localActivities.value.any { it.id == activityId } || activityId.startsWith("phone-")
        if (isLocal) {
            return@withContext Result.failure(Exception("Local workout detail not found"))
        }

        try {
            val api = ApiClient.create(getServerUrl())
            val response = api.getActivityDetail(activityId)
            saveDetailToDiskCache(activityId, response)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteActivity(activityId: String): Boolean = withContext(Dispatchers.IO) {
        val isLocal = _localActivities.value.any { it.id == activityId }
        if (isLocal) {
            val updated = _localActivities.value.filterNot { it.id == activityId }
            _localActivities.value = updated
            persistLocalActivities(updated)
            detailMemoryCache.remove(activityId)
            try {
                File(context.cacheDir, "activity_detail_$activityId.json").delete()
                File(context.getExternalFilesDir(null), "Runwise/$activityId.gpx").delete()
            } catch (_: Exception) {}
            return@withContext true
        }

        try {
            val api = ApiClient.create(getServerUrl())
            val response = api.deleteActivity(activityId)
            if (response.status == "success") {
                detailMemoryCache.remove(activityId)
                try {
                    File(context.cacheDir, "activity_detail_$activityId.json").delete()
                } catch (_: Exception) {}

                val current = _syncData.value
                if (current != null) {
                    val updatedActivities = current.activities.filterNot { it.id == activityId }
                    val updated = current.copy(activities = updatedActivities)
                    _syncData.value = updated
                    saveCachedData(updated)
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    // --- WORKOUT BUILDER STORAGE ---

    fun saveCustomWorkout(workout: CustomWorkout) {
        val current = _customWorkouts.value.toMutableList()
        val idx = current.indexOfFirst { it.id == workout.id }
        if (idx >= 0) {
            current[idx] = workout
        } else {
            current.add(0, workout)
        }
        _customWorkouts.value = current
        persistWorkouts(current)
    }

    fun deleteCustomWorkout(workoutId: String) {
        val updated = _customWorkouts.value.filterNot { it.id == workoutId }
        _customWorkouts.value = updated
        persistWorkouts(updated)
    }

    private fun loadSavedWorkouts() {
        try {
            val json = prefs.getString(PREF_SAVED_WORKOUTS, null)
            if (!json.isNullOrBlank()) {
                val list = workoutListAdapter.fromJson(json)
                if (!list.isNullOrEmpty()) {
                    _customWorkouts.value = list
                    return
                }
            }
        } catch (_: Exception) {
        }
        // Initialize with standard templates if empty
        val defaultTemplates = createDefaultTemplates()
        _customWorkouts.value = defaultTemplates
        persistWorkouts(defaultTemplates)
    }

    private fun persistWorkouts(list: List<CustomWorkout>) {
        try {
            val json = workoutListAdapter.toJson(list)
            prefs.edit().putString(PREF_SAVED_WORKOUTS, json).apply()
        } catch (_: Exception) {
        }
    }

    private fun createDefaultTemplates(): List<CustomWorkout> {
        return listOf(
            CustomWorkout(
                id = "preset-10k-lock",
                name = "10K Race Pace Lock",
                description = "Primary race target: locks legs to 6:15 min/km with strict ±10s auditory cues.",
                isPreset = true,
                steps = listOf(
                    WorkoutStep(
                        title = "Warm Up",
                        stepType = StepType.WARMUP,
                        targetType = TargetType.DISTANCE,
                        targetDistanceMeters = 1000,
                        targetPaceSecPerKm = 420, // 7:00 pace
                        targetCadenceSpm = 162
                    ),
                    WorkoutStep(
                        title = "10K Target Pace Lock",
                        stepType = StepType.RUN,
                        targetType = TargetType.DISTANCE,
                        targetDistanceMeters = 5000,
                        targetPaceSecPerKm = 375, // 6:15 pace
                        targetCadenceSpm = 168
                    ),
                    WorkoutStep(
                        title = "Cool Down",
                        stepType = StepType.COOLDOWN,
                        targetType = TargetType.DISTANCE,
                        targetDistanceMeters = 1000,
                        targetPaceSecPerKm = 435, // 7:15 pace
                        targetCadenceSpm = 160
                    )
                )
            ),
            CustomWorkout(
                id = "preset-4x1k-intervals",
                name = "4 × 1,000m Race Pace Intervals",
                description = "Teaches neuromuscular cadence and pacing memory. 1km repeats @ 6:15 with 400m recovery.",
                isPreset = true,
                steps = listOf(
                    WorkoutStep(title = "Warm Up", stepType = StepType.WARMUP, targetDistanceMeters = 1000, targetPaceSecPerKm = 420),
                    WorkoutStep(title = "Interval 1", stepType = StepType.INTERVAL, targetDistanceMeters = 1000, targetPaceSecPerKm = 375),
                    WorkoutStep(title = "Recovery 1", stepType = StepType.RECOVERY, targetDistanceMeters = 400, targetPaceSecPerKm = 450),
                    WorkoutStep(title = "Interval 2", stepType = StepType.INTERVAL, targetDistanceMeters = 1000, targetPaceSecPerKm = 375),
                    WorkoutStep(title = "Recovery 2", stepType = StepType.RECOVERY, targetDistanceMeters = 400, targetPaceSecPerKm = 450),
                    WorkoutStep(title = "Interval 3", stepType = StepType.INTERVAL, targetDistanceMeters = 1000, targetPaceSecPerKm = 375),
                    WorkoutStep(title = "Recovery 3", stepType = StepType.RECOVERY, targetDistanceMeters = 400, targetPaceSecPerKm = 450),
                    WorkoutStep(title = "Interval 4", stepType = StepType.INTERVAL, targetDistanceMeters = 1000, targetPaceSecPerKm = 375),
                    WorkoutStep(title = "Cool Down", stepType = StepType.COOLDOWN, targetDistanceMeters = 1000, targetPaceSecPerKm = 435)
                )
            ),
            CustomWorkout(
                id = "preset-z2-base",
                name = "Zone 2 Aerobic Base Run",
                description = "Steady, conversational aerobic run keeping calves protected and cardiac strain low.",
                isPreset = true,
                steps = listOf(
                    WorkoutStep(
                        title = "Base Aerobic Run",
                        stepType = StepType.RUN,
                        targetType = TargetType.DISTANCE,
                        targetDistanceMeters = 5000,
                        targetPaceSecPerKm = 415, // 6:55 min/km
                        targetCadenceSpm = 164
                    )
                )
            )
        )
    }

    private fun loadLocalActivities() {
        CoroutineScope(Dispatchers.IO).launch {
            val list = mutableListOf<ActivityItem>()
            try {
                val file = File(context.filesDir, LOCAL_ACTIVITIES_FILE)
                if (file.exists()) {
                    val json = file.readText()
                    val parsed = activityListAdapter.fromJson(json)
                    if (parsed != null) {
                        list.addAll(parsed)
                    }
                }
            } catch (_: Exception) {}

            // Pre-populate / restore recorded walk from Nov 2 (activity-9742edb9-2606-4f06-988c-8d027345fde5) if missing
            val walkId = "activity-9742edb9-2606-4f06-988c-8d027345fde5"
            if (list.none { it.id == walkId }) {
                val cacheFile = File(context.cacheDir, "activity_detail_$walkId.json")
                var recoveredItem: ActivityItem? = null
                if (cacheFile.exists()) {
                    try {
                        val detail = detailAdapter.fromJson(cacheFile.readText())
                        if (detail != null) {
                            recoveredItem = detail.activity.copy(
                                deviceName = "Runwise Phone Recording",
                                isPhoneRecorded = true
                            )
                        }
                    } catch (_: Exception) {}
                }
                if (recoveredItem == null) {
                    recoveredItem = ActivityItem(
                        id = walkId,
                        name = "Walk - Nov 2, 08:59",
                        description = "Completed Walk workout (Free Walk). Elevation gain: +0m. Total splits: 1.",
                        sportType = "Walk",
                        workoutType = "Free Walk",
                        startDateTime = "2026-11-02 08:59:17",
                        distanceMeters = 1,
                        distanceKm = 0.0,
                        movingTimeSeconds = 3,
                        elapsedTimeSeconds = 3,
                        averageSpeedKmh = 1.2,
                        maxSpeedKmh = 1.38,
                        averagePaceSecPerKm = 0,
                        averagePaceFormatted = "-",
                        elevationMeters = 0,
                        calories = 0,
                        encodedPolyline = "{hjyBucyzL@?",
                        deviceName = "Runwise Phone Recording",
                        isPhoneRecorded = true
                    )
                }
                list.add(0, recoveredItem)
                persistLocalActivities(list)

                // Ensure GPX file is written
                val gpxXml = PolylineUtils.generateGpxXml(
                    activityName = recoveredItem.name,
                    sportType = recoveredItem.sportType,
                    startTimeIso = "2026-11-02T08:59:17Z",
                    routePoints = listOf(Pair(20.04638, 72.71499), Pair(20.04637, 72.71499)),
                    elevationMeters = 0
                )
                saveGpxFile(walkId, recoveredItem.name, gpxXml)
            }

            _localActivities.value = list
        }
    }

    private fun persistLocalActivities(list: List<ActivityItem>) {
        try {
            val file = File(context.filesDir, LOCAL_ACTIVITIES_FILE)
            val json = activityListAdapter.toJson(list)
            file.writeText(json)
        } catch (_: Exception) {}
    }

    private fun loadCachedData() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
                if (cacheFile.exists()) {
                    val json = cacheFile.readText()
                    val data = syncAdapter.fromJson(json)
                    if (data != null) {
                        _syncData.value = data
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun saveCachedData(data: SyncResponse) {
        try {
            val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
            val json = syncAdapter.toJson(data)
            cacheFile.writeText(json)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val PREF_SERVER_URL = "pref_server_url"
        private const val PREF_MIN_CADENCE = "pref_min_cadence"
        private const val PREF_AUDIO_INTERVAL_METERS = "pref_audio_interval_meters"
        private const val PREF_AUDIO_500M_CUE = "pref_audio_500m_cue"
        private const val PREF_SAVED_WORKOUTS = "pref_saved_workouts"
        private const val CACHE_FILE_NAME = "runwise_sync_cache.json"
        private const val LOCAL_ACTIVITIES_FILE = "local_activities.json"

        @Volatile
        private var instance: RunwiseRepository? = null

        fun getInstance(context: Context): RunwiseRepository {
            return instance ?: synchronized(this) {
                instance ?: RunwiseRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
