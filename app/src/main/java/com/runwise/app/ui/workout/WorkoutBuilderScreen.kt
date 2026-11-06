package com.runwise.app.ui.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import java.util.UUID

@Composable
fun WorkoutBuilderScreen(
    repository: RunwiseRepository,
    onStartWorkout: (workout: CustomWorkout) -> Unit
) {
    val savedWorkouts by repository.customWorkouts.collectAsState()
    var isBuilderOpen by remember { mutableStateOf(false) }

    // Builder state
    var workoutName by remember { mutableStateOf("") }
    var workoutDesc by remember { mutableStateOf("") }
    var steps by remember {
        mutableStateOf(
            listOf(
                WorkoutStep(title = "Warm Up", stepType = StepType.WARMUP, targetDistanceMeters = 1000, targetPaceSecPerKm = 420),
                WorkoutStep(title = "Interval 1", stepType = StepType.INTERVAL, targetDistanceMeters = 1000, targetPaceSecPerKm = 375),
                WorkoutStep(title = "Recovery", stepType = StepType.RECOVERY, targetDistanceMeters = 400, targetPaceSecPerKm = 450),
                WorkoutStep(title = "Cool Down", stepType = StepType.COOLDOWN, targetDistanceMeters = 1000, targetPaceSecPerKm = 435)
            )
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    "STRUCTURED WORKOUTS",
                    color = RunwiseOrange,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    "Custom Laps & Pacing",
                    color = TextPrimaryDark,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        // Button to toggle builder
        item {
            Button(
                onClick = {
                    workoutName = "Custom Pacing Workout"
                    workoutDesc = "Structured multi-lap session"
                    isBuilderOpen = !isBuilderOpen
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (isBuilderOpen) SurfaceDark else RunwiseOrange)
            ) {
                Icon(
                    if (isBuilderOpen) Icons.Default.Close else Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isBuilderOpen) "CLOSE BUILDER" else "+ BUILD CUSTOM WORKOUT",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        // --- INTERACTIVE WORKOUT BUILDER PANEL ---
        if (isBuilderOpen) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    border = androidx.compose.foundation.BorderStroke(1.dp, RunwiseOrange)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "CREATE WORKOUT & SET LAPS",
                            color = RunwiseOrange,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = workoutName,
                            onValueChange = { workoutName = it },
                            label = { Text("Workout Name (e.g. 5x800m Pacing)") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = RunwiseOrange,
                                unfocusedBorderColor = CardBorderDark,
                                focusedTextColor = TextPrimaryDark,
                                unfocusedTextColor = TextPrimaryDark
                            )
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "WORKOUT STEPS & LAP PACING (${steps.size} STEPS)",
                            color = TextSecondaryDark,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Steps editor
                        steps.forEachIndexed { index, step ->
                            StepEditorCard(
                                stepNumber = index + 1,
                                step = step,
                                onUpdateStep = { updated ->
                                    val newSteps = steps.toMutableList()
                                    newSteps[index] = updated
                                    steps = newSteps
                                },
                                onDeleteStep = {
                                    if (steps.size > 1) {
                                        val newSteps = steps.toMutableList()
                                        newSteps.removeAt(index)
                                        steps = newSteps
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        // Add Step Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val newSteps = steps.toMutableList()
                                    newSteps.add(
                                        WorkoutStep(
                                            title = "Run Lap ${steps.size + 1}",
                                            stepType = StepType.RUN,
                                            targetDistanceMeters = 1000,
                                            targetPaceSecPerKm = 375
                                        )
                                    )
                                    steps = newSteps
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add Lap", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    // Add an interval + recovery pair
                                    val newSteps = steps.toMutableList()
                                    newSteps.add(
                                        WorkoutStep(
                                            title = "Fast Rep",
                                            stepType = StepType.INTERVAL,
                                            targetDistanceMeters = 800,
                                            targetPaceSecPerKm = 360 // 6:00 pace
                                        )
                                    )
                                    newSteps.add(
                                        WorkoutStep(
                                            title = "Recovery Jog",
                                            stepType = StepType.RECOVERY,
                                            targetDistanceMeters = 400,
                                            targetPaceSecPerKm = 450 // 7:30 pace
                                        )
                                    )
                                    steps = newSteps
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("+ Rep & Rest", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Action Buttons: Save & Start
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    val finalWorkout = CustomWorkout(
                                        name = if (workoutName.isNotBlank()) workoutName else "Custom Session",
                                        description = workoutDesc,
                                        steps = steps
                                    )
                                    repository.saveCustomWorkout(finalWorkout)
                                    isBuilderOpen = false
                                },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
                            ) {
                                Text("SAVE WORKOUT", fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    val finalWorkout = CustomWorkout(
                                        name = if (workoutName.isNotBlank()) workoutName else "Custom Session",
                                        description = workoutDesc,
                                        steps = steps
                                    )
                                    repository.saveCustomWorkout(finalWorkout)
                                    onStartWorkout(finalWorkout)
                                },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = RunwiseOrange)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("SAVE & START", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // --- SAVED & PRESET WORKOUTS LIST ---
        item {
            Text(
                "SAVED WORKOUT ROUTINES",
                color = TextSecondaryDark,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        items(savedWorkouts) { workout ->
            WorkoutCard(
                workout = workout,
                onStart = { onStartWorkout(workout) },
                onDelete = if (!workout.isPreset) { { repository.deleteCustomWorkout(workout.id) } } else null
            )
        }
    }
}

@Composable
fun StepEditorCard(
    stepNumber: Int,
    step: WorkoutStep,
    onUpdateStep: (WorkoutStep) -> Unit,
    onDeleteStep: () -> Unit
) {
    var title by remember(step) { mutableStateOf(step.title) }
    var distStr by remember(step) { mutableStateOf(step.targetDistanceMeters.toString()) }
    var paceMin by remember(step) { mutableStateOf((step.targetPaceSecPerKm / 60).toString()) }
    var paceSec by remember(step) { mutableStateOf("%02d".format(step.targetPaceSecPerKm % 60)) }
    var selectedType by remember(step) { mutableStateOf(step.stepType) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "LAP $stepNumber • ${selectedType.displayName.uppercase()}",
                    color = Color(selectedType.badgeColorHex),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )

                IconButton(onClick = onDeleteStep, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Step", tint = TextMutedDark, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Step Type Selector Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                StepType.values().forEach { type ->
                    val isSelected = selectedType == type
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) Color(type.badgeColorHex) else BackgroundDark)
                            .clickable {
                                selectedType = type
                                onUpdateStep(step.copy(stepType = type, title = type.displayName))
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            type.displayName,
                            color = if (isSelected) Color.White else TextSecondaryDark,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Distance & Pace Inputs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Distance input
                OutlinedTextField(
                    value = distStr,
                    onValueChange = {
                        distStr = it
                        val meters = it.toIntOrNull() ?: step.targetDistanceMeters
                        onUpdateStep(step.copy(targetDistanceMeters = meters))
                    },
                    label = { Text("Dist (m)", fontSize = 10.sp) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RunwiseOrange,
                        unfocusedBorderColor = CardBorderDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    )
                )

                // Pace Min
                OutlinedTextField(
                    value = paceMin,
                    onValueChange = {
                        paceMin = it
                        val m = it.toIntOrNull() ?: 6
                        val s = paceSec.toIntOrNull() ?: 0
                        onUpdateStep(step.copy(targetPaceSecPerKm = m * 60 + s))
                    },
                    label = { Text("Min/km", fontSize = 10.sp) },
                    modifier = Modifier.weight(0.8f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RunwiseOrange,
                        unfocusedBorderColor = CardBorderDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    )
                )

                Text(":", color = TextPrimaryDark, fontWeight = FontWeight.Bold)

                // Pace Sec
                OutlinedTextField(
                    value = paceSec,
                    onValueChange = {
                        paceSec = it
                        val m = paceMin.toIntOrNull() ?: 6
                        val s = it.toIntOrNull() ?: 0
                        onUpdateStep(step.copy(targetPaceSecPerKm = m * 60 + s))
                    },
                    label = { Text("Sec", fontSize = 10.sp) },
                    modifier = Modifier.weight(0.8f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RunwiseOrange,
                        unfocusedBorderColor = CardBorderDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    )
                )
            }
        }
    }
}

@Composable
fun WorkoutCard(
    workout: CustomWorkout,
    onStart: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(RunwiseOrange.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = RunwiseOrange, modifier = Modifier.size(20.dp))
                    }
                    Column {
                        Text(workout.name, color = TextPrimaryDark, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "${workout.steps.size} Laps • ${workout.formattedTotalDistance}",
                            color = RunwiseOrange,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = TextMutedDark, modifier = Modifier.size(20.dp))
                    }
                }
            }

            if (workout.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(workout.description, color = TextMutedDark, fontSize = 12.sp, lineHeight = 16.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Step summary pills
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                workout.steps.forEachIndexed { i, step ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceDark)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(step.stepType.badgeColorHex))
                            )
                            Text("Lap ${i + 1}: ${step.title}", color = TextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(step.targetDescription, color = TextSecondaryDark, fontSize = 12.sp)
                            Text(
                                "Target: ${step.formattedPace}/km",
                                color = Color(step.stepType.badgeColorHex),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RunwiseOrange)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("LAUNCH THIS WORKOUT", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}
