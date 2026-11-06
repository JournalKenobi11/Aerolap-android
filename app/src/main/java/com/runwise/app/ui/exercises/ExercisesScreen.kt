package com.runwise.app.ui.exercises

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.runwise.app.data.repository.RunwiseRepository
import com.runwise.app.ui.theme.*

@Composable
fun ExercisesScreen(repository: RunwiseRepository) {
    val syncData by repository.syncData.collectAsState()

    var selectedTab by remember { mutableStateOf(0) } // 0: Strength & Calf, 1: Running Plan, 2: Stretches

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Column {
                Text(
                    "TRAINING EXERCISES",
                    color = RunwiseOrange,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    "Strength & Mobility Guide",
                    color = TextPrimaryDark,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        // Tab Selector Chips
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val tabs = listOf("Calf & Strength", "Plan Exercises", "Stretching")
                tabs.forEachIndexed { index, title ->
                    val isSelected = selectedTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) RunwiseOrange else CardDark)
                            .clickable { selectedTab = index }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            title,
                            color = if (isSelected) Color.White else TextSecondaryDark,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Tab 0: Calf & Home Strength (with 2kg DBs)
        if (selectedTab == 0) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.FitnessCenter, contentDescription = null, tint = RunwiseOrange)
                            Text("Athlete Equipment & Focus", color = TextPrimaryDark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Equipment: Pair of 2kg Dumbbells • Home Setup\nPrimary Target: Low Single-Leg Calf Raise capacity. Build Achilles and soleus resilience to sustain 6:15 min/km without calf exhaustion or Achilles tendon strain.",
                            color = TextMutedDark,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Exercise 1: Single-Leg Calf Raise Capacity Test
            item {
                ExerciseCard(
                    title = "Single-Leg Calf Raise Capacity Test",
                    badge = "BENCHMARK: 20 REPS",
                    badgeColor = PaceOnTarget,
                    sets = "3 sets to fatigue (per leg)",
                    instructions = "Stand barefoot on flat floor or step edge on one leg. Lift heel up smoothly for 2s, hold top 1s, lower slowly for 2s. Do not bend knee or claw toes. Rest 60s between legs.",
                    equipment = "Bodyweight (wall for balance)",
                    keyBenefit = "Builds gastrocnemius endurance to prevent late-race 10K cramping."
                )
            }

            // Exercise 2: Bent-Knee Soleus Dumbbell Raise
            item {
                ExerciseCard(
                    title = "Bent-Knee Soleus Dumbbell Raise",
                    badge = "2KG DUMBBELLS",
                    badgeColor = RunwiseOrange,
                    sets = "3 sets × 15 reps (per leg)",
                    instructions = "Sit on a chair, place your 2kg dumbbells on your lower thigh just above the knee. With knee bent at 90°, elevate your heel as high as possible. Pause at top, lower under control.",
                    equipment = "Pair of 2kg Dumbbells + Chair",
                    keyBenefit = "Soleus absorbs up to 8x bodyweight during running. Crucial for holding 6:15 pace."
                )
            }

            // Exercise 3: Eccentric Heel Drop Over a Step
            item {
                ExerciseCard(
                    title = "Eccentric Heel Drop Over Step Edge",
                    badge = "ACHILLES SHIELD",
                    badgeColor = SportHike,
                    sets = "3 sets × 15 reps",
                    instructions = "Rise up on both feet on a step. Shift weight to single leg, then take 3 full seconds to slowly lower heel below step level. Push back up with both feet.",
                    equipment = "Step edge or staircase",
                    keyBenefit = "Lengthens calf muscle fibers under load and fortifies the Achilles tendon."
                )
            }

            // Exercise 4: Isometric Wall Hold
            item {
                ExerciseCard(
                    title = "Isometric Wall Soleus Hold",
                    badge = "PAIN INHIBITION",
                    badgeColor = SportWalk,
                    sets = "3 sets × 45 seconds",
                    instructions = "Lean against a wall in a slight quarter squat. Rise onto the balls of your feet and hold statically at 75% max height for 45 seconds.",
                    equipment = "Wall",
                    keyBenefit = "Analgesic tendon relief; stiffens Achilles spring for energy return."
                )
            }
        }

        // Tab 1: Running Plan Daily Exercises
        if (selectedTab == 1) {
            val planExercises = syncData?.plan?.weeks
                ?.flatMap { it.days }
                ?.flatMap { it.exercises }
                ?.distinctBy { it.name }
                ?: emptyList()

            if (planExercises.isNotEmpty()) {
                items(planExercises) { ex ->
                    ExerciseCard(
                        title = ex.name,
                        badge = ex.type.uppercase(),
                        badgeColor = RunwiseOrange,
                        sets = ex.reps,
                        instructions = "Perform as part of today's 10K running plan routine. Focus on strict form and joint stability.",
                        equipment = "Bodyweight / 2kg Dumbbells",
                        keyBenefit = "Targeted support for 10K race preparation"
                    )
                }
            } else {
                item {
                    Text("Sync to load running plan exercises from your 10K coaching schedule.", color = TextMutedDark, fontSize = 13.sp)
                }
            }
        }

        // Tab 2: Essential Running Mobility & Stretches
        if (selectedTab == 2) {
            item {
                ExerciseCard(
                    title = "Standing Quadriceps & Hip Flexor Stretch",
                    badge = "POST-RUN",
                    badgeColor = SportWalk,
                    sets = "2 sets × 45s per leg",
                    instructions = "Stand tall, bend one knee and grab your ankle behind you. Keep knees together and tuck your pelvis slightly under to feel deep quad and hip flexor stretch.",
                    equipment = "Bodyweight",
                    keyBenefit = "Relieves anterior pelvic tilt and knee patellar load after pacing runs."
                )
            }

            item {
                ExerciseCard(
                    title = "Dynamic Hamstring Scoop & Hinge",
                    badge = "PRE & POST RUN",
                    badgeColor = RunwiseOrange,
                    sets = "2 sets × 10 reps per leg",
                    instructions = "Step one heel forward with toe pointed up. Hinge at hips with straight back, reach hands down and sweep arms forward in a fluid scooping motion.",
                    equipment = "Bodyweight",
                    keyBenefit = "Mobilizes posterior chain and sciatic nerve glide for a free stride."
                )
            }

            item {
                ExerciseCard(
                    title = "Plantar Fascia & Arch Rolling",
                    badge = "DAILY",
                    badgeColor = PaceOnTarget,
                    sets = "2 minutes per foot",
                    instructions = "Place 2kg dumbbell handle or small ball under the arch of your foot while seated. Apply moderate pressure and roll from heel to ball of foot.",
                    equipment = "2kg Dumbbell handle or ball",
                    keyBenefit = "Releases plantar fascia tension directly connected to calf tightness."
                )
            }
        }
    }
}

@Composable
fun ExerciseCard(
    title: String,
    badge: String,
    badgeColor: Color,
    sets: String,
    instructions: String,
    equipment: String,
    keyBenefit: String
) {
    var completedSets by remember { mutableStateOf(0) }

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
                Text(
                    title,
                    color = TextPrimaryDark,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    badge,
                    color = badgeColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(sets, color = RunwiseOrange, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(equipment, color = TextMutedDark, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(instructions, color = TextSecondaryDark, fontSize = 12.sp, lineHeight = 17.sp)

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = PaceOnTarget, modifier = Modifier.size(14.dp))
                Text(keyBenefit, color = PaceOnTarget, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Set Checkboxes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Log Sets:", color = TextSecondaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 1..3) {
                        val isDone = completedSets >= i
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isDone) PaceOnTarget else SurfaceDark)
                                .clickable {
                                    completedSets = if (completedSets == i) i - 1 else i
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "S$i",
                                color = if (isDone) Color.White else TextMutedDark,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
