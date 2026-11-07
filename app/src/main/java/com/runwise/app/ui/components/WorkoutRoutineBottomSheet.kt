package com.runwise.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runwise.app.data.model.Exercise
import com.runwise.app.data.model.ExerciseCatalog
import com.runwise.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutRoutineBottomSheet(
    dayTitle: String,
    dayDate: String,
    exercises: List<Exercise>,
    onDismiss: () -> Unit,
    onNavigateToCatalog: (exerciseName: String) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Map of exerciseIndex to count of completed sets
    val completedSetsMap = remember { mutableStateMapOf<Int, Int>() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundDark,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(CardBorderDark)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "POST-WORKOUT PROTOCOL",
                        color = AerolapBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        dayTitle,
                        color = TextPrimaryDark,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        "$dayDate • ${exercises.size} ${if (exercises.size == 1) "Exercise" else "Exercises"} Prescribed",
                        color = TextSecondaryDark,
                        fontSize = 12.sp
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(SurfaceDark)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondaryDark, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = CardBorderDark)
            Spacer(modifier = Modifier.height(10.dp))

            // Exercise List
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                itemsIndexed(exercises) { index, ex ->
                    val detail = ExerciseCatalog.findDetail(ex.name)
                    val completedSets = completedSetsMap[index] ?: 0

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardDark),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (completedSets > 0) PaceOnTarget.copy(alpha = 0.5f) else CardBorderDark
                        )
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            // Step pill & Type badge
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (completedSets > 0) PaceOnTarget else AerolapBlue)
                                    )
                                    Text(
                                        "EXERCISE ${index + 1} OF ${exercises.size}",
                                        color = if (completedSets > 0) PaceOnTarget else AerolapBlue,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                }

                                Text(
                                    detail.badge,
                                    color = AerolapBlue,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(AerolapBlue.copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                ex.name,
                                color = TextPrimaryDark,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Target: ${ex.reps.ifBlank { detail.defaultSets }}",
                                    color = AerolapBlue,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    detail.equipment,
                                    color = TextMutedDark,
                                    fontSize = 11.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                detail.instructions,
                                color = TextSecondaryDark,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = PaceOnTarget, modifier = Modifier.size(14.dp))
                                Text(detail.keyBenefit, color = PaceOnTarget, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Set Logging Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Log Completed Sets:", color = TextSecondaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (s in 1..3) {
                                        val isDone = completedSets >= s
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isDone) PaceOnTarget else SurfaceDark)
                                                .border(
                                                    width = 1.dp,
                                                    color = if (isDone) PaceOnTarget else CardBorderDark,
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .clickable {
                                                    completedSetsMap[index] = if (completedSets == s) s - 1 else s
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                "S$s",
                                                color = if (isDone) Color.Black else TextSecondaryDark,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom CTA
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .padding(bottom = 6.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AerolapBlue)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("DONE WITH PROTOCOL", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 14.sp)
            }
        }
    }
}
