package com.runwise.app.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runwise.app.data.model.*
import com.runwise.app.data.repository.RunwiseRepository
import com.runwise.app.ui.theme.*
import com.runwise.app.util.PlanWorkoutParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    repository: RunwiseRepository,
    onStartWorkout: (workout: CustomWorkout) -> Unit,
    onNavigateToExercises: () -> Unit,
    onBack: () -> Unit
) {
    val syncData by repository.syncData.collectAsState()
    val plan = syncData?.plan

    val todayDateStr = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    val weeks = plan?.weeks ?: emptyList()
    var selectedWeekIndex by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "RUNNING PLAN EXPLORER",
                            color = RunwiseOrange,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            plan?.goalTitle ?: "Target 10K Running Plan",
                            color = TextPrimaryDark,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimaryDark)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        },
        containerColor = BackgroundDark
    ) { paddingValues ->
        if (weeks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("No plan data synced yet. Tap Sync in Settings.", color = TextSecondaryDark)
            }
        } else {
            val currentWeek = weeks.getOrElse(selectedWeekIndex) { weeks.first() }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 10.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Week selector chips
                item {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(weeks.indices.toList()) { idx ->
                            val wk = weeks[idx]
                            val isSelected = idx == selectedWeekIndex
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedWeekIndex = idx },
                                label = {
                                    Text(
                                        "Week ${wk.number} (${wk.dates})",
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = RunwiseOrange,
                                    selectedLabelColor = Color.White,
                                    containerColor = CardDark,
                                    labelColor = TextSecondaryDark
                                ),
                                shape = RoundedCornerShape(18.dp)
                            )
                        }
                    }
                }

                // 2. Week Overview Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardDark),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    currentWeek.title,
                                    color = TextPrimaryDark,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    currentWeek.dates,
                                    color = RunwiseOrange,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                currentWeek.focus,
                                color = TextSecondaryDark,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Target: ${currentWeek.targetDistance}", color = PaceOnTarget, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("TSB: ${currentWeek.projectedTsb}", color = TextMutedDark, fontSize = 11.sp)
                            }
                        }
                    }
                }

                // 3. Days in Week
                items(currentWeek.days) { day ->
                    val isToday = day.date == todayDateStr
                    PlanDayCard(
                        day = day,
                        isToday = isToday,
                        onStartWorkout = {
                            val workout = PlanWorkoutParser.parsePlanDayToWorkout(day)
                            onStartWorkout(workout)
                        },
                        onViewExercises = onNavigateToExercises
                    )
                }
            }
        }
    }
}

@Composable
fun PlanDayCard(
    day: PlanDay,
    isToday: Boolean,
    onStartWorkout: () -> Unit,
    onViewExercises: () -> Unit
) {
    val isRest = day.category.equals("rest", ignoreCase = true)
    val isRace = day.category.equals("race", ignoreCase = true)
    val isWorkout = day.category.equals("workout", ignoreCase = true) || day.badge.contains("PACE LOCK", ignoreCase = true)

    val badgeColor = when {
        isRace -> Color(0xFFEF4444)
        isWorkout -> RunwiseOrange
        isRest -> TextMutedDark
        else -> SportRun
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isToday) Color(0xFF22170F) else CardDark
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isToday) 2.dp else 1.dp,
            color = if (isToday) RunwiseOrange else CardBorderDark
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Day Header: Date, Name, Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Day Number Pill
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isToday) RunwiseOrange else SurfaceDark),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            day.dayNumber,
                            color = if (isToday) Color.Black else TextPrimaryDark,
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp
                        )
                    }

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "${day.dayName}, ${day.date}",
                                color = if (isToday) RunwiseOrange else TextSecondaryDark,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (isToday) {
                                Text(
                                    "TODAY",
                                    color = Color.Black,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(RunwiseOrange)
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            day.title,
                            color = TextPrimaryDark,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    day.badge,
                    color = badgeColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            // Target Parameters (Distance, Target Pace, Target HR)
            if (!isRest) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceDark)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    PlanParamSnippet("Distance", day.distance)
                    PlanParamSnippet("Target Pace", day.pace)
                    PlanParamSnippet("Target HR", day.targetHr)
                }
            }

            // Description / Coaching Instructions
            Text(
                day.description,
                color = TextSecondaryDark,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            // Exercises snippet if present
            if (day.exercises.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceDark.copy(alpha = 0.6f))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Recommended Protocol:",
                            color = TextSecondaryDark,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "View Details",
                            color = RunwiseOrange,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { onViewExercises() }
                        )
                    }
                    day.exercises.take(2).forEach { ex ->
                        Text("• ${ex.name} (${ex.reps})", color = TextMutedDark, fontSize = 11.sp)
                    }
                }
            }

            // Action Button
            if (!isRest) {
                Button(
                    onClick = onStartWorkout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isToday) RunwiseOrange else SurfaceDark)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = if (isToday) Color.Black else TextPrimaryDark, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (isToday) "START TODAY'S WORKOUT" else "START THIS WORKOUT",
                        color = if (isToday) Color.Black else TextPrimaryDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            } else {
                OutlinedButton(
                    onClick = onViewExercises,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondaryDark)
                ) {
                    Icon(Icons.Default.SelfImprovement, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("VIEW RECOVERY STRETCHES", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun PlanParamSnippet(label: String, value: String) {
    Column {
        Text(label, color = TextMutedDark, fontSize = 10.sp)
        Text(value, color = TextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

private fun createWorkoutFromPlanDay(day: PlanDay): CustomWorkout {
    return PlanWorkoutParser.parsePlanDayToWorkout(day)
}
