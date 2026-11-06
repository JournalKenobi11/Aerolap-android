package com.runwise.app.ui.activities

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runwise.app.data.model.ActivityItem
import com.runwise.app.data.repository.RunwiseRepository
import com.runwise.app.ui.theme.*
import java.util.Locale

@Composable
fun ActivitiesScreen(
    repository: RunwiseRepository,
    onActivityClick: (String) -> Unit = {}
) {
    val syncData by repository.syncData.collectAsState()
    val localActivities by repository.localActivities.collectAsState()
    val watchActivities = syncData?.activities ?: emptyList()

    // Pre-cache top recent workouts as soon as activities screen is opened
    LaunchedEffect(watchActivities) {
        if (watchActivities.isNotEmpty()) {
            repository.prefetchRecentActivityDetails(watchActivities.take(15).map { it.id })
        }
    }

    val allActivities = remember(watchActivities, localActivities) {
        (localActivities + watchActivities).distinctBy { it.id }.sortedByDescending { it.startDateTime }
    }

    var selectedSport by remember { mutableStateOf("All") }

    val filteredActivities = remember(allActivities, selectedSport) {
        if (selectedSport == "All") allActivities
        else allActivities.filter { it.sportType.equals(selectedSport, ignoreCase = true) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text(
                    "ACTIVITIES",
                    color = RunwiseOrange,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    "${allActivities.size} Activities",
                    color = TextPrimaryDark,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        // Sport filter chips: All, Run, Walk, Hike
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    SportFilterChip("All (${allActivities.size})", selectedSport == "All") { selectedSport = "All" }
                }
                item {
                    SportFilterChip("Run (${allActivities.count { it.sportType.equals("Run", true) }})", selectedSport == "Run") { selectedSport = "Run" }
                }
                item {
                    SportFilterChip("Walk (${allActivities.count { it.sportType.equals("Walk", true) }})", selectedSport == "Walk") { selectedSport = "Walk" }
                }
                item {
                    SportFilterChip("Hike (${allActivities.count { it.sportType.equals("Hike", true) }})", selectedSport == "Hike") { selectedSport = "Hike" }
                }
            }
        }

        // Activity items list
        items(filteredActivities, key = { it.id }) { activity ->
            ActivityListItem(activity = activity, onClick = { onActivityClick(activity.id) })
        }
    }
}

@Composable
fun SportFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = RunwiseOrange,
            selectedLabelColor = Color.White,
            containerColor = CardDark,
            labelColor = TextSecondaryDark
        ),
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
fun ActivityListItem(
    activity: ActivityItem,
    onClick: () -> Unit = {}
) {
    val sportColor = when (activity.sportType.lowercase(Locale.ROOT)) {
        "run" -> SportRun
        "walk" -> SportWalk
        "hike" -> SportHike
        else -> RunwiseOrange
    }

    val icon: ImageVector = when (activity.sportType.lowercase(Locale.ROOT)) {
        "run" -> Icons.Default.DirectionsRun
        "walk" -> Icons.Default.DirectionsWalk
        "hike" -> Icons.Default.Terrain
        else -> Icons.Default.FitnessCenter
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
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
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(sportColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = sportColor, modifier = Modifier.size(20.dp))
                    }

                    Column {
                        Text(
                            activity.name,
                            color = TextPrimaryDark,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            activity.startDateTime.replace("T", " "),
                            color = TextMutedDark,
                            fontSize = 11.sp
                        )
                    }
                }

                Text(
                    String.format(Locale.US, "%.2f km", activity.distanceKm),
                    color = TextPrimaryDark,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Metrics row: Duration, Pace, Cadence, Elevation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceDark)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricSnippet("Time", formatDuration(activity.movingTimeSeconds))
                MetricSnippet("Pace", "${activity.averagePaceFormatted} /km")
                if (activity.averageCadence != null && activity.averageCadence > 0) {
                    MetricSnippet("Cadence", "${activity.averageCadence} spm")
                } else if (activity.averageHeartRate != null && activity.averageHeartRate > 0) {
                    MetricSnippet("Avg HR", "${activity.averageHeartRate} bpm")
                }
                MetricSnippet("Elev", "${activity.elevationMeters} m")
            }
        }
    }
}

@Composable
fun MetricSnippet(label: String, value: String) {
    Column {
        Text(label, color = TextMutedDark, fontSize = 10.sp)
        Text(value, color = TextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}
