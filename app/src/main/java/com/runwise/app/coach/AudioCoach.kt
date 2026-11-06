package com.runwise.app.coach

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class AudioCoach(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var isMuted = false

    private val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var toneGenerator: ToneGenerator? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Pace Guidance Classifier (Strava usy.java:1523-1570 & clean config)
    enum class PaceClassification { FAST, ON_PACE, SLOW }

    private var currentPaceClass = PaceClassification.ON_PACE
    private var stateStartMs = 0L
    private var paceAlertCount = 0
    private var hadActiveAlertInState = false
    private var lastPaceWarningTime = 0L
    private var lastCadenceWarningTime = 0L

    private val persistenceThresholdSec = 30L // 30 seconds continuous out-of-band required
    private val enterToleranceSec = 30        // Alert when deviation >= 30s
    private val exitToleranceSec = 20         // Clear/exit alert when deviation < 20s

    private var activeFocusRequest: AudioFocusRequest? = null

    init {
        tts = TextToSpeech(context.applicationContext, this)
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (_: Exception) {}
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { engine ->
                val result = engine.setLanguage(Locale.US)
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setSpeechRate(1.05f) // Clear, slightly brisk coaching cadence
                    engine.setPitch(1.0f)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            abandonAudioDucking()
                        }
                        @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, -1)"))
                        override fun onError(utteranceId: String?) {
                            abandonAudioDucking()
                        }
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            abandonAudioDucking()
                        }
                    })
                    isTtsReady = true
                }
            }
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
    }

    fun isMuted(): Boolean = isMuted

    fun resetStepPaceTracking() {
        currentPaceClass = PaceClassification.ON_PACE
        stateStartMs = 0L
        paceAlertCount = 0
        hadActiveAlertInState = false
        resetStepCadenceTracking()
    }

    /**
     * Vibrate phone for 2.0 seconds continuously (Strava u0o.b auto-transition).
     */
    fun vibrate2Seconds() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(2000L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(2000L)
            }
        } catch (_: Exception) {}
    }

    /**
     * Short haptic pulse (e.g. for countdown chime tick).
     */
    fun vibratePulse(durationMs: Long = 150L) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    /**
     * Play 3s countdown chime tone + haptic pulse.
     */
    fun playCountdownChime() {
        if (isMuted) return
        try {
            vibratePulse(150L)
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
        } catch (_: Exception) {}
    }

    /**
     * Play manual step transition chime tone.
     */
    fun playTransitionChime() {
        if (isMuted) return
        try {
            vibratePulse(200L)
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 250)
        } catch (_: Exception) {}
    }

    /**
     * Halfway milestone cue (Strava eb70.c / usy.java:1472)
     */
    fun announceHalfway(targetPaceSecPerKm: Int = 0) {
        if (isMuted || !isTtsReady) return
        val paceText = if (targetPaceSecPerKm > 0) {
            " Target pace is ${formatPaceSpeech(targetPaceSecPerKm)}."
        } else ""
        speak("Halfway there.$paceText", queueMode = TextToSpeech.QUEUE_ADD)
    }

    /**
     * 10 seconds remaining warning (Strava eb70.a)
     */
    fun announceTenSecondsRemaining(nextStepDescription: String) {
        if (isMuted || !isTtsReady) return
        val nextText = if (nextStepDescription.isNotBlank()) " Next: $nextStepDescription." else ""
        speak("10 seconds remaining.$nextText", queueMode = TextToSpeech.QUEUE_ADD)
    }

    /**
     * Distance step "Almost there" cue (Strava usy.java:1488)
     */
    fun announceAlmostThere(nextStepDescription: String) {
        if (isMuted || !isTtsReady) return
        val nextText = if (nextStepDescription.isNotBlank()) " Next: $nextStepDescription." else ""
        speak("Almost there.$nextText", queueMode = TextToSpeech.QUEUE_ADD)
    }

    /**
     * Automatic Step Transition (Strava u0o.b with z == true):
     * 1. 2000ms vibration.
     * 2. NO chime (already played at T-3s).
     * 3. Immediate TTS via QUEUE_ADD without 1000ms delay.
     * 4. Does NOT repeat next step description (already spoken at T-10s).
     */
    fun announceStepTransitionAutomatic(
        stepNumber: Int,
        totalSteps: Int,
        stepTitle: String,
        stepDescription: String = "",
        splitSummary: String? = null,
        isWorkTransition: Boolean = false,
        isRecoveryTransition: Boolean = false
    ) {
        vibrate2Seconds()
        if (isMuted || !isTtsReady) return

        val message = buildString {
            if (!splitSummary.isNullOrBlank()) {
                append("$splitSummary. ")
            }
            if (isWorkTransition) {
                append("Work! ")
            } else if (isRecoveryTransition) {
                append("Recovery. ")
            }
            if (stepNumber == totalSteps) {
                append("Final step: ")
            }
            if (stepDescription.isNotBlank()) {
                append(stepDescription)
            } else {
                append(stepTitle)
            }
        }

        if (message.isNotBlank()) {
            speak(message.trim(), queueMode = TextToSpeech.QUEUE_ADD)
        }
    }

    /**
     * Manual Step Transition (Strava u0o.b with z == false):
     * 1. Flushes existing TTS.
     * 2. Plays transition chime tone + 2000ms vibration.
     * 3. Prepends next step description with 1000ms post-delay.
     */
    fun announceStepTransitionManual(
        stepNumber: Int,
        totalSteps: Int,
        stepTitle: String,
        targetPaceSecPerKm: Int,
        distanceMeters: Int
    ) {
        playTransitionChime()
        vibrate2Seconds()
        if (isMuted || !isTtsReady) return

        mainHandler.postDelayed({
            val pacePart = if (targetPaceSecPerKm > 0) "Target pace ${formatPaceSpeech(targetPaceSecPerKm)}" else ""
            val distPart = if (distanceMeters > 0) "$distanceMeters meters" else ""
            speak("Step $stepNumber of $totalSteps: $stepTitle. $distPart. $pacePart.".trim(), queueMode = TextToSpeech.QUEUE_FLUSH)
        }, 1000L)
    }

    /**
     * Intermediate checkpoint announcement (e.g. 500m progress check)
     */
    fun announceCheckpoint(distanceMeters: Int, lapPaceSecPerKm: Int) {
        if (isMuted || !isTtsReady) return
        val lapPaceStr = formatPaceSpeech(lapPaceSecPerKm)
        val text = "$distanceMeters meters. Last 500 meters, $lapPaceStr."
        speak(text)
    }

    /**
     * Complete kilometer milestone announcement (1.0 km, 2.0 km, 3.0 km, etc.)
     */
    fun announceKilometerMilestone(
        kmNumber: Int,
        splitPaceSecPerKm: Int,
        totalElapsedSeconds: Long,
        avgPaceSecPerKm: Int
    ) {
        if (isMuted || !isTtsReady) return
        val splitPaceStr = formatPaceSpeech(splitPaceSecPerKm)
        val elapsedStr = formatTimeSpeech(totalElapsedSeconds)
        val avgPaceStr = formatPaceSpeech(avgPaceSecPerKm)
        val text = "Kilometer $kmNumber complete. Split pace, $splitPaceStr. Total time, $elapsedStr. Average pace, $avgPaceStr."
        speak(text)
    }

    /**
     * Announce split every 500m (retained for custom split intervals)
     */
    fun announceSplit(splitNumber: Int, distanceMeters: Int, lapPaceSecPerKm: Int, totalElapsedSeconds: Long) {
        if (isMuted || !isTtsReady) return
        val lapPaceStr = formatPaceSpeech(lapPaceSecPerKm)
        val elapsedStr = formatTimeSpeech(totalElapsedSeconds)
        val text = "Split $splitNumber. $distanceMeters meters. Last 500 meters, $lapPaceStr. Total time, $elapsedStr."
        speak(text)
    }

    /**
     * Target Pace Guidance & Escalating Cooldown Throttler (Strava usy.java:1523-1570):
     * - 30 seconds continuous persistence required for 1st alert
     * - Escalating cooldown: 60s -> 180s (3m) -> 300s (5m) -> 420s (7m)
     * - Hard capped at maximum 4 alerts per state
     * - Back-on-pace announcement: "You're back on pace." spoken once upon recovery
     * - End-of-step suppression (<10s remaining time or <10s distance ETA)
     * - Startup suppression: first 200m, first 20s after resume, first 25s of new step
     */
    fun evaluatePaceWarning(
        rollingPaceSecPerKm: Int,
        targetPaceSecPerKm: Int,
        currentDistanceMeters: Int,
        secondsSinceResume: Long = 999L,
        secondsSinceStepStart: Long = 999L,
        remainingStepSeconds: Long? = null,
        remainingStepMeters: Double? = null,
        rollingSpeedMps: Double? = null
    ) {
        if (isMuted || !isTtsReady || targetPaceSecPerKm <= 0) return

        // 1. Startup suppression
        if (currentDistanceMeters < 200) return   // Suppress first 200m
        if (secondsSinceResume < 20L) return      // Suppress 20s after resume
        if (secondsSinceStepStart < 25L) return   // Suppress 25s after new step starts
        if (rollingPaceSecPerKm < 150 || rollingPaceSecPerKm > 720) return

        // 2. End-of-step suppression (Strava usy.java:1555)
        if (remainingStepSeconds != null && remainingStepSeconds < 10L) return
        if (remainingStepMeters != null && rollingSpeedMps != null && rollingSpeedMps > 0.0) {
            val etaSec = remainingStepMeters / rollingSpeedMps
            if (etaSec < 10.0) return
        }

        val now = SystemClock.elapsedRealtime()
        val fastDelta = targetPaceSecPerKm - rollingPaceSecPerKm
        val slowDelta = rollingPaceSecPerKm - targetPaceSecPerKm

        // 3. Classification with asymmetric hysteresis (enter at 30s deviation, exit at <20s)
        val newClass = when {
            fastDelta >= enterToleranceSec -> PaceClassification.FAST
            slowDelta >= enterToleranceSec -> PaceClassification.SLOW
            fastDelta < exitToleranceSec && slowDelta < exitToleranceSec -> PaceClassification.ON_PACE
            else -> currentPaceClass // Retain current in deadband
        }

        // 4. State transition
        if (newClass != currentPaceClass) {
            val prevClass = currentPaceClass
            currentPaceClass = newClass
            stateStartMs = now

            if (newClass == PaceClassification.ON_PACE) {
                // Recovered to target! Acknowledge once
                if ((prevClass == PaceClassification.FAST || prevClass == PaceClassification.SLOW) && hadActiveAlertInState) {
                    speak("You're back on pace.", queueMode = TextToSpeech.QUEUE_ADD)
                }
                paceAlertCount = 0
                hadActiveAlertInState = false
                return
            } else {
                paceAlertCount = 0
                hadActiveAlertInState = false
            }
        }

        if (currentPaceClass == PaceClassification.ON_PACE) return

        // 5. Throttling Schedule:
        // Alert 1 (count 0): elapsedInState >= 30s && cooldown >= 60s
        // Alert 2 (count 1): elapsedInState >= 180s (3 min)
        // Alert 3 (count 2): elapsedInState >= 300s (5 min)
        // Alert 4 (count 3): elapsedInState >= 420s (7 min)
        // Capped after count 4.
        val elapsedInStateSec = (now - stateStartMs) / 1000L
        val cooldownMs = now - lastPaceWarningTime

        val shouldAlert = when (paceAlertCount) {
            0 -> elapsedInStateSec >= persistenceThresholdSec && cooldownMs >= 60_000L
            1 -> elapsedInStateSec >= 180L && paceAlertCount < 4
            2 -> elapsedInStateSec >= 300L && paceAlertCount < 4
            3 -> elapsedInStateSec >= 420L && paceAlertCount < 4
            else -> false
        }

        if (shouldAlert) {
            lastPaceWarningTime = now
            paceAlertCount++
            hadActiveAlertInState = true

            val targetStr = formatPaceSpeech(targetPaceSecPerKm)
            val currentStr = formatPaceSpeech(rollingPaceSecPerKm)

            if (currentPaceClass == PaceClassification.FAST) {
                speak("Slow down. Your pace is $currentStr, aim for $targetStr.", queueMode = TextToSpeech.QUEUE_ADD)
            } else {
                speak("Pick it up. Your pace is $currentStr, aim for $targetStr.", queueMode = TextToSpeech.QUEUE_ADD)
            }
        }
    }

    private var cadenceOutOfBandStartMs = 0L
    private var cadenceAlertCount = 0

    fun resetStepCadenceTracking() {
        cadenceOutOfBandStartMs = 0L
        cadenceAlertCount = 0
    }

    /**
     * Check continuous cadence against target (e.g. alert if < 162 SPM).
     * Synchronized with pace alert guidelines:
     * 1. Only alerts during running/work steps (suppressed in recovery/warmup/cooldown).
     * 2. Suppressed if cadence <= 125 SPM (runner is walking or stopped).
     * 3. Requires continuous out-of-band persistence >= 25 seconds.
     * 4. Escalating cooldown: 60s -> 180s -> 300s.
     * 5. Hard capped at 3 alerts maximum.
     * 6. Spaced at least 30s apart from pace alerts.
     */
    fun evaluateCadenceWarning(
        currentSpm: Int,
        minSpmThreshold: Int = 162,
        currentDistanceMeters: Int,
        isWorkOrRunStep: Boolean = true
    ) {
        if (isMuted || !isTtsReady) return
        if (!isWorkOrRunStep) {
            cadenceOutOfBandStartMs = 0L
            return
        }
        if (currentDistanceMeters < 200) return
        if (currentSpm <= 125) { // Walking or stopped
            cadenceOutOfBandStartMs = 0L
            return
        }

        val now = SystemClock.elapsedRealtime()

        // Spacing from pace alert: at least 30s
        if (now - lastPaceWarningTime < 30_000L) return

        if (currentSpm < minSpmThreshold) {
            if (cadenceOutOfBandStartMs == 0L) {
                cadenceOutOfBandStartMs = now
            }
            val elapsedLowSec = (now - cadenceOutOfBandStartMs) / 1000L
            val cooldownMs = now - lastCadenceWarningTime

            val shouldAlert = when (cadenceAlertCount) {
                0 -> elapsedLowSec >= 25L && cooldownMs >= 60_000L
                1 -> elapsedLowSec >= 180L && cadenceAlertCount < 3
                2 -> elapsedLowSec >= 300L && cadenceAlertCount < 3
                else -> false // Hard cap at 3 alerts
            }

            if (shouldAlert) {
                lastCadenceWarningTime = now
                cadenceAlertCount++
                speak("Cadence low: $currentSpm steps per minute. Quick, light foot turnover.", queueMode = TextToSpeech.QUEUE_ADD)
            }
        } else {
            // Recovered above target cadence
            cadenceOutOfBandStartMs = 0L
        }
    }

    fun speakCustom(message: String) {
        if (isMuted || !isTtsReady) return
        speak(message)
    }

    private fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        try {
            requestAudioDucking()
            tts?.speak(text, queueMode, null, "runwise_coach_${System.currentTimeMillis()}")
        } catch (_: Exception) {}
    }

    private fun requestAudioDucking() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(false)
                .build()

            activeFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioDucking() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                activeFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
    }

    private fun formatPaceSpeech(secPerKm: Int): String {
        val min = secPerKm / 60
        val sec = secPerKm % 60
        return if (sec == 0) "$min minutes per kilometer" else "$min $sec per kilometer"
    }

    private fun formatTimeSpeech(totalSec: Long): String {
        val min = totalSec / 60
        val sec = totalSec % 60
        return if (min == 0L) "$sec seconds" else "$min minutes $sec seconds"
    }

    fun shutdown() {
        try {
            mainHandler.removeCallbacksAndMessages(null)
            toneGenerator?.release()
            toneGenerator = null
            tts?.stop()
            tts?.shutdown()
            tts = null
            isTtsReady = false
        } catch (_: Exception) {}
    }
}
