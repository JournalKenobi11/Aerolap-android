package com.runwise.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.runwise.app.MainActivity
import com.runwise.app.coach.AudioCoach
import com.runwise.app.coach.CadenceTracker
import com.runwise.app.data.api.ApiClient
import com.runwise.app.data.model.CustomWorkout
import com.runwise.app.data.model.StepType
import com.runwise.app.data.model.TargetType
import com.runwise.app.data.model.WorkoutStep
import com.runwise.app.data.repository.RunwiseRepository
import com.runwise.app.service.engine.AutoPauseEngine
import com.runwise.app.service.engine.AutoPauseListener
import com.runwise.app.service.engine.FilterReason
import com.runwise.app.service.engine.GpsKinematicFilter
import com.runwise.app.service.engine.HardwareSpeedCache
import com.runwise.app.service.engine.RollingPaceEstimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class GpsStatus {
    SEARCHING,
    LOCKED,
    DISABLED
}

data class LapSnapshot(
    val lapNumber: Int,
    val distanceMeters: Int,
    val timeSeconds: Long,
    val paceSecPerKm: Int,
    val paceFormatted: String,
    val stepTitle: String? = null,
    val isInterval: Boolean = false
)

data class RunState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val isAutoPaused: Boolean = false,
    val isPreStart: Boolean = false,
    val gpsStatus: GpsStatus = GpsStatus.SEARCHING,
    val gpsAccuracyMeters: Float = 999f,
    val currentLatitude: Double = 0.0,
    val currentLongitude: Double = 0.0,
    val distanceMeters: Int = 0,
    val movingTimeSeconds: Long = 0L,
    val elapsedTimeSeconds: Long = 0L,
    val currentPaceSecPerKm: Int = 0,
    val averagePaceSecPerKm: Int = 0,
    val targetPaceSecPerKm: Int = 375, // Dynamic from active step
    val currentCadenceSpm: Int = 0,
    val averageCadenceSpm: Int = 0,
    val currentLapNumber: Int = 1,
    val totalLapsCount: Int = 1,
    val currentStepTitle: String = "Lap 1",
    val stepType: StepType = StepType.RUN,
    val stepTargetDistanceMeters: Int = 0,
    val stepTargetDurationSeconds: Int = 0,
    val lapDistanceMeters: Int = 0,
    val lapTimeSeconds: Long = 0L,
    val lapPaceSecPerKm: Int = 0,
    val calories: Int = 0,
    val currentAltitudeMeters: Double = 0.0,
    val elevationGainMeters: Int = 0,
    val sportType: String = "Run",
    val routePoints: List<Pair<Double, Double>> = emptyList(),
    val workoutType: String = "Run Workout",
    val completedLaps: List<LapSnapshot> = emptyList(),
    val currentSplitNumber: Int = 1,
    val currentSplitDistanceMeters: Int = 0,
    val currentSplitPaceSecPerKm: Int = 0,
    val lastSplitPaceSecPerKm: Int = 0,
    val lastSplitPaceFormatted: String = "-",
    val completedSplits: List<com.runwise.app.data.model.ActivitySplitItem> = emptyList()
)

class TrackingService : Service(), AutoPauseListener {

    private val binder = LocalBinder()
    private lateinit var locationManager: LocationManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var cadenceTracker: CadenceTracker
    private lateinit var audioCoach: AudioCoach
    private lateinit var repository: RunwiseRepository
    private var wakeLock: PowerManager.WakeLock? = null

    // Strava-Derived Signal Processing Subsystems
    private val hardwareSpeedCache = HardwareSpeedCache()
    private val gpsKinematicFilter = GpsKinematicFilter()
    private val rollingPaceEstimator = RollingPaceEstimator()
    private lateinit var autoPauseEngine: AutoPauseEngine

    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var timerJob: Job? = null

    private var startTimeMillis = 0L
    private var pausedDurationMillis = 0L
    private var lastPauseTimestamp = 0L
    private var lastLocation: Location? = null
    private var lastAltitude: Double? = null
    private var smoothedAltitude: Double? = null
    private var elevationReferenceAltitude: Double? = null
    private var cumulativeElevationGainMeters = 0.0
    private var currentSportType = "Run"

    // Time-series streams for performance curves
    private val paceCurveStream = mutableListOf<Double>()
    private val cadenceCurveStream = mutableListOf<Int>()
    private val elevationCurveStream = mutableListOf<Double>()
    private val distanceCurveStream = mutableListOf<Double>()

    // Separate checkpoint (500m) & kilometer milestone (1,000m) tracking
    private var checkpointStartDistance = 0.0
    private var checkpointStartMovingTimeMs = 0L
    private var kilometerStartDistance = 0.0
    private var kilometerStartMovingTimeMs = 0L
    private var next500mBoundary = 500.0
    private var nextKilometerBoundary = 1000.0
    private var completedKmCount = 0
    private var lastKmBoundaryElev = 0.0
    private var lastAcceptedMovingTimeMs = 0L
    private var lastAcceptedElevation = 0.0
    private var lastResumeTimestamp = 0L
    private var lastStepStartTimestamp = 0L
    private val kmSplitsList = mutableListOf<com.runwise.app.data.model.ActivitySplitItem>()
    private val richTrackPoints = mutableListOf<com.runwise.app.util.GpsTrackPoint>()
    private var splitCadenceSamples = 0
    private var splitCadenceSum = 0L

    // 5-second split massaging buffer (MIN_SPLIT_PACE_TIME_MS = 5000L)
    private var lastCompletedSplitPaceSec = 0
    private var lastSplitCrossingTimeMs = 0L

    // Step milestone countdown states
    private var stepHalfwayTriggered = false
    private var stepTenSecondsTriggered = false
    private var stepThreeSecondsTriggered = false
    private var lastCountdownSecond = -1L

    private var cumulativeDistanceMeters = 0.0
    private var lapDistanceMeters = 0.0
    private var lapStartTimeMillis = 0L
    private var currentLap = 1
    private var totalCadenceSamples = 0
    private var sumCadence = 0L
    private val completedLaps = mutableListOf<LapSnapshot>()

    // Active workout structure
    private var activeWorkout: CustomWorkout? = null
    private var currentStepIndex = 0

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) {
                handleLocationUpdate(location)
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): TrackingService = this@TrackingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        autoPauseEngine = AutoPauseEngine(this, this)
        cadenceTracker = CadenceTracker(this)
        audioCoach = AudioCoach(this)
        repository = RunwiseRepository.getInstance(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aerolap::TrackingWakeLock")

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> {
                val targetPace = intent.getIntExtra(EXTRA_TARGET_PACE, 0)
                val workoutType = intent.getStringExtra(EXTRA_WORKOUT_TYPE) ?: "Target Pace Run"
                val sportType = intent.getStringExtra(EXTRA_SPORT_TYPE) ?: "Run"
                val workoutJson = intent.getStringExtra(EXTRA_WORKOUT_JSON)
                prepareWorkout(targetPace, workoutType, sportType, workoutJson)
            }
            ACTION_CONFIRM_START -> confirmStart()
            ACTION_CANCEL_PRE_START -> cancelPreStart()
            ACTION_START -> {
                val targetPace = intent.getIntExtra(EXTRA_TARGET_PACE, 375)
                val workoutType = intent.getStringExtra(EXTRA_WORKOUT_TYPE) ?: "Target Pace Run"
                val sportType = intent.getStringExtra(EXTRA_SPORT_TYPE) ?: "Run"
                val workoutJson = intent.getStringExtra(EXTRA_WORKOUT_JSON)
                startWorkout(targetPace, workoutType, sportType, workoutJson)
            }
            ACTION_PAUSE -> pauseWorkout()
            ACTION_RESUME -> resumeWorkout()
            ACTION_LAP -> advanceOrManualLap(isAutomatic = false)
            ACTION_STOP -> {
                val customName = intent?.getStringExtra(EXTRA_CUSTOM_NAME)
                stopWorkout(customName)
            }
            ACTION_TOGGLE_MUTE -> {
                audioCoach.setMuted(!audioCoach.isMuted())
            }
        }
        return START_STICKY
    }

    private fun prepareWorkout(targetPaceSec: Int, workoutType: String, sportType: String, workoutJson: String?) {
        if (_runState.value.isRunning) return

        wakeLock?.acquire(3 * 60 * 60 * 1000L) // 3 hours max
        currentSportType = sportType

        // Parse structured workout or create fallback
        activeWorkout = if (!workoutJson.isNullOrBlank()) {
            try {
                ApiClient.getMoshi().adapter(CustomWorkout::class.java).fromJson(workoutJson)
            } catch (_: Exception) {
                null
            }
        } else null

        if (activeWorkout == null || activeWorkout?.steps.isNullOrEmpty()) {
            val fallbackStep = WorkoutStep(
                title = workoutType,
                stepType = StepType.RUN,
                targetType = TargetType.OPEN,
                targetPaceSecPerKm = targetPaceSec
            )
            activeWorkout = CustomWorkout(
                name = workoutType,
                steps = listOf(fallbackStep)
            )
        }

        currentStepIndex = 0
        val steps = activeWorkout!!.steps
        val firstStep = steps[0]
        completedLaps.clear()

        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val initialStatus = if (isGpsEnabled) GpsStatus.SEARCHING else GpsStatus.DISABLED

        _runState.value = RunState(
            isPreStart = true,
            isRunning = false,
            gpsStatus = initialStatus,
            gpsAccuracyMeters = 999f,
            targetPaceSecPerKm = firstStep.targetPaceSecPerKm,
            currentLapNumber = 1,
            totalLapsCount = steps.size,
            currentStepTitle = firstStep.title,
            stepType = firstStep.stepType,
            stepTargetDistanceMeters = firstStep.targetDistanceMeters,
            stepTargetDurationSeconds = firstStep.targetDurationSeconds,
            sportType = currentSportType,
            workoutType = activeWorkout!!.name,
            completedLaps = emptyList()
        )

        startForeground(NOTIFICATION_ID, buildNotification("Acquiring GPS...", "$sportType Ready check"))
        startLocationUpdates()
    }

    private fun confirmStart() {
        if (!_runState.value.isPreStart) return

        startTimeMillis = SystemClock.elapsedRealtime()
        lapStartTimeMillis = startTimeMillis
        pausedDurationMillis = 0L
        cumulativeDistanceMeters = 0.0
        lapDistanceMeters = 0.0
        currentLap = 1
        totalCadenceSamples = 0
        sumCadence = 0L
        lastAltitude = null
        smoothedAltitude = null
        elevationReferenceAltitude = null
        cumulativeElevationGainMeters = 0.0
        completedLaps.clear()
        paceCurveStream.clear()
        cadenceCurveStream.clear()
        elevationCurveStream.clear()
        distanceCurveStream.clear()
        kmSplitsList.clear()
        richTrackPoints.clear()
        splitCadenceSamples = 0
        splitCadenceSum = 0L
        richTrackPoints.clear()
        splitCadenceSamples = 0
        splitCadenceSum = 0L

        hardwareSpeedCache.clear()
        gpsKinematicFilter.reset()
        rollingPaceEstimator.reset()
        audioCoach.resetStepPaceTracking()
        audioCoach.resetStepCadenceTracking()
        resetStepMilestones()
        lastCompletedSplitPaceSec = 0
        lastSplitCrossingTimeMs = 0L

        checkpointStartDistance = 0.0
        checkpointStartMovingTimeMs = 0L
        kilometerStartDistance = 0.0
        kilometerStartMovingTimeMs = 0L
        next500mBoundary = 500.0
        nextKilometerBoundary = 1000.0
        completedKmCount = 0
        lastKmBoundaryElev = 0.0
        lastAcceptedMovingTimeMs = 0L
        lastAcceptedElevation = 0.0
        lastResumeTimestamp = startTimeMillis
        lastStepStartTimestamp = startTimeMillis

        cadenceTracker.start()
        autoPauseEngine.start()

        val steps = activeWorkout?.steps ?: emptyList()
        val firstStep = steps.getOrNull(0)

        _runState.value = _runState.value.copy(
            isPreStart = false,
            isRunning = true,
            isPaused = false,
            isAutoPaused = false
        )

        if (firstStep != null) {
            audioCoach.announceStepTransitionManual(
                stepNumber = 1,
                totalSteps = steps.size,
                stepTitle = firstStep.title,
                targetPaceSecPerKm = firstStep.targetPaceSecPerKm,
                distanceMeters = if (firstStep.targetType == TargetType.DISTANCE) firstStep.targetDistanceMeters else 0
            )
        }

        startTimerLoop()
        updateNotification()
    }

    private fun cancelPreStart() {
        stopLocationUpdates()
        _runState.value = RunState(isPreStart = false, isRunning = false)
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startWorkout(targetPaceSec: Int, workoutType: String, sportType: String, workoutJson: String?) {
        if (_runState.value.isRunning) return

        wakeLock?.acquire(3 * 60 * 60 * 1000L) // 3 hours max
        startForeground(NOTIFICATION_ID, buildNotification("Workout Started", "Acquiring GPS..."))

        startTimeMillis = SystemClock.elapsedRealtime()
        lapStartTimeMillis = startTimeMillis
        pausedDurationMillis = 0L
        cumulativeDistanceMeters = 0.0
        lapDistanceMeters = 0.0
        currentLap = 1
        totalCadenceSamples = 0
        sumCadence = 0L
        lastAltitude = null
        smoothedAltitude = null
        elevationReferenceAltitude = null
        cumulativeElevationGainMeters = 0.0
        currentSportType = sportType
        completedLaps.clear()
        kmSplitsList.clear()
        richTrackPoints.clear()
        splitCadenceSamples = 0
        splitCadenceSum = 0L
        paceCurveStream.clear()
        cadenceCurveStream.clear()
        elevationCurveStream.clear()
        distanceCurveStream.clear()

        hardwareSpeedCache.clear()
        gpsKinematicFilter.reset()
        rollingPaceEstimator.reset()
        audioCoach.resetStepPaceTracking()
        audioCoach.resetStepCadenceTracking()
        resetStepMilestones()
        lastCompletedSplitPaceSec = 0
        lastSplitCrossingTimeMs = 0L

        checkpointStartDistance = 0.0
        checkpointStartMovingTimeMs = 0L
        kilometerStartDistance = 0.0
        kilometerStartMovingTimeMs = 0L
        next500mBoundary = 500.0
        nextKilometerBoundary = 1000.0
        completedKmCount = 0
        lastKmBoundaryElev = 0.0
        lastAcceptedMovingTimeMs = 0L
        lastAcceptedElevation = 0.0
        lastResumeTimestamp = startTimeMillis
        lastStepStartTimestamp = startTimeMillis

        // Parse structured workout or create fallback
        activeWorkout = if (!workoutJson.isNullOrBlank()) {
            try {
                ApiClient.getMoshi().adapter(CustomWorkout::class.java).fromJson(workoutJson)
            } catch (_: Exception) {
                null
            }
        } else null

        if (activeWorkout == null || activeWorkout?.steps.isNullOrEmpty()) {
            val fallbackStep = WorkoutStep(
                title = workoutType,
                stepType = StepType.RUN,
                targetType = TargetType.OPEN,
                targetPaceSecPerKm = targetPaceSec
            )
            activeWorkout = CustomWorkout(
                name = workoutType,
                steps = listOf(fallbackStep)
            )
        }

        currentStepIndex = 0
        val steps = activeWorkout!!.steps
        val firstStep = steps[0]

        cadenceTracker.start()
        autoPauseEngine.start()

        _runState.value = RunState(
            isRunning = true,
            isPaused = false,
            isAutoPaused = false,
            targetPaceSecPerKm = firstStep.targetPaceSecPerKm,
            currentLapNumber = 1,
            totalLapsCount = steps.size,
            currentStepTitle = firstStep.title,
            stepType = firstStep.stepType,
            stepTargetDistanceMeters = firstStep.targetDistanceMeters,
            stepTargetDurationSeconds = firstStep.targetDurationSeconds,
            sportType = currentSportType,
            workoutType = activeWorkout!!.name
        )

        audioCoach.announceStepTransitionManual(
            stepNumber = 1,
            totalSteps = steps.size,
            stepTitle = firstStep.title,
            targetPaceSecPerKm = firstStep.targetPaceSecPerKm,
            distanceMeters = if (firstStep.targetType == TargetType.DISTANCE) firstStep.targetDistanceMeters else 0
        )

        startLocationUpdates()
        startTimerLoop()
    }

    private fun pauseWorkout() {
        if (!_runState.value.isRunning || _runState.value.isPaused) return
        lastPauseTimestamp = SystemClock.elapsedRealtime()
        autoPauseEngine.setRunningState(false)
        cadenceTracker.stop()
        _runState.value = _runState.value.copy(isPaused = true, isAutoPaused = false)
        audioCoach.speakCustom("Workout paused.")
        updateNotification()
    }

    private fun resumeWorkout() {
        if (!_runState.value.isRunning || !_runState.value.isPaused) return
        pausedDurationMillis += SystemClock.elapsedRealtime() - lastPauseTimestamp
        lastResumeTimestamp = SystemClock.elapsedRealtime()
        autoPauseEngine.setRunningState(true)
        cadenceTracker.start()
        _runState.value = _runState.value.copy(isPaused = false, isAutoPaused = false)
        audioCoach.speakCustom("Resuming workout.")
        updateNotification()
    }

    // AutoPauseListener callbacks (Strava km00 / lmn / vxz)
    override fun onAutoPauseTriggered() {
        if (!_runState.value.isRunning || _runState.value.isPaused) return
        lastPauseTimestamp = SystemClock.elapsedRealtime()
        cadenceTracker.stop()
        _runState.value = _runState.value.copy(isPaused = true, isAutoPaused = true)
        audioCoach.speakCustom("Workout paused.")
        updateNotification()
    }

    override fun onAutoResumeTriggered() {
        if (!_runState.value.isRunning || !_runState.value.isPaused) return
        pausedDurationMillis += SystemClock.elapsedRealtime() - lastPauseTimestamp
        lastResumeTimestamp = SystemClock.elapsedRealtime()
        cadenceTracker.start()
        _runState.value = _runState.value.copy(isPaused = false, isAutoPaused = false)
        audioCoach.speakCustom("Resuming workout.")
        updateNotification()
    }

    private fun resetStepMilestones() {
        stepHalfwayTriggered = false
        stepTenSecondsTriggered = false
        stepThreeSecondsTriggered = false
        lastCountdownSecond = -1L
    }

    private fun getNextStepAudioDescription(nextIndex: Int, steps: List<WorkoutStep>): String {
        if (nextIndex >= steps.size) return "Workout complete"
        val nextStep = steps[nextIndex]
        val distOrTime = if (nextStep.targetType == TargetType.DISTANCE) {
            val meters = nextStep.targetDistanceMeters
            if (meters >= 1000) {
                val km = meters / 1000.0
                if (km == km.toInt().toDouble()) "${km.toInt()} kilometers" else "$km kilometers"
            } else {
                "$meters meters"
            }
        } else if (nextStep.targetType == TargetType.DURATION) {
            val secs = nextStep.targetDurationSeconds
            if (secs >= 60) {
                val mins = secs / 60
                val remSecs = secs % 60
                if (remSecs == 0) {
                    if (mins == 1) "1 minute" else "$mins minutes"
                } else {
                    "$mins minutes $remSecs seconds"
                }
            } else {
                "$secs seconds"
            }
        } else ""

        val paceStr = if (nextStep.targetPaceSecPerKm > 0) {
            val m = nextStep.targetPaceSecPerKm / 60
            val s = nextStep.targetPaceSecPerKm % 60
            val sFormatted = if (s < 10) "0$s" else "$s"
            "at $m $sFormatted pace"
        } else ""

        return "${nextStep.title}. $distOrTime $paceStr".trim()
    }

    private fun advanceOrManualLap(isAutomatic: Boolean = false) {
        if (!_runState.value.isRunning) return
        val now = SystemClock.elapsedRealtime()
        lastStepStartTimestamp = now
        val lapTimeSec = (now - lapStartTimeMillis) / 1000L
        val lapMeters = lapDistanceMeters.toInt()
        val lapPace = if (lapMeters > 30 && lapTimeSec > 0) ((lapTimeSec.toDouble() / lapMeters) * 1000.0).toInt() else 0
        val paceStr = formatPace(lapPace)

        val workout = activeWorkout
        val steps = workout?.steps ?: emptyList()

        if (currentStepIndex < steps.size - 1) {
            // Snapshot current completed structured step
            val finishedStep = steps[currentStepIndex]
            val isInterval = finishedStep.stepType == StepType.INTERVAL
            completedLaps.add(
                LapSnapshot(
                    lapNumber = currentLap,
                    distanceMeters = lapMeters,
                    timeSeconds = lapTimeSec,
                    paceSecPerKm = lapPace,
                    paceFormatted = paceStr,
                    stepTitle = finishedStep.title,
                    isInterval = isInterval
                )
            )

            currentStepIndex++
            val nextStep = steps[currentStepIndex]
            currentLap = currentStepIndex + 1
            lapDistanceMeters = 0.0
            lapStartTimeMillis = now

            // Strava invariant: reset rolling pace buffer & audio pacer on step transition
            rollingPaceEstimator.reset()
            audioCoach.resetStepPaceTracking()
            audioCoach.resetStepCadenceTracking()
            resetStepMilestones()

            _runState.value = _runState.value.copy(
                completedLaps = completedLaps.toList(),
                currentLapNumber = currentLap,
                currentStepTitle = nextStep.title,
                stepType = nextStep.stepType,
                stepTargetDistanceMeters = nextStep.targetDistanceMeters,
                stepTargetDurationSeconds = nextStep.targetDurationSeconds,
                targetPaceSecPerKm = nextStep.targetPaceSecPerKm,
                lapDistanceMeters = 0,
                lapTimeSeconds = 0L,
                lapPaceSecPerKm = 0
            )

            if (isAutomatic) {
                val splitSummary = if (lapTimeSec >= 60L && lapMeters >= 400) "Lap pace $paceStr" else null
                val isWorkTransition = (nextStep.stepType == StepType.INTERVAL && (finishedStep.stepType == StepType.RECOVERY || finishedStep.stepType == StepType.WARMUP))
                val isRecoveryTransition = (nextStep.stepType == StepType.RECOVERY)
                val nextDesc = getNextStepAudioDescription(currentStepIndex, steps)
                audioCoach.announceStepTransitionAutomatic(
                    stepNumber = currentLap,
                    totalSteps = steps.size,
                    stepTitle = nextStep.title,
                    stepDescription = nextDesc,
                    splitSummary = splitSummary,
                    isWorkTransition = isWorkTransition,
                    isRecoveryTransition = isRecoveryTransition
                )
            } else {
                audioCoach.announceStepTransitionManual(
                    stepNumber = currentLap,
                    totalSteps = steps.size,
                    stepTitle = nextStep.title,
                    targetPaceSecPerKm = nextStep.targetPaceSecPerKm,
                    distanceMeters = if (nextStep.targetType == TargetType.DISTANCE) nextStep.targetDistanceMeters else 0
                )
            }
        } else {
            // Manual split on current step or open run manual split
            val currentTitle = if (steps.isNotEmpty() && currentStepIndex < steps.size) {
                steps[currentStepIndex].title
            } else {
                "Lap $currentLap"
            }
            val isInterval = steps.getOrNull(currentStepIndex)?.let {
                it.stepType == StepType.INTERVAL
            } ?: false

            if (isAutomatic) {
                audioCoach.vibrate2Seconds()
                audioCoach.speakCustom("Workout complete. Outstanding effort! All steps completed.")
            } else {
                audioCoach.announceSplit(currentLap, lapMeters, lapPace, _runState.value.movingTimeSeconds)
            }

            completedLaps.add(
                LapSnapshot(
                    lapNumber = currentLap,
                    distanceMeters = lapMeters,
                    timeSeconds = lapTimeSec,
                    paceSecPerKm = lapPace,
                    paceFormatted = paceStr,
                    stepTitle = currentTitle,
                    isInterval = isInterval
                )
            )

            currentLap++
            lapDistanceMeters = 0.0
            lapStartTimeMillis = now

            // Reset step-specific estimators & milestones
            rollingPaceEstimator.reset()
            audioCoach.resetStepPaceTracking()
            audioCoach.resetStepCadenceTracking()
            resetStepMilestones()

            _runState.value = _runState.value.copy(
                completedLaps = completedLaps.toList(),
                currentLapNumber = currentLap,
                totalLapsCount = maxOf(_runState.value.totalLapsCount, currentLap),
                lapDistanceMeters = 0,
                lapTimeSeconds = 0L,
                lapPaceSecPerKm = 0
            )
        }
    }

    private fun stopWorkout(customName: String? = null) {
        if (!_runState.value.isRunning) return

        val finalState = _runState.value
        autoPauseEngine.stop()
        cadenceTracker.stop()
        stopLocationUpdates()
        timerJob?.cancel()

        audioCoach.speakCustom("Workout completed. Outstanding effort! Workout saved to phone.")

        // Snapshot final active in-progress lap if meaningful distance/duration recorded
        val now = SystemClock.elapsedRealtime()
        val finalLapTimeSec = (now - lapStartTimeMillis) / 1000L
        val finalLapMeters = lapDistanceMeters.toInt()
        if (finalLapMeters > 20 || finalLapTimeSec > 5) {
            val finalLapPace = if (finalLapMeters > 30 && finalLapTimeSec > 0) {
                ((finalLapTimeSec.toDouble() / finalLapMeters) * 1000.0).toInt()
            } else 0
            completedLaps.add(
                LapSnapshot(
                    lapNumber = currentLap,
                    distanceMeters = finalLapMeters,
                    timeSeconds = finalLapTimeSec,
                    paceSecPerKm = finalLapPace,
                    paceFormatted = formatPace(finalLapPace),
                    stepTitle = finalState.currentStepTitle,
                    isInterval = finalState.stepType == StepType.INTERVAL
                )
            )
        }
        val lapsToSave = completedLaps.toList()

        // Include remaining partial kilometer split (e.g. 548m of 2,548m)
        val currentMovingTimeMs = (now - startTimeMillis - pausedDurationMillis).coerceAtLeast(0L)
        val finalKmDist = cumulativeDistanceMeters - kilometerStartDistance
        val finalKmTimeSec = (currentMovingTimeMs - kilometerStartMovingTimeMs) / 1000.0
        if (finalKmDist >= 20.0) {
            val finalKmPace = if (finalKmDist > 10.0 && finalKmTimeSec > 0) {
                ((finalKmTimeSec / finalKmDist) * 1000.0).toInt()
            } else 0
            val finalKmSpeedKmh = if (finalKmTimeSec > 0) (finalKmDist / finalKmTimeSec) * 3.6 else 0.0
            val currentAlt = smoothedAltitude ?: (lastAltitude ?: 0.0)
            val eleDelta = (currentAlt - lastKmBoundaryElev).roundToInt()

            kmSplitsList.add(
                com.runwise.app.data.model.ActivitySplitItem(
                    splitNumber = completedKmCount + 1,
                    distanceMeters = finalKmDist.roundToInt(),
                    movingTimeSeconds = finalKmTimeSec.toLong(),
                    elevationDifference = eleDelta,
                    averageSpeedKmh = finalKmSpeedKmh,
                    paceFormatted = formatPace(finalKmPace),
                    paceSecPerKm = finalKmPace
                )
            )
        }
        val kmSplitsToSave = kmSplitsList.toList()

        // Assemble populated time-series streams (pace, cadence, elevation, distance)
        val streamsToSave = com.runwise.app.data.model.ActivityStreamsData(
            paceCurve = paceCurveStream.toList(),
            hrCurve = emptyList(),
            cadenceCurve = cadenceCurveStream.toList(),
            elevationCurve = elevationCurveStream.toList(),
            distanceCurve = distanceCurveStream.toList()
        )

        // Save locally to phone storage & generate GPX (Strictly Down-Sync Only)
        serviceScope.launch {
            repository.savePhoneRecordedWorkout(
                sportType = finalState.sportType,
                workoutType = finalState.workoutType,
                distanceMeters = finalState.distanceMeters,
                movingTimeSeconds = finalState.movingTimeSeconds,
                elapsedTimeSeconds = finalState.elapsedTimeSeconds,
                avgCadence = if (finalState.averageCadenceSpm > 0) finalState.averageCadenceSpm else null,
                elevationGainMeters = finalState.elevationGainMeters,
                calories = finalState.calories,
                routePoints = finalState.routePoints,
                customName = customName,
                completedLaps = lapsToSave,
                streams = streamsToSave,
                kmSplits = kmSplitsToSave,
                trackPoints = richTrackPoints.toList()
            )
        }

        _runState.value = RunState(isRunning = false)
        wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startLocationUpdates() {
        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(1000L)
                .setMinUpdateDistanceMeters(0f)
                .build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (_: SecurityException) {
        }
    }

    private fun stopLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {}
        lastLocation = null
    }

    private fun handleLocationUpdate(location: Location) {
        val lat = location.latitude
        val lng = location.longitude
        val acc = location.accuracy
        val now = SystemClock.elapsedRealtime()

        // 1. Hardware speed cache unconditional update (Strava sz40.java:546-552)
        if (location.hasSpeed()) {
            hardwareSpeedCache.update(location.speed, now)
        }

        // Handle Pre-Start GPS Acquisition Cue & Positioning
        if (_runState.value.isPreStart) {
            val prevGps = _runState.value.gpsStatus
            val isLocked = acc <= 20.0f
            val newGps = if (isLocked) GpsStatus.LOCKED else GpsStatus.SEARCHING

            if (newGps == GpsStatus.LOCKED && prevGps != GpsStatus.LOCKED) {
                audioCoach.speakCustom("GPS acquired. Ready to start.")
            }

            _runState.value = _runState.value.copy(
                gpsStatus = newGps,
                gpsAccuracyMeters = acc,
                currentLatitude = lat,
                currentLongitude = lng,
                currentAltitudeMeters = if (location.hasAltitude()) location.altitude else 0.0
            )
            return
        }

        if (!_runState.value.isRunning || _runState.value.isPaused) return

        // 2. Strava GPS Kinematic & Displacement Filter (sz40.java:553-642)
        val filterResult = gpsKinematicFilter.filter(location, now)
        if (filterResult.reason != FilterReason.ACCEPTED) {
            return
        }

        val distance = filterResult.displacementMeters
        val prevDistance = cumulativeDistanceMeters

        cumulativeDistanceMeters += distance
        lapDistanceMeters += distance

        val currentMovingTimeMs = (now - startTimeMillis - pausedDurationMillis).coerceAtLeast(0L)
        val lapElapsedMs = (now - lapStartTimeMillis).coerceAtLeast(0L)

        // 3. Update Strava 15s Rolling Pace Estimator (n200.a)
        rollingPaceEstimator.addSample(lapElapsedMs, lapDistanceMeters)
        val currentRollingPace = rollingPaceEstimator.calculateRollingPaceSecPerKm(lapElapsedMs)
        val currentRollingSpeedMps = rollingPaceEstimator.calculateRollingSpeedMps(lapElapsedMs)

        // Feed speed to GPS Auto-Pause engine (vxz.java)
        val speedForEngine = hardwareSpeedCache.getFreshSpeedMps(now) ?: currentRollingSpeedMps
        if (speedForEngine != null) {
            autoPauseEngine.onGpsSpeedUpdate(speedForEngine)
        }

        // Record time-series telemetry streams for every accepted GPS point
        paceCurveStream.add(if (currentRollingPace > 0) currentRollingPace.toDouble() else 0.0)
        cadenceCurveStream.add(cadenceTracker.currentCadenceSpm.value)
        elevationCurveStream.add(smoothedAltitude ?: (lastAltitude ?: (if (location.hasAltitude()) location.altitude else 0.0)))
        distanceCurveStream.add(cumulativeDistanceMeters)

        // 4. Distance-Based Step Milestones & Completion (Strava usy.java:1470-1524, i6f)
        val steps = activeWorkout?.steps
        if (!steps.isNullOrEmpty() && currentStepIndex < steps.size) {
            val currentStep = steps[currentStepIndex]
            if (currentStep.targetType == TargetType.DISTANCE) {
                val targetDist = currentStep.targetDistanceMeters.toDouble()
                val remDist = targetDist - lapDistanceMeters
                val speedForEta = speedForEngine ?: (if (currentRollingPace > 0) 1000.0 / currentRollingPace else 0.0)

                // Halfway Milestone
                if (!stepHalfwayTriggered && targetDist > 150.0 && lapDistanceMeters >= targetDist / 2.0) {
                    stepHalfwayTriggered = true
                    audioCoach.announceHalfway(currentStep.targetPaceSecPerKm)
                }

                // 10s Remaining Warning ("Almost there")
                if (!stepTenSecondsTriggered && targetDist > 50.0 && remDist <= 100.0 && speedForEta > 0.0) {
                    val eta = remDist / speedForEta
                    if (eta <= 10.0) {
                        stepTenSecondsTriggered = true
                        val nextDesc = getNextStepAudioDescription(currentStepIndex + 1, steps)
                        audioCoach.announceAlmostThere(nextDesc)
                    }
                }

                // 3s Countdown Chime
                if (!stepThreeSecondsTriggered && remDist <= 40.0 && speedForEta > 0.0) {
                    val eta = remDist / speedForEta
                    if (eta <= 3.0) {
                        stepThreeSecondsTriggered = true
                        audioCoach.playCountdownChime()
                    }
                }

                // Target Reached
                if (lapDistanceMeters >= targetDist) {
                    advanceOrManualLap(isAutomatic = true)
                }
            }
        }

        val currAlt = smoothedAltitude ?: (lastAltitude ?: (if (location.hasAltitude()) location.altitude else 0.0))

        // 5. Kilometer Splits with Exact Boundary Linear Interpolation (ActiveSplitList)
        while (cumulativeDistanceMeters >= next500mBoundary || cumulativeDistanceMeters >= nextKilometerBoundary) {
            if (next500mBoundary < nextKilometerBoundary && cumulativeDistanceMeters >= next500mBoundary) {
                // Intermediate 500m checkpoint
                val frac = if (cumulativeDistanceMeters > prevDistance) {
                    ((next500mBoundary - prevDistance) / (cumulativeDistanceMeters - prevDistance)).coerceIn(0.0, 1.0)
                } else 1.0
                val tBoundaryMovingMs = lastAcceptedMovingTimeMs + (frac * (currentMovingTimeMs - lastAcceptedMovingTimeMs)).toLong()
                val segDist = next500mBoundary - checkpointStartDistance
                val segTimeSec = (tBoundaryMovingMs - checkpointStartMovingTimeMs) / 1000.0
                val segPace = if (segDist > 30.0 && segTimeSec > 0) {
                    ((segTimeSec / segDist) * 1000.0).toInt()
                } else 0

                if (repository.isAudio500mCueEnabled()) {
                    audioCoach.announceCheckpoint(500, segPace)
                }

                checkpointStartDistance = next500mBoundary
                checkpointStartMovingTimeMs = tBoundaryMovingMs
                next500mBoundary += 1000.0
            } else if (cumulativeDistanceMeters >= nextKilometerBoundary) {
                // Complete 1 kilometer milestone
                val frac = if (cumulativeDistanceMeters > prevDistance) {
                    ((nextKilometerBoundary - prevDistance) / (cumulativeDistanceMeters - prevDistance)).coerceIn(0.0, 1.0)
                } else 1.0
                val tBoundaryMovingMs = lastAcceptedMovingTimeMs + (frac * (currentMovingTimeMs - lastAcceptedMovingTimeMs)).toLong()
                val kmDist = nextKilometerBoundary - kilometerStartDistance
                val kmTimeSec = (tBoundaryMovingMs - kilometerStartMovingTimeMs) / 1000.0
                val splitPace = if (kmDist > 50.0 && kmTimeSec > 0) {
                    ((kmTimeSec / kmDist) * 1000.0).toInt()
                } else 0

                val totalElapsedSec = tBoundaryMovingMs / 1000L
                val avgPace = if (nextKilometerBoundary > 50.0 && totalElapsedSec > 0) {
                    ((totalElapsedSec.toDouble() / nextKilometerBoundary) * 1000.0).toInt()
                } else 0

                val boundaryElev = lastAcceptedElevation + frac * (currAlt - lastAcceptedElevation)
                val eleDelta = (boundaryElev - lastKmBoundaryElev).roundToInt()
                val splitSpeedKmh = if (kmTimeSec > 0) (kmDist / kmTimeSec) * 3.6 else 0.0

                completedKmCount++
                val splitAvgCadence = if (splitCadenceSamples > 0) (splitCadenceSum / splitCadenceSamples).toInt() else null
                audioCoach.announceKilometerMilestone(
                    kmNumber = completedKmCount,
                    splitPaceSecPerKm = splitPace,
                    totalElapsedSeconds = totalElapsedSec,
                    avgPaceSecPerKm = avgPace,
                    splitCadenceSpm = splitAvgCadence,
                    targetPaceSecPerKm = _runState.value.targetPaceSecPerKm
                )
                splitCadenceSamples = 0
                splitCadenceSum = 0L

                // 5-second split massaging anchor (MIN_SPLIT_PACE_TIME_MS = 5000L)
                lastCompletedSplitPaceSec = splitPace
                lastSplitCrossingTimeMs = now

                kmSplitsList.add(
                    com.runwise.app.data.model.ActivitySplitItem(
                        splitNumber = completedKmCount,
                        distanceMeters = kmDist.roundToInt(),
                        movingTimeSeconds = kmTimeSec.toLong(),
                        elevationDifference = eleDelta,
                        averageSpeedKmh = splitSpeedKmh,
                        paceFormatted = formatPace(splitPace),
                        paceSecPerKm = splitPace
                    )
                )

                kilometerStartDistance = nextKilometerBoundary
                kilometerStartMovingTimeMs = tBoundaryMovingMs
                checkpointStartDistance = nextKilometerBoundary
                checkpointStartMovingTimeMs = tBoundaryMovingMs
                lastKmBoundaryElev = boundaryElev
                nextKilometerBoundary += 1000.0
                next500mBoundary = nextKilometerBoundary - 500.0
            } else {
                break
            }
        }

        lastAcceptedMovingTimeMs = currentMovingTimeMs
        lastAcceptedElevation = currAlt
        lastLocation = location

        if (location.hasAltitude()) {
            val rawAlt = location.altitude
            if (smoothedAltitude == null) {
                smoothedAltitude = rawAlt
                elevationReferenceAltitude = rawAlt
                lastAltitude = rawAlt
            } else {
                val prevSmoothed = smoothedAltitude!!
                val rawDiff = kotlin.math.abs(rawAlt - prevSmoothed)

                if (rawDiff < ELEVATION_MAX_VALID_SPIKE) {
                    val currentSmoothed = prevSmoothed + ELEVATION_SMOOTHING_ALPHA * (rawAlt - prevSmoothed)
                    smoothedAltitude = currentSmoothed
                    lastAltitude = currentSmoothed

                    val ref = elevationReferenceAltitude ?: currentSmoothed
                    val gainFromRef = currentSmoothed - ref

                    if (gainFromRef >= ELEVATION_HYSTERESIS_THRESHOLD) {
                        cumulativeElevationGainMeters += gainFromRef
                        elevationReferenceAltitude = currentSmoothed
                    } else if (ref - currentSmoothed >= ELEVATION_HYSTERESIS_THRESHOLD) {
                        elevationReferenceAltitude = currentSmoothed
                    }
                }
            }
        }

        val currentRoute = _runState.value.routePoints.toMutableList()
        currentRoute.add(Pair(location.latitude, location.longitude))

        val ptIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(if (location.time > 0) location.time else System.currentTimeMillis()))

        richTrackPoints.add(
            com.runwise.app.util.GpsTrackPoint(
                lat = location.latitude,
                lon = location.longitude,
                ele = lastAltitude ?: (if (location.hasAltitude()) location.altitude else 0.0),
                timestampIso = ptIso,
                cadence = if (cadenceTracker.currentCadenceSpm.value > 0) cadenceTracker.currentCadenceSpm.value else null,
                speedMps = if (location.hasSpeed()) location.speed.toDouble() else null
            )
        )

        _runState.value = _runState.value.copy(
            routePoints = currentRoute,
            currentAltitudeMeters = lastAltitude ?: 0.0,
            elevationGainMeters = cumulativeElevationGainMeters.roundToInt()
        )
    }

    private fun startTimerLoop() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                if (!_runState.value.isRunning || _runState.value.isPaused) continue

                val now = SystemClock.elapsedRealtime()
                val movingTimeSec = (now - startTimeMillis - pausedDurationMillis) / 1000L
                val lapTimeSec = (now - lapStartTimeMillis) / 1000L
                val lapElapsedMs = (now - lapStartTimeMillis).coerceAtLeast(0L)

                // Cadence tick
                cadenceTracker.tickDecay()
                val currentSpm = cadenceTracker.currentCadenceSpm.value
                if (currentSpm > 0) {
                    totalCadenceSamples++
                    sumCadence += currentSpm.toLong()
                    splitCadenceSamples++
                    splitCadenceSum += currentSpm.toLong()
                }
                val avgCadence = if (totalCadenceSamples > 0) (sumCadence / totalCadenceSamples).toInt() else 0

                // 1. Duration Step Milestones & Completion (Strava eb70.java)
                val steps = activeWorkout?.steps
                if (!steps.isNullOrEmpty() && currentStepIndex < steps.size) {
                    val currentStep = steps[currentStepIndex]
                    if (currentStep.targetType == TargetType.DURATION) {
                        val targetSec = currentStep.targetDurationSeconds.toLong()
                        val remSec = targetSec - lapTimeSec

                        // Halfway Milestone
                        if (!stepHalfwayTriggered && targetSec > 30L && lapTimeSec >= targetSec / 2L) {
                            stepHalfwayTriggered = true
                            audioCoach.announceHalfway(currentStep.targetPaceSecPerKm)
                        }

                        // 10s Remaining Warning
                        if (!stepTenSecondsTriggered && targetSec >= 20L && remSec <= 10L) {
                            stepTenSecondsTriggered = true
                            val nextDesc = getNextStepAudioDescription(currentStepIndex + 1, steps)
                            audioCoach.announceTenSecondsRemaining(nextDesc)
                        }

                        // 3-2-1 Countdown Chimes
                        if (remSec in 1L..3L && remSec != lastCountdownSecond) {
                            lastCountdownSecond = remSec
                            audioCoach.playCountdownChime()
                        }

                        // Target Reached
                        if (lapTimeSec >= targetSec) {
                            advanceOrManualLap(isAutomatic = true)
                        }
                    }
                }

                // 2. Strava 15-Second Rolling Pace Estimator
                val rollingPaceSec = rollingPaceEstimator.calculateRollingPaceSecPerKm(lapElapsedMs)
                val rollingSpeedMps = rollingPaceEstimator.calculateRollingSpeedMps(lapElapsedMs)

                // Calculate overall average pace
                val totalDist = cumulativeDistanceMeters.toInt()
                val avgPaceSec = if (totalDist > 50 && movingTimeSec > 0) {
                    ((movingTimeSec.toDouble() / totalDist) * 1000.0).toInt()
                } else 0

                // Current lap pace
                val lapMeters = lapDistanceMeters.toInt()
                val rawLapPaceSec = if (lapMeters > 50 && lapTimeSec > 0) {
                    ((lapTimeSec.toDouble() / lapMeters) * 1000.0).toInt()
                } else 0

                // 3. Current Split Calculations & Strava 5-Second Massaging Buffer (MIN_SPLIT_PACE_TIME_MS = 5000L)
                val currentMovingTimeMs = movingTimeSec * 1000L
                val currentSplitDist = (cumulativeDistanceMeters - kilometerStartDistance).coerceAtLeast(0.0)
                val currentSplitElapsedMovingMs = (currentMovingTimeMs - kilometerStartMovingTimeMs).coerceAtLeast(0L)
                val currentSplitElapsedSec = currentSplitElapsedMovingMs / 1000.0

                val currentSplitRawPaceSec = if (currentSplitDist > 20.0 && currentSplitElapsedSec > 0) {
                    ((currentSplitElapsedSec / currentSplitDist) * 1000.0).toInt()
                } else {
                    avgPaceSec
                }

                val displayedSplitPaceSec = if (currentSplitElapsedMovingMs <= 5000L && lastCompletedSplitPaceSec > 0) {
                    lastCompletedSplitPaceSec
                } else if (currentSplitRawPaceSec > 0) {
                    currentSplitRawPaceSec
                } else {
                    avgPaceSec
                }

                // Estimated calories burned
                val calories = ((totalDist / 1000.0) * 58.4 * 1.03).roundToInt()
                val activeTargetPace = _runState.value.targetPaceSecPerKm

                _runState.value = _runState.value.copy(
                    distanceMeters = totalDist,
                    movingTimeSeconds = movingTimeSec,
                    elapsedTimeSeconds = movingTimeSec,
                    currentPaceSecPerKm = displayedSplitPaceSec,
                    averagePaceSecPerKm = avgPaceSec,
                    currentCadenceSpm = currentSpm,
                    averageCadenceSpm = avgCadence,
                    lapDistanceMeters = lapMeters,
                    lapTimeSeconds = lapTimeSec,
                    lapPaceSecPerKm = rawLapPaceSec,
                    calories = calories,
                    currentAltitudeMeters = lastAltitude ?: 0.0,
                    elevationGainMeters = cumulativeElevationGainMeters.roundToInt(),
                    sportType = currentSportType,
                    currentSplitNumber = completedKmCount + 1,
                    currentSplitDistanceMeters = currentSplitDist.roundToInt(),
                    currentSplitPaceSecPerKm = displayedSplitPaceSec,
                    lastSplitPaceSecPerKm = lastCompletedSplitPaceSec,
                    lastSplitPaceFormatted = if (lastCompletedSplitPaceSec > 0) formatPace(lastCompletedSplitPaceSec) else "-",
                    completedSplits = kmSplitsList.toList()
                )

                // 4. Tactical Pace Guidance with 30s Persistence, Escalating Cooldown, & End-of-Step Suppression
                if (activeTargetPace > 0 && rollingPaceSec > 0 && currentSportType == "Run") {
                    val secondsSinceResume = (now - lastResumeTimestamp) / 1000L
                    val secondsSinceStepStart = (now - lastStepStartTimestamp) / 1000L

                    val currentStep = activeWorkout?.steps?.getOrNull(currentStepIndex)
                    val remainingStepSeconds = if (currentStep?.targetType == TargetType.DURATION) {
                        (currentStep.targetDurationSeconds.toLong() - lapTimeSec).coerceAtLeast(0L)
                    } else null

                    val remainingStepMeters = if (currentStep?.targetType == TargetType.DISTANCE) {
                        (currentStep.targetDistanceMeters.toDouble() - lapDistanceMeters).coerceAtLeast(0.0)
                    } else null

                    audioCoach.evaluatePaceWarning(
                        rollingPaceSecPerKm = rollingPaceSec,
                        targetPaceSecPerKm = activeTargetPace,
                        currentDistanceMeters = totalDist,
                        secondsSinceResume = secondsSinceResume,
                        secondsSinceStepStart = secondsSinceStepStart,
                        remainingStepSeconds = remainingStepSeconds,
                        remainingStepMeters = remainingStepMeters,
                        rollingSpeedMps = rollingSpeedMps
                    )
                }

                // Cadence Warnings (< 162 SPM)
                if (currentSportType == "Run") {
                    val minCadence = repository.getMinCadenceAlert()
                    val currentStep = activeWorkout?.steps?.getOrNull(currentStepIndex)
                    val isWorkOrRunStep = currentStep == null || currentStep.stepType == StepType.INTERVAL || currentStep.stepType == StepType.RUN
                    audioCoach.evaluateCadenceWarning(currentSpm, minCadence, totalDist, isWorkOrRunStep = isWorkOrRunStep)
                }

                updateNotification()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aerolap Tactical Pacer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live running pace, cadence, and split alerts"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        val state = _runState.value
        val distKm = "%.2f km".format(state.distanceMeters / 1000.0)
        val paceStr = formatPace(state.currentPaceSecPerKm)
        val targetPaceStr = if (state.targetPaceSecPerKm > 0) formatPace(state.targetPaceSecPerKm) else "Open"
        val timeStr = formatDuration(state.movingTimeSeconds)

        val title = "${state.currentStepTitle} • $distKm"
        val text = "Pace: $paceStr/km (Target: $targetPaceStr) | SPM: ${state.currentCadenceSpm} | Time: $timeStr"

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun formatPace(secPerKm: Int): String {
        if (secPerKm <= 0) return "--:--"
        val min = secPerKm / 60
        val sec = secPerKm % 60
        return "%d:%02d".format(min, sec)
    }

    private fun formatDuration(totalSec: Long): String {
        val hrs = totalSec / 3600
        val min = (totalSec % 3600) / 60
        val sec = totalSec % 60
        return if (hrs > 0) "%d:%02d:%02d".format(hrs, min, sec) else "%02d:%02d".format(min, sec)
    }

    override fun onDestroy() {
        super.onDestroy()
        autoPauseEngine.stop()
        cadenceTracker.stop()
        stopLocationUpdates()
        timerJob?.cancel()
        audioCoach.shutdown()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    companion object {
        const val ACTION_PREPARE = "ACTION_PREPARE"
        const val ACTION_CONFIRM_START = "ACTION_CONFIRM_START"
        const val ACTION_CANCEL_PRE_START = "ACTION_CANCEL_PRE_START"
        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_LAP = "ACTION_LAP"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_TOGGLE_MUTE = "ACTION_TOGGLE_MUTE"

        const val EXTRA_TARGET_PACE = "EXTRA_TARGET_PACE"
        const val EXTRA_WORKOUT_TYPE = "EXTRA_WORKOUT_TYPE"
        const val EXTRA_SPORT_TYPE = "EXTRA_SPORT_TYPE"
        const val EXTRA_WORKOUT_JSON = "EXTRA_WORKOUT_JSON"
        const val EXTRA_CUSTOM_NAME = "EXTRA_CUSTOM_NAME"

        private const val CHANNEL_ID = "aerolap_workout_channel"
        private const val NOTIFICATION_ID = 4040

        private const val ELEVATION_SMOOTHING_ALPHA = 0.15
        private const val ELEVATION_HYSTERESIS_THRESHOLD = 2.0
        private const val ELEVATION_MAX_VALID_SPIKE = 40.0

        private val _runState = MutableStateFlow(RunState())
        val runState: StateFlow<RunState> = _runState.asStateFlow()
    }
}
