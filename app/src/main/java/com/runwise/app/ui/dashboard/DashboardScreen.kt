package com.runwise.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runwise.app.data.model.*
import com.runwise.app.data.repository.RunwiseRepository
import com.runwise.app.ui.components.WorkoutRoutineBottomSheet
import com.runwise.app.ui.theme.*
import com.runwise.app.util.PlanWorkoutParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    repository: RunwiseRepository,
    onStartWorkout: (workout: CustomWorkout) -> Unit,
    onQuickStartActivity: (sportType: String) -> Unit,
    onNavigateToPlan: () -> Unit,
    onNavigateToTrends: () -> Unit,
    onNavigateToCalf: (exerciseName: String) -> Unit,
    onNavigateToWorkoutBuilder: () -> Unit
) {
    val syncData by repository.syncData.collectAsState()
    val isSyncing by repository.isSyncing.collectAsState()

    var routineSheetDay by remember { mutableStateOf<PlanDay?>(null) }

    val todayDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    val planDays = syncData?.plan?.weeks?.flatMap { it.days } ?: emptyList()
    val todayWorkout = planDays.find { it.date == todayDateStr }
        ?: planDays.firstOrNull()

    // Find next non-rest workout if today is rest
    val nextWorkout = if (todayWorkout?.category.equals("rest", ignoreCase = true)) {
        val todayIdx = planDays.indexOfFirst { it.date == todayDateStr }
        if (todayIdx >= 0 && todayIdx + 1 < planDays.size) planDays[todayIdx + 1] else null
    } else null

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Top Header: Athlete Profile & Quick Sync
        item {
            HeaderSection(
                athleteName = syncData?.athlete?.name ?: "Aashay Kadu",
                isSyncing = isSyncing,
                onSyncClick = { repository.sync() }
            )
        }

        // 2. Today's Planned Workout Hero Card (from active Plan)
        item {
            TodayPlanHeroCard(
                todayWorkout = todayWorkout,
                nextWorkout = nextWorkout,
                onStartTodayWorkout = { day ->
                    val workout = PlanWorkoutParser.parsePlanDayToWorkout(day)
                    onStartWorkout(workout)
                },
                onNavigateToExercise = onNavigateToCalf,
                onOpenRoutineSheet = { day -> routineSheetDay = day },
                onViewFullPlan = onNavigateToPlan
            )
        }

        // 3. Quick Record Activity (Strava-Style: Run, Walk, Hike)
        item {
            QuickRecordCard(
                onStartSport = { sport -> onQuickStartActivity(sport) }
            )
        }

        // 4. Running Plan Explorer Callout Card
        item {
            PlanExplorerCard(
                plan = syncData?.plan,
                onNavigateToPlan = onNavigateToPlan
            )
        }

        // 5. Physiological Readiness Metrics (CTL, ATL, TSB, ACWR)
        item {
            ReadinessMetricsGrid(readiness = syncData?.readiness)
        }

        // 6. Running Coach Advice Banner
        item {
            CoachAdviceBanner(readiness = syncData?.readiness)
        }

        // 7. Instant Workout Builder Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToWorkoutBuilder() },
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(AerolapBlue.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Timer, contentDescription = null, tint = AerolapBlue)
                        }
                        Column {
                            Text(
                                "Custom Workout Builder",
                                color = TextPrimaryDark,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                "Custom Laps, Step Pacing & Reps",
                                color = TextSecondaryDark,
                                fontSize = 13.sp
                            )
                        }
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMutedDark)
                }
            }
        }
    }

    // Dedicated Routine Modal Bottom Sheet
    val activeRoutineDay = routineSheetDay
    if (activeRoutineDay != null) {
        WorkoutRoutineBottomSheet(
            dayTitle = activeRoutineDay.title,
            dayDate = "${activeRoutineDay.dayName}, ${activeRoutineDay.date}",
            exercises = activeRoutineDay.exercises,
            onDismiss = { routineSheetDay = null },
            onNavigateToCatalog = { exName ->
                routineSheetDay = null
                onNavigateToCalf(exName)
            }
        )
    }
}

@Composable
fun HeaderSection(athleteName: String, isSyncing: Boolean, onSyncClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                "Aerolap Coach",
                color = AerolapBlue,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                athleteName,
                color = TextPrimaryDark,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        IconButton(
            onClick = onSyncClick,
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(CardDark)
        ) {
            if (isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = AerolapBlue,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.Sync, contentDescription = "Sync", tint = TextPrimaryDark)
            }
        }
    }
}

/**
 * Hero Card displaying Today's Planned Workout (as per the Running Plan)
 */
@Composable
fun TodayPlanHeroCard(
    todayWorkout: PlanDay?,
    nextWorkout: PlanDay?,
    onStartTodayWorkout: (PlanDay) -> Unit,
    onNavigateToExercise: (String) -> Unit,
    onOpenRoutineSheet: (PlanDay) -> Unit,
    onViewFullPlan: () -> Unit
) {
    val isRest = todayWorkout?.category.equals("rest", ignoreCase = true)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRest) CardDark else Color(0xFF22170F)
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.5.dp,
            color = if (isRest) CardBorderDark else AerolapBlue
        )
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header: "TODAY'S PLANNED WORKOUT" + Date
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isRest) TextMutedDark else AerolapBlue)
                    )
                    Text(
                        "TODAY'S PLANNED WORKOUT",
                        color = if (isRest) TextSecondaryDark else AerolapBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                todayWorkout?.badge?.let { badge ->
                    Text(
                        badge,
                        color = if (isRest) TextMutedDark else AerolapBlue,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isRest) SurfaceDark else AerolapBlue.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // Title & Date
            Column {
                Text(
                    todayWorkout?.title ?: "Scheduled Workout",
                    color = TextPrimaryDark,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    "${todayWorkout?.dayName ?: "Today"}, ${todayWorkout?.date ?: ""}",
                    color = TextSecondaryDark,
                    fontSize = 12.sp
                )
            }

            // Parameters (Distance, Pace, Target HR) if not rest day
            if (!isRest && todayWorkout != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceDark)
                        .padding(vertical = 10.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text("DISTANCE", color = TextMutedDark, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(todayWorkout.distance, color = TextPrimaryDark, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    }

                    Box(modifier = Modifier.width(1.dp).height(24.dp).background(CardBorderDark))

                    Column(
                        modifier = Modifier.weight(1.2f).padding(horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("TARGET PACE", color = TextMutedDark, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(todayWorkout.pace, color = PaceOnTarget, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    }

                    Box(modifier = Modifier.width(1.dp).height(24.dp).background(CardBorderDark))

                    Column(
                        modifier = Modifier.weight(1.3f),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text("TARGET HR", color = TextMutedDark, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(todayWorkout.targetHr, color = AerolapBlue, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }

            // Description
            Text(
                todayWorkout?.description ?: "Follow your training plan instructions.",
                color = TextSecondaryDark,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )

            // Assigned Protocol / Exercises if present
            if (todayWorkout?.exercises?.isNotEmpty() == true) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceDark.copy(alpha = 0.7f))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.FitnessCenter,
                                contentDescription = null,
                                tint = AerolapBlue,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                "Assigned Protocol (${todayWorkout.exercises.size}):",
                                color = TextSecondaryDark,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AerolapBlue.copy(alpha = 0.15f))
                                .clickable { onOpenRoutineSheet(todayWorkout) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                if (todayWorkout.exercises.size > 1) "View Routine (${todayWorkout.exercises.size}) ↗" else "View Details ↗",
                                color = AerolapBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    todayWorkout.exercises.forEach { ex ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(BackgroundDark.copy(alpha = 0.6f))
                                .clickable { onNavigateToExercise(ex.name) }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (ex.type.contains("Stretch", ignoreCase = true)) SportWalk else AerolapBlue)
                                )
                                Text(
                                    ex.name,
                                    color = TextPrimaryDark,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    ex.reps,
                                    color = AerolapBlue,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = "View exercise",
                                    tint = TextMutedDark,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // If today is a rest day, show Next Workout preview
            if (isRest && nextWorkout != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceDark)
                        .padding(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("UP NEXT:", color = AerolapBlue, fontSize = 11.sp, fontWeight = FontWeight.Black)
                            Text("${nextWorkout.dayName}, ${nextWorkout.date}", color = TextSecondaryDark, fontSize = 11.sp)
                        }
                        Text(
                            "${nextWorkout.title} (${nextWorkout.distance} @ ${nextWorkout.pace})",
                            color = TextPrimaryDark,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Action Buttons
            val isCompleted = todayWorkout?.badge?.equals("COMPLETED", ignoreCase = true) == true
            if (!isRest && todayWorkout != null) {
                if (isCompleted) {
                    OutlinedButton(
                        onClick = { onStartTodayWorkout(todayWorkout) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PaceOnTarget.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = PaceOnTarget.copy(alpha = 0.12f))
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = PaceOnTarget, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("WORKOUT COMPLETED", color = PaceOnTarget, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    }
                } else {
                    Button(
                        onClick = { onStartTodayWorkout(todayWorkout) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AerolapBlue)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("START TODAY'S WORKOUT", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 13.sp)
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { todayWorkout?.let { onOpenRoutineSheet(it) } ?: onNavigateToExercise("") },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimaryDark)
                    ) {
                        Icon(Icons.Default.SelfImprovement, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (todayWorkout?.exercises?.isNotEmpty() == true) "Protocol (${todayWorkout.exercises.size})" else "Recovery Exercises",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = onViewFullPlan,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
                    ) {
                        Text("View Full Plan", color = TextPrimaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * 1-Tap Quick Start Activity (Walk, Run, Hike) like Strava
 */
@Composable
fun QuickRecordCard(onStartSport: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.RadioButtonChecked, contentDescription = null, tint = AerolapBlue, modifier = Modifier.size(16.dp))
                    Text(
                        "QUICK RECORD ACTIVITY",
                        color = TextSecondaryDark,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
                Text("Instant GPS & Elevation", color = TextMutedDark, fontSize = 10.sp)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickSportButton(
                    title = "Run",
                    icon = Icons.Default.DirectionsRun,
                    color = SportRun,
                    modifier = Modifier.weight(1f),
                    onClick = { onStartSport("Run") }
                )
                QuickSportButton(
                    title = "Walk",
                    icon = Icons.Default.DirectionsWalk,
                    color = SportWalk,
                    modifier = Modifier.weight(1f),
                    onClick = { onStartSport("Walk") }
                )
                QuickSportButton(
                    title = "Hike",
                    icon = Icons.Default.Terrain,
                    color = SportHike,
                    modifier = Modifier.weight(1f),
                    onClick = { onStartSport("Hike") }
                )
            }
        }
    }
}

@Composable
fun QuickSportButton(
    title: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = title, tint = color, modifier = Modifier.size(24.dp))
            Text(title, color = TextPrimaryDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Plan Explorer Callout Card
 */
@Composable
fun PlanExplorerCard(plan: RunningPlan?, onNavigateToPlan: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToPlan() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(SportRun.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = SportRun)
                }
                Column {
                    Text(
                        "Date-Wise Running Plan",
                        color = TextPrimaryDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        "${plan?.weeks?.size ?: 3} Weeks Scheduled • Nov 02 – Nov 22",
                        color = TextSecondaryDark,
                        fontSize = 12.sp
                    )
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMutedDark)
        }
    }
}

@Composable
fun ReadinessMetricsGrid(readiness: ReadinessMetrics?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "PHYSIOLOGICAL READINESS",
                color = TextSecondaryDark,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricItem("Fitness (CTL)", String.format(Locale.US, "%.1f", readiness?.ctl ?: 30.4), "Long-term base", FitnessCtl)
                MetricItem("Fatigue (ATL)", String.format(Locale.US, "%.1f", readiness?.atl ?: 44.7), "Recent 7-day load", FatigueAtl)
                MetricItem("Form (TSB)", String.format(Locale.US, "%.1f", readiness?.tsb ?: -14.3), "Readiness balance", FormTsb)
                MetricItem("ACWR", String.format(Locale.US, "%.2f", readiness?.acwr ?: 1.47), "Injury risk ratio", FatigueAtl)
            }
        }
    }
}

@Composable
fun MetricItem(label: String, value: String, description: String, valueColor: Color) {
    Column {
        Text(label, color = TextMutedDark, fontSize = 10.sp)
        Text(value, color = valueColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(description, color = TextMutedDark, fontSize = 9.sp)
    }
}

@Composable
fun CoachAdviceBanner(readiness: ReadinessMetrics?) {
    val status = readiness?.status ?: "Overreaching"
    val headline = readiness?.headline ?: "High Fatigue & Elevated Injury Risk"
    val advice = readiness?.advice ?: "Acute workload is elevated relative to your historical base. Prioritize recovery and keep easy runs strictly in Zone 2."
    val recommendation = readiness?.recommendation ?: "Take a rest day or keep today's run strictly in Zone 1 (Recovery) under 30 minutes to absorb training."

    val isWarning = status.contains("Overreach", ignoreCase = true)
    val accentColor = if (isWarning) FatigueAtl else PaceOnTarget

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    if (isWarning) Icons.Default.Warning else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    headline,
                    color = TextPrimaryDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(advice, color = TextSecondaryDark, fontSize = 13.sp, lineHeight = 18.sp)

            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceDark)
                    .padding(10.dp)
            ) {
                Text(
                    "Recommendation: $recommendation",
                    color = accentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private fun parsePaceSec(paceStr: String): Int {
    val m = Regex("""(\d+):(\d+)""").find(paceStr) ?: return 375
    val min = m.groupValues[1].toIntOrNull() ?: 6
    val sec = m.groupValues[2].toIntOrNull() ?: 15
    return min * 60 + sec
}
