package com.runwise.app.ui.active

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runwise.app.service.GpsStatus
import com.runwise.app.service.LapSnapshot
import com.runwise.app.service.RunState
import com.runwise.app.service.TrackingService
import com.runwise.app.ui.components.LeafletMapView
import com.runwise.app.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ActiveRunScreen(
    onFinishRun: () -> Unit
) {
    val context = LocalContext.current
    val runState by TrackingService.runState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var isMuted by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var activityNameInput by remember { mutableStateOf("") }

    // If workout is neither active nor in pre-start preparation, exit
    if (!runState.isRunning && !runState.isPreStart) {
        LaunchedEffect(Unit) {
            onFinishRun()
        }
        return
    }

    // 1. PRE-START GPS ACQUISITION SCREEN
    if (runState.isPreStart) {
        PreStartGpsView(
            runState = runState,
            onStart = {
                val intent = Intent(context, TrackingService::class.java).apply {
                    action = TrackingService.ACTION_CONFIRM_START
                }
                context.startService(intent)
            },
            onCancel = {
                val intent = Intent(context, TrackingService::class.java).apply {
                    action = TrackingService.ACTION_CANCEL_PRE_START
                }
                context.startService(intent)
                onFinishRun()
            }
        )
        return
    }

    // 2. ACTIVE WORKOUT SCREEN WITH SWIPEABLE PAGES (HUD, MAP, LAPS)
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            ActiveRunTopBar(
                runState = runState,
                currentPage = pagerState.currentPage,
                isMuted = isMuted,
                onToggleMute = {
                    isMuted = !isMuted
                    sendServiceIntent(context, TrackingService.ACTION_TOGGLE_MUTE)
                },
                onSelectPage = { page ->
                    coroutineScope.launch { pagerState.animateScrollToPage(page) }
                }
            )
        },
        bottomBar = {
            ActiveRunBottomBar(
                runState = runState,
                currentPage = pagerState.currentPage,
                onSelectPage = { page ->
                    coroutineScope.launch { pagerState.animateScrollToPage(page) }
                },
                onLap = {
                    sendServiceIntent(context, TrackingService.ACTION_LAP)
                },
                onPauseResume = {
                    val action = if (runState.isPaused) TrackingService.ACTION_RESUME else TrackingService.ACTION_PAUSE
                    sendServiceIntent(context, action)
                },
                onFinish = {
                    sendServiceIntent(context, TrackingService.ACTION_PAUSE)
                    val defaultName = "${runState.sportType} - ${SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date())}"
                    activityNameInput = defaultName
                    showSaveDialog = true
                }
            )
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) { page ->
            when (page) {
                0 -> ActiveRunHudPage(
                    runState = runState,
                    onViewAllLaps = {
                        coroutineScope.launch { pagerState.animateScrollToPage(2) }
                    }
                )
                1 -> ActiveRunMapPage(
                    runState = runState
                )
                2 -> StravaLapsScreen(
                    runState = runState
                )
            }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = {
                Text("Save Activity", fontWeight = FontWeight.Bold, color = TextPrimaryDark)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Enter a name for this activity:", color = TextSecondaryDark, fontSize = 13.sp)
                    OutlinedTextField(
                        value = activityNameInput,
                        onValueChange = { activityNameInput = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AerolapBlue,
                            unfocusedBorderColor = CardBorderDark,
                            focusedTextColor = TextPrimaryDark,
                            unfocusedTextColor = TextPrimaryDark
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSaveDialog = false
                        val finalName = activityNameInput.ifBlank { "${runState.sportType} - Workout" }
                        val intent = Intent(context, TrackingService::class.java).apply {
                            action = TrackingService.ACTION_STOP
                            putExtra(TrackingService.EXTRA_CUSTOM_NAME, finalName)
                        }
                        context.startService(intent)
                        onFinishRun()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AerolapBlue)
                ) {
                    Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel", color = TextSecondaryDark)
                }
            },
            containerColor = CardDark
        )
    }
}

// ----------------------------------------------------
// TOP NAVIGATION & HEADER BAR
// ----------------------------------------------------

@Composable
fun ActiveRunTopBar(
    runState: RunState,
    currentPage: Int,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onSelectPage: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BackgroundDark)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val badgeColor = when (runState.sportType) {
                        "Run" -> AerolapBlue
                        "Walk" -> SportWalk
                        "Hike" -> SportHike
                        else -> AerolapBlue
                    }
                    Text(
                        runState.sportType.uppercase(Locale.US),
                        color = Color.Black,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeColor)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Text(
                        "${runState.workoutType} • LAP ${runState.currentLapNumber}/${runState.totalLapsCount}",
                        color = AerolapBlue,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
                Text(
                    runState.currentStepTitle,
                    color = TextPrimaryDark,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            IconButton(
                onClick = onToggleMute,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(SurfaceDark)
            ) {
                Icon(
                    if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Mute audio cues",
                    tint = if (isMuted) FatigueAtl else PaceOnTarget,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Page selector tabs: HUD, MAP, LAPS
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceDark)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val pages = listOf("HUD", "MAP", "LAPS")
            pages.forEachIndexed { index, label ->
                val isSelected = currentPage == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) AerolapBlue else Color.Transparent)
                        .clickable { onSelectPage(index) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) Color.White else TextSecondaryDark,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------
// BOTTOM BAR: STRAVA 3-DOT PAGER & CONTROLS
// ----------------------------------------------------

@Composable
fun ActiveRunBottomBar(
    runState: RunState,
    currentPage: Int,
    onSelectPage: (Int) -> Unit,
    onLap: () -> Unit,
    onPauseResume: () -> Unit,
    onFinish: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BackgroundDark)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Strava-style 3-dot pager pill container: [ • • • ]
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceDark)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { index ->
                    val isSelected = currentPage == index
                    Box(
                        modifier = Modifier
                            .size(if (isSelected) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) Color.White else TextMutedDark)
                            .clickable { onSelectPage(index) }
                    )
                }
            }
        }

        // Action Buttons
        if (runState.isPaused) {
            // Paused State: EXACT MATCH WITH STRAVA SCREENSHOT:
            // Orange "Resume" button & Black "Finish" button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onPauseResume,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = RoundedCornerShape(27.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AerolapBlue)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Resume", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                Button(
                    onClick = onFinish,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = RoundedCornerShape(27.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CardDark)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Finish", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        } else {
            // Active Recording State: Lap / Next Step, Pause, Finish
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onLap,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimaryDark)
                ) {
                    Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (runState.currentLapNumber < runState.totalLapsCount) "NEXT STEP" else "LAP",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                Button(
                    onClick = onPauseResume,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
                ) {
                    Icon(Icons.Default.Pause, contentDescription = null, tint = TextPrimaryDark, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("PAUSE", color = TextPrimaryDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Button(
                    onClick = onFinish,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FatigueAtl)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("FINISH", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

// ----------------------------------------------------
// PAGE 0: ACTIVE RUN HUD (DASHBOARD)
// ----------------------------------------------------

@Composable
fun ActiveRunHudPage(
    runState: RunState,
    onViewAllLaps: () -> Unit
) {
    val targetPace = runState.targetPaceSecPerKm
    val currentPace = runState.currentPaceSecPerKm

    val paceColor = when {
        runState.stepType == com.runwise.app.data.model.StepType.WARMUP ||
        runState.stepType == com.runwise.app.data.model.StepType.COOLDOWN ||
        runState.stepType == com.runwise.app.data.model.StepType.RECOVERY ||
        targetPace <= 0 -> PaceOnTarget
        currentPace <= 0 -> TextMutedDark
        currentPace < targetPace - 10 -> PaceTooFast
        currentPace > targetPace + 10 -> PaceTooSlow
        else -> PaceOnTarget
    }

    val paceStatusLabel = when {
        runState.stepType == com.runwise.app.data.model.StepType.WARMUP -> "WARM-UP (EASY / FREE PACE)"
        runState.stepType == com.runwise.app.data.model.StepType.COOLDOWN -> "COOL-DOWN (EASY / FREE PACE)"
        runState.stepType == com.runwise.app.data.model.StepType.RECOVERY -> "RECOVERY (REST / WALK)"
        targetPace <= 0 -> "FREE PACING"
        currentPace <= 0 -> "CALIBRATING GPS..."
        currentPace < targetPace - 10 -> "TOO FAST (EASE OFF)"
        currentPace > targetPace + 10 -> "TOO SLOW (PICK UP)"
        else -> "LOCKED ON TARGET ${formatPace(targetPace)}"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Live Pace Hero Display
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, CardBorderDark)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    paceStatusLabel,
                    color = paceColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(paceColor.copy(alpha = 0.15f))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    formatPace(currentPace),
                    color = paceColor,
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )

                Text(
                    "CURRENT KM PACE",
                    color = TextSecondaryDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                if (runState.lastSplitPaceSecPerKm > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Last Km: ${runState.lastSplitPaceFormatted} /km",
                        color = AerolapBlue,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (targetPace > 0) {
                    PaceTargetGauge(currentPace = currentPace, targetPace = targetPace)
                }
            }
        }

        // Current Kilometer Progress Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "KILOMETER ${runState.currentSplitNumber} PROGRESS",
                        color = TextSecondaryDark,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${runState.currentSplitDistanceMeters}m / 1000m",
                        color = TextPrimaryDark,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                val kmFraction = (runState.currentSplitDistanceMeters.toFloat() / 1000f).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { kmFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = AerolapBlue,
                    trackColor = SurfaceDark
                )

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Split Pace: ${formatPace(runState.currentSplitPaceSecPerKm)}/km",
                        color = AerolapBlue,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (runState.lastSplitPaceSecPerKm > 0) "Prev: ${runState.lastSplitPaceFormatted}/km" else "First Kilometer",
                        color = TextSecondaryDark,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Cadence & Step Progress
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.DirectionsRun,
                            contentDescription = null,
                            tint = if (runState.currentCadenceSpm < 162 && runState.currentCadenceSpm > 0 && runState.sportType == "Run") FatigueAtl else SportRun,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text("CONTINUOUS CADENCE", color = TextSecondaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (runState.currentCadenceSpm > 0) "${runState.currentCadenceSpm} SPM" else "Reading sensor...",
                                color = TextPrimaryDark,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (runState.sportType == "Run") "Target: ≥162 SPM" else "Standard Cadence", color = TextMutedDark, fontSize = 11.sp)
                        Text("Avg: ${runState.averageCadenceSpm} SPM", color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = CardBorderDark)
                Spacer(modifier = Modifier.height(10.dp))

                // Lap / Step Progress
                val targetMeters = runState.stepTargetDistanceMeters
                val targetSecs = runState.stepTargetDurationSeconds
                val isDistanceStep = targetMeters > 0
                val isDurationStep = targetSecs > 0

                val (stepProgressLabel, stepProgressValue, progress) = when {
                    isDistanceStep -> {
                        val p = (runState.lapDistanceMeters.toFloat() / targetMeters).coerceIn(0f, 1f)
                        Triple("STEP ${runState.currentLapNumber} PROGRESS", "${runState.lapDistanceMeters}m / ${targetMeters}m", p)
                    }
                    isDurationStep -> {
                        val p = (runState.lapTimeSeconds.toFloat() / targetSecs).coerceIn(0f, 1f)
                        Triple("STEP ${runState.currentLapNumber} REST", "${runState.lapTimeSeconds}s / ${targetSecs}s", p)
                    }
                    else -> {
                        val p = (runState.lapDistanceMeters.toFloat() / 500f).coerceIn(0f, 1f)
                        Triple("500M PROGRESS", "${runState.lapDistanceMeters}m / 500m", p)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stepProgressLabel,
                        color = TextSecondaryDark,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stepProgressValue,
                        color = TextPrimaryDark,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (isDurationStep) SportWalk else AerolapBlue,
                    trackColor = SurfaceDark
                )

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (isDurationStep) "Rest Left: ${maxOf(0L, targetSecs - runState.lapTimeSeconds)}s" else "Lap Pace: ${formatPace(runState.lapPaceSecPerKm)}/km",
                        color = TextSecondaryDark,
                        fontSize = 12.sp
                    )
                    Text(
                        "Lap Time: ${formatTimer(runState.lapTimeSeconds)}",
                        color = TextSecondaryDark,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Metrics Row: Total Distance, Moving Time, Elevation Gain
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("DISTANCE", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        String.format(Locale.US, "%.2f km", runState.distanceMeters / 1000.0),
                        color = TextPrimaryDark,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("TIME", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        formatTimer(runState.movingTimeSeconds),
                        color = TextPrimaryDark,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("ELEVATION", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "+${runState.elevationGainMeters} m",
                        color = PaceOnTarget,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }

        // Live Street Map Route Overlay Preview
        LiveRouteMap(
            routePoints = runState.routePoints,
            currentLocation = if (runState.currentLatitude != 0.0) Pair(runState.currentLatitude, runState.currentLongitude) else null
        )

        // LAPS & SPLITS SUMMARY CARD (Visible directly in HUD!)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.Default.FormatListNumbered,
                            contentDescription = null,
                            tint = AerolapBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "1 KM SPLITS",
                            color = TextSecondaryDark,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    TextButton(
                        onClick = onViewAllLaps,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "Full Screen →",
                            color = AerolapBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                StravaSplitsTable(
                    completedSplits = runState.completedSplits,
                    currentSplitNumber = runState.currentSplitNumber,
                    currentSplitDistanceMeters = runState.currentSplitDistanceMeters,
                    currentSplitPaceSecPerKm = runState.currentSplitPaceSecPerKm
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ----------------------------------------------------
// PAGE 1: FULL SCREEN ROUTE MAP
// ----------------------------------------------------

@Composable
fun ActiveRunMapPage(
    runState: RunState
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        // Full height live map
        LeafletMapView(
            coordinates = runState.routePoints,
            currentLocation = if (runState.currentLatitude != 0.0) Pair(runState.currentLatitude, runState.currentLongitude) else null,
            isLiveTracking = true,
            height = 600.dp,
            modifier = Modifier.fillMaxSize(),
            emptyMessage = "Tracking GPS trail..."
        )

        // Floating metrics overlay card at the top
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark.copy(alpha = 0.94f)),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("DISTANCE", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        String.format(Locale.US, "%.2f km", runState.distanceMeters / 1000.0),
                        color = TextPrimaryDark,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PACE", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        formatPace(runState.currentPaceSecPerKm),
                        color = TextPrimaryDark,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("TIME", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        formatTimer(runState.movingTimeSeconds),
                        color = TextPrimaryDark,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------
// PAGE 2: STRAVA-STYLE DEDICATED LAPS SCREEN
// Modeled after Screenshot_2026-10-28-08-23-55-690_com.strava.jpg
// ----------------------------------------------------

@Composable
fun StravaLapsScreen(
    runState: RunState
) {
    var selectedTab by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Paused / Stopped Banner (matching Strava screenshot yellow header)
        if (runState.isPaused) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFC500))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp, horizontal = 16.dp)
                ) {
                    Text(
                        "Stopped",
                        color = Color(0xFF1E222A),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (selectedTab == 0) "1 KM Splits" else "Laps",
                        color = Color(0xFF0F1115),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        } else {
            // Recording banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(PaceOnTarget)
                        )
                        Text(
                            "RECORDING",
                            color = PaceOnTarget,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    Text(
                        if (selectedTab == 0) "1 KM Splits" else "Laps",
                        color = TextPrimaryDark,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Text(
                    if (selectedTab == 0) "${runState.completedSplits.size + 1} Splits" else "${runState.completedLaps.size + 1} Laps",
                    color = TextSecondaryDark,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Tab Selector Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceDark)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selectedTab == 0) CardDark else Color.Transparent)
                    .clickable { selectedTab = 0 }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "1 KM SPLITS (${runState.completedSplits.size + 1})",
                    color = if (selectedTab == 0) TextPrimaryDark else TextSecondaryDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selectedTab == 1) CardDark else Color.Transparent)
                    .clickable { selectedTab = 1 }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "WORKOUT LAPS (${runState.completedLaps.size + 1})",
                    color = if (selectedTab == 1) TextPrimaryDark else TextSecondaryDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Dedicated Table Container
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                if (selectedTab == 0) {
                    StravaSplitsTable(
                        completedSplits = runState.completedSplits,
                        currentSplitNumber = runState.currentSplitNumber,
                        currentSplitDistanceMeters = runState.currentSplitDistanceMeters,
                        currentSplitPaceSecPerKm = runState.currentSplitPaceSecPerKm
                    )

                    if (runState.completedSplits.isEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Kilometer 1 in progress. 1 km splits record automatically at each 1,000m mark.",
                            color = TextMutedDark,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    StravaLapsTable(
                        completedLaps = runState.completedLaps,
                        currentLapNumber = runState.currentLapNumber,
                        currentLapDistanceMeters = runState.lapDistanceMeters,
                        currentLapPaceSecPerKm = runState.lapPaceSecPerKm
                    )

                    if (runState.completedLaps.isEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Lap 1 in progress. Completed laps are recorded after each structured workout step.",
                            color = TextMutedDark,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ----------------------------------------------------
// REUSABLE STRAVA SPLITS & LAPS TABLES
// ----------------------------------------------------

@Composable
fun StravaSplitsTable(
    completedSplits: List<com.runwise.app.data.model.ActivitySplitItem>,
    currentSplitNumber: Int,
    currentSplitDistanceMeters: Int,
    currentSplitPaceSecPerKm: Int,
    modifier: Modifier = Modifier
) {
    val allPaces = (completedSplits.map { it.paceSecPerKm } + listOf(currentSplitPaceSecPerKm)).filter { it > 0 }
    val maxSpeed = if (allPaces.isNotEmpty()) {
        allPaces.maxOf { 1000f / it }
    } else {
        3.5f
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Km",
                color = TextSecondaryDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(38.dp)
            )
            Text(
                text = "Dist",
                color = TextSecondaryDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(54.dp)
            )
            Text(
                text = "Pace",
                color = TextSecondaryDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider(color = CardBorderDark, thickness = 0.8.dp)
        Spacer(modifier = Modifier.height(8.dp))

        completedSplits.forEach { split ->
            StravaLapRow(
                lapNumber = split.splitNumber,
                distanceMeters = split.distanceMeters,
                paceSecPerKm = split.paceSecPerKm,
                paceFormatted = split.paceFormatted,
                maxSpeed = maxSpeed,
                isActive = false
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        StravaLapRow(
            lapNumber = currentSplitNumber,
            distanceMeters = currentSplitDistanceMeters,
            paceSecPerKm = currentSplitPaceSecPerKm,
            paceFormatted = if (currentSplitPaceSecPerKm > 0) formatPace(currentSplitPaceSecPerKm) else "--:--",
            maxSpeed = maxSpeed,
            isActive = true
        )
    }
}

@Composable
fun StravaLapsTable(
    completedLaps: List<LapSnapshot>,
    currentLapNumber: Int,
    currentLapDistanceMeters: Int,
    currentLapPaceSecPerKm: Int,
    modifier: Modifier = Modifier
) {
    // Calculate max speed for proportional horizontal bar width scaling
    val allPaces = (completedLaps.map { it.paceSecPerKm } + listOf(currentLapPaceSecPerKm)).filter { it > 0 }
    val maxSpeed = if (allPaces.isNotEmpty()) {
        allPaces.maxOf { 1000f / it }
    } else {
        3.5f // Default reference speed (~4:45 min/km)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Table Header: Lap, Km, Avg pace
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Lap",
                color = TextSecondaryDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(38.dp)
            )
            Text(
                text = "Km",
                color = TextSecondaryDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(54.dp)
            )
            Text(
                text = "Avg pace",
                color = TextSecondaryDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider(color = CardBorderDark, thickness = 0.8.dp)
        Spacer(modifier = Modifier.height(8.dp))

        // Render Completed Laps
        completedLaps.forEach { lap ->
            StravaLapRow(
                lapNumber = lap.lapNumber,
                distanceMeters = lap.distanceMeters,
                paceSecPerKm = lap.paceSecPerKm,
                paceFormatted = lap.paceFormatted,
                maxSpeed = maxSpeed,
                isActive = false
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Render Active In-Progress Lap
        StravaLapRow(
            lapNumber = currentLapNumber,
            distanceMeters = currentLapDistanceMeters,
            paceSecPerKm = currentLapPaceSecPerKm,
            paceFormatted = if (currentLapPaceSecPerKm > 0) formatPace(currentLapPaceSecPerKm) else "--:--",
            maxSpeed = maxSpeed,
            isActive = true
        )
    }
}

@Composable
fun StravaLapRow(
    lapNumber: Int,
    distanceMeters: Int,
    paceSecPerKm: Int,
    paceFormatted: String,
    maxSpeed: Float,
    isActive: Boolean
) {
    val kmStr = String.format(Locale.US, "%.2f", distanceMeters / 1000.0)

    val speed = if (paceSecPerKm > 0) 1000f / paceSecPerKm else 0f
    val fraction = if (maxSpeed > 0f && speed > 0f) {
        (speed / maxSpeed).coerceIn(0.12f, 1f)
    } else if (isActive) {
        0.75f // Default active bar width before pace calculation
    } else {
        0.15f
    }

    val completedBarColor = Color(0xFF90CAF9) // Soft Strava light blue
    val activeBarColor = Color(0xFF0066FE)    // Vibrant Strava Royal Blue

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isActive) Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0066FE).copy(alpha = 0.12f))
                    .padding(vertical = 4.dp, horizontal = 4.dp)
                else Modifier.padding(vertical = 2.dp, horizontal = 4.dp)
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Lap Number
        Text(
            text = "$lapNumber",
            color = TextPrimaryDark,
            fontSize = 15.sp,
            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
            modifier = Modifier.width(38.dp)
        )

        // Distance in Km
        Text(
            text = kmStr,
            color = TextPrimaryDark,
            fontSize = 15.sp,
            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
            modifier = Modifier.width(54.dp)
        )

        // Horizontal Bar Area + Pace
        BoxWithConstraints(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            val totalWidth = maxWidth
            val maxBarWidth = (totalWidth - 58.dp).coerceAtLeast(20.dp)
            val barWidth = maxBarWidth * fraction

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isActive) {
                    // Strava active lap left orange vertical accent tick
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(26.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(AerolapBlue)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Horizontal pace bar
                Box(
                    modifier = Modifier
                        .width(barWidth)
                        .height(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isActive) activeBarColor else completedBarColor)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Average Pace label
                Text(
                    text = paceFormatted,
                    color = TextPrimaryDark,
                    fontSize = 14.sp,
                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ----------------------------------------------------
// PRE-START GPS ACQUISITION COMPONENT
// ----------------------------------------------------

private data class GpsVisualState(
    val bg: Color,
    val border: Color,
    val icon: ImageVector,
    val color: Color,
    val title: String,
    val subtitle: String
)

@Composable
fun PreStartGpsView(
    runState: RunState,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    val gpsStatus = runState.gpsStatus
    val accuracy = runState.gpsAccuracyMeters
    val isLocked = gpsStatus == GpsStatus.LOCKED

    val visual = when (gpsStatus) {
        GpsStatus.LOCKED -> GpsVisualState(
            bg = Color(0xFF0F2B1D),
            border = Color(0xFF22C55E),
            icon = Icons.Default.CheckCircle,
            color = Color(0xFF22C55E),
            title = "GPS READY & LOCKED",
            subtitle = "High accuracy GPS locked (±${accuracy.roundToInt()}m) • Ready to record"
        )
        GpsStatus.DISABLED -> GpsVisualState(
            bg = Color(0xFF2B1010),
            border = Color(0xFFEF4444),
            icon = Icons.Default.LocationOff,
            color = Color(0xFFEF4444),
            title = "GPS LOCATION DISABLED",
            subtitle = "Please enable Location in device settings"
        )
        else -> GpsVisualState(
            bg = Color(0xFF2B200D),
            border = Color(0xFFEAB308),
            icon = Icons.Default.GpsNotFixed,
            color = Color(0xFFEAB308),
            title = "ACQUIRING GPS FIX...",
            subtitle = if (accuracy < 100) "Refining accuracy: ±${accuracy.roundToInt()}m (locking ≤ 20m)" else "Searching for GPS satellites..."
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 40.dp)
        ) {
            val badgeColor = when (runState.sportType) {
                "Run" -> AerolapBlue
                "Walk" -> SportWalk
                "Hike" -> SportHike
                else -> AerolapBlue
            }

            Text(
                runState.sportType.uppercase(Locale.US),
                color = Color.Black,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(badgeColor)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                runState.workoutType,
                color = TextPrimaryDark,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                if (runState.targetPaceSecPerKm > 0) "Target Pace: ${formatPace(runState.targetPaceSecPerKm)} min/km" else "Self-Paced Free Workout",
                color = TextSecondaryDark,
                fontSize = 14.sp
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = visual.bg),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, visual.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    visual.icon,
                    contentDescription = null,
                    tint = visual.color,
                    modifier = Modifier.size(56.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    visual.title,
                    color = visual.color,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    visual.subtitle,
                    color = TextPrimaryDark,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )

                if (gpsStatus == GpsStatus.SEARCHING) {
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = visual.color,
                        strokeWidth = 2.5.dp
                    )
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLocked) PaceOnTarget else AerolapBlue
                )
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isLocked) "START WORKOUT" else "START ANYWAY",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp
                )
            }

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondaryDark)
            ) {
                Text("Cancel", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ----------------------------------------------------
// LIVE ROUTE MAP PREVIEW
// ----------------------------------------------------

@Composable
fun LiveRouteMap(
    routePoints: List<Pair<Double, Double>>,
    currentLocation: Pair<Double, Double>?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.Default.Place,
                        contentDescription = null,
                        tint = AerolapBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "LIVE ROUTE MAP",
                        color = TextSecondaryDark,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
                Text(
                    if (routePoints.size >= 2) "${routePoints.size} GPS trackpoints" else "Acquiring...",
                    color = TextMutedDark,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            LeafletMapView(
                coordinates = routePoints,
                currentLocation = currentLocation,
                isLiveTracking = true,
                height = 150.dp,
                emptyMessage = "Tracking GPS trail as you move..."
            )
        }
    }
}

// ----------------------------------------------------
// TARGET PACE GAUGE COMPONENT
// ----------------------------------------------------

@Composable
fun PaceTargetGauge(currentPace: Int, targetPace: Int) {
    if (targetPace <= 0) return

    val diff = (currentPace - targetPace).coerceIn(-40, 40)
    val gaugeFraction = ((diff + 40) / 80f).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("FAST (${formatPace(targetPace - 10)})", color = PaceTooFast, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("TARGET (${formatPace(targetPace)})", color = PaceOnTarget, fontSize = 11.sp, fontWeight = FontWeight.Black)
            Text("SLOW (${formatPace(targetPace + 10)})", color = PaceTooSlow, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(SurfaceDark)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.25f)
                    .align(Alignment.Center)
                    .background(PaceOnTarget.copy(alpha = 0.4f))
            )

            if (currentPace > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(4.dp)
                        .align(Alignment.CenterStart)
                        .offset(x = (gaugeFraction * 260).dp)
                        .background(Color.White)
                )
            }
        }
    }
}

private fun sendServiceIntent(context: Context, action: String) {
    val intent = Intent(context, TrackingService::class.java).apply {
        this.action = action
    }
    context.startService(intent)
}

private fun formatPace(secPerKm: Int): String {
    if (secPerKm <= 0) return "--:--"
    val m = secPerKm / 60
    val s = secPerKm % 60
    return "%d:%02d".format(m, s)
}

private fun formatTimer(totalSeconds: Long): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}
