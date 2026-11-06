package com.runwise.app.ui.activities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runwise.app.data.model.*
import com.runwise.app.data.repository.RunwiseRepository
import com.runwise.app.ui.components.LeafletMapView
import com.runwise.app.ui.theme.*
import com.runwise.app.util.AnalyticsEngine
import com.runwise.app.util.PolylineUtils
import com.runwise.app.util.SmoothedTelemetrySeries
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDetailScreen(
    activityId: String,
    repository: RunwiseRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val cached = remember(activityId) { repository.getCachedActivityDetail(activityId) }
    var detailResponse by remember { mutableStateOf<ActivityDetailResponse?>(cached) }
    var isLoading by remember { mutableStateOf(cached == null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    LaunchedEffect(activityId) {
        if (detailResponse == null) {
            isLoading = true
        }
        errorMessage = null
        val result = repository.getActivityDetail(activityId)
        result.onSuccess {
            detailResponse = it
            isLoading = false
        }.onFailure {
            if (detailResponse == null) {
                errorMessage = it.localizedMessage ?: "Failed to load activity details"
            }
            isLoading = false
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteDialog = false },
            title = { Text("Delete Activity", fontWeight = FontWeight.Bold, color = TextPrimaryDark) },
            text = { Text("Are you sure you want to delete this workout? This action cannot be undone.", color = TextSecondaryDark) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isDeleting = true
                            val success = repository.deleteActivity(activityId)
                            isDeleting = false
                            showDeleteDialog = false
                            if (success) {
                                Toast.makeText(context, "Activity deleted", Toast.LENGTH_SHORT).show()
                                onBack()
                            } else {
                                Toast.makeText(context, "Failed to delete activity", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = FatigueAtl)
                ) {
                    Text(if (isDeleting) "Deleting..." else "Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = TextSecondaryDark)
                }
            },
            containerColor = CardDark
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            detailResponse?.activity?.name ?: "Workout Details",
                            color = TextPrimaryDark,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        detailResponse?.activity?.let {
                            Text(
                                it.startDateTime.replace("T", " "),
                                color = TextMutedDark,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimaryDark)
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = FatigueAtl)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        },
        containerColor = BackgroundDark
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = RunwiseOrange)
            }
        } else if (errorMessage != null && detailResponse == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(errorMessage ?: "Error", color = FatigueAtl, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                repository.getActivityDetail(activityId).onSuccess {
                                    detailResponse = it
                                    isLoading = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RunwiseOrange)
                    ) {
                        Text("Retry", color = Color.White)
                    }
                }
            }
        } else {
            val detail = detailResponse ?: return@Scaffold
            val activity = detail.activity

            // Resolve route coordinates: from detail.routeCoordinates or decode encodedPolyline
            val coordinates = remember(detail) {
                if (detail.routeCoordinates.isNotEmpty()) {
                    detail.routeCoordinates.map { Pair(it[0], it[1]) }
                } else if (!activity.encodedPolyline.isNullOrBlank()) {
                    PolylineUtils.decodePolyline(activity.encodedPolyline)
                } else emptyList()
            }

            val cumulativeDistances = remember(coordinates) {
                AnalyticsEngine.computeCumulativeDistances(coordinates)
            }
            var scrubDistanceMeters by remember { mutableStateOf<Double?>(null) }
            val scrubCoordinate = remember(scrubDistanceMeters, coordinates, cumulativeDistances) {
                scrubDistanceMeters?.let {
                    AnalyticsEngine.interpolateCoordinateAtDistance(it, coordinates, cumulativeDistances)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Map Route Canvas
                DetailRouteMap(
                    coordinates = coordinates,
                    distanceKm = activity.distanceKm,
                    movingTimeSeconds = activity.movingTimeSeconds,
                    elevationMeters = activity.elevationMeters,
                    scrubCoordinate = scrubCoordinate
                )

                // 2. Primary Metrics Grid
                PrimaryMetricsGrid(activity = activity)

                // 3. Strava-style Workout Laps / Analysis (ONLY for interval workouts; non-interval workouts show 1km splits)
                val hasActualIntervals = activity.workoutType?.contains("Interval", ignoreCase = true) == true ||
                                         activity.workoutType?.contains("HIIT", ignoreCase = true) == true ||
                                         detail.laps.any { it.isInterval }
                if (hasActualIntervals && detail.laps.isNotEmpty()) {
                    WorkoutLapsAnalysisSection(laps = detail.laps)
                }

                // 4. 1km Metric Splits
                if (detail.splits.isNotEmpty()) {
                    KilometerSplitsSection(splits = detail.splits)
                }

                // 5. Performance Curves
                PerformanceCurvesSection(
                    streams = detail.streams,
                    scrubDistanceMeters = scrubDistanceMeters,
                    onScrubChange = { scrubDistanceMeters = it }
                )

                // 6. Pace & HR Zones Distribution
                if (detail.paceZones.isNotEmpty() || detail.hrZones.isNotEmpty()) {
                    ZonesDistributionSection(paceZones = detail.paceZones, hrZones = detail.hrZones)
                }

                // 7. GPX & FIT Export / Share Actions
                ExportActionsSection(
                    activity = activity,
                    routeCoordinates = coordinates,
                    serverUrl = repository.getServerUrl(),
                    context = context
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// ----------------------------------------------------
// UI COMPONENTS
// ----------------------------------------------------

@Composable
fun DetailRouteMap(
    coordinates: List<Pair<Double, Double>>,
    distanceKm: Double,
    movingTimeSeconds: Long,
    elevationMeters: Int,
    scrubCoordinate: Pair<Double, Double>? = null
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
                Text("GPS ROUTE MAP", color = RunwiseOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                if (coordinates.isNotEmpty()) {
                    Text("${coordinates.size} GPS points", color = TextSecondaryDark, fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Real Street Map with CartoDB Dark Matter tiles and Polyline Route Overlay
            LeafletMapView(
                coordinates = coordinates,
                scrubCoordinate = scrubCoordinate,
                height = 240.dp,
                emptyMessage = "No GPS route coordinates recorded"
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Sub-bar with quick stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                QuickStatSnippet("Distance", String.format(Locale.US, "%.2f km", distanceKm))
                QuickStatSnippet("Time", formatDuration(movingTimeSeconds))
                QuickStatSnippet("Elevation", "+$elevationMeters m")
            }
        }
    }
}

@Composable
fun PrimaryMetricsGrid(activity: ActivityItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PERFORMANCE METRICS", color = RunwiseOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricTile("Avg Pace", "${activity.averagePaceFormatted} /km", Icons.Default.Timer, PaceOnTarget)
                MetricTile("Moving Time", formatDuration(activity.movingTimeSeconds), Icons.Default.Schedule, TextPrimaryDark)
                MetricTile("Elevation Gain", "+${activity.elevationMeters} m", Icons.Default.TrendingUp, SportHike)
            }

            HorizontalDivider(color = CardBorderDark)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (activity.averageCadence != null && activity.averageCadence > 0) {
                    MetricTile("Avg Cadence", "${activity.averageCadence} spm", Icons.AutoMirrored.Filled.DirectionsRun, SportRun)
                } else {
                    MetricTile("Cadence", "-- spm", Icons.AutoMirrored.Filled.DirectionsRun, TextMutedDark)
                }

                if (activity.averageHeartRate != null && activity.averageHeartRate > 0) {
                    MetricTile("Avg HR", "${activity.averageHeartRate} bpm", Icons.Default.Favorite, FatigueAtl)
                } else {
                    MetricTile("Avg HR", "-- bpm", Icons.Default.Favorite, TextMutedDark)
                }

                if (activity.calories != null && activity.calories > 0) {
                    MetricTile("Calories", "${activity.calories} kcal", Icons.Default.LocalFireDepartment, RunwiseOrange)
                } else {
                    MetricTile("Calories", "-- kcal", Icons.Default.LocalFireDepartment, TextMutedDark)
                }
            }
        }
    }
}

/**
 * Strava-style workout analysis table:
 * Intervals are visually highlighted in prominent orange containers
 * Warmup, recovery, and cooldown laps are displayed in subtle dark cards.
 */
@Composable
fun WorkoutLapsAnalysisSection(laps: List<ActivityLapItem>) {
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
                Column {
                    Text("WORKOUT ANALYSIS", color = RunwiseOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("Lap & Interval Breakdown", color = TextPrimaryDark, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Text("${laps.size} Laps", color = TextSecondaryDark, fontSize = 12.sp)
            }

            // Lap List
            laps.forEach { lap ->
                val isInterval = lap.isInterval

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isInterval) Color(0xFF2A1B0E) else SurfaceDark
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        width = if (isInterval) 1.5.dp else 1.dp,
                        color = if (isInterval) RunwiseOrange else CardBorderDark
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Lap number & name & badge
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(if (isInterval) RunwiseOrange else SurfaceDark),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    lap.lapNumber.toString(),
                                    color = if (isInterval) Color.Black else TextPrimaryDark,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp
                                )
                            }

                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        lap.name,
                                        color = if (isInterval) RunwiseOrange else TextPrimaryDark,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (isInterval) {
                                        Text(
                                            "INTERVAL",
                                            color = Color.Black,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Black,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(RunwiseOrange)
                                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        if (lap.distanceMeters >= 1000) String.format(Locale.US, "%.2f km", lap.distanceMeters / 1000.0)
                                        else "${lap.distanceMeters} m",
                                        color = TextSecondaryDark,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        formatDuration(lap.movingTimeSeconds),
                                        color = TextMutedDark,
                                        fontSize = 12.sp
                                    )
                                    if (lap.elevationDifference != 0) {
                                        Text(
                                            "${if (lap.elevationDifference > 0) "+" else ""}${lap.elevationDifference}m",
                                            color = if (lap.elevationDifference >= 0) PaceOnTarget else FatigueAtl,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }

                        // Right: Pace & HR
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${lap.paceFormatted} /km",
                                color = if (isInterval) RunwiseOrange else PaceOnTarget,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                            if (lap.averageHeartRate != null && lap.averageHeartRate > 0) {
                                Text(
                                    "${lap.averageHeartRate} bpm",
                                    color = FatigueAtl,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun KilometerSplitsSection(splits: List<ActivitySplitItem>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("1 KM SPLITS", color = RunwiseOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

            val minPace = splits.map { it.paceSecPerKm }.filter { it > 0 }.minOrNull() ?: 300
            val maxPace = splits.map { it.paceSecPerKm }.filter { it > 0 }.maxOrNull() ?: 600
            val paceRange = (maxPace - minPace).coerceAtLeast(30)

            splits.forEach { split ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val splitLabel = if (split.distanceMeters >= 950) {
                        "${split.splitNumber} km"
                    } else {
                        "${split.distanceMeters} m"
                    }
                    Text(
                        splitLabel,
                        color = TextPrimaryDark,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(52.dp)
                    )

                    // Pace visual bar
                    val fraction = if (split.paceSecPerKm > 0) {
                        1f - ((split.paceSecPerKm - minPace).toFloat() / paceRange).coerceIn(0f, 0.85f)
                    } else 0.5f

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(SurfaceDark)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction)
                                .clip(RoundedCornerShape(4.dp))
                                .background(SportRun)
                        )
                    }

                    Text(
                        "${split.paceFormatted} /km",
                        color = TextPrimaryDark,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(62.dp)
                    )

                    Text(
                        formatDuration(split.movingTimeSeconds),
                        color = TextMutedDark,
                        fontSize = 11.sp,
                        modifier = Modifier.width(42.dp)
                    )

                    if (split.elevationDifference != 0) {
                        Text(
                            "${if (split.elevationDifference > 0) "+" else ""}${split.elevationDifference}m",
                            color = if (split.elevationDifference >= 0) PaceOnTarget else FatigueAtl,
                            fontSize = 10.sp,
                            modifier = Modifier.width(34.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PerformanceCurvesSection(
    streams: ActivityStreamsData,
    scrubDistanceMeters: Double?,
    onScrubChange: (Double?) -> Unit
) {
    val series = remember(streams) {
        AnalyticsEngine.processTelemetry(
            distanceStream = streams.distanceCurve,
            paceStream = streams.paceCurve,
            cadenceStream = streams.cadenceCurve,
            elevationStream = streams.elevationCurve
        )
    }

    val hasPace = series.smoothedPaceSecPerKm.isNotEmpty()
    val hasCadence = series.avgCadenceSpm > 60.0 && series.smoothedCadenceSpm.isNotEmpty()
    val hasElevation = series.smoothedElevationMeters.isNotEmpty()

    if (!hasPace && !hasCadence && !hasElevation) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("PERFORMANCE CURVES", color = RunwiseOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

            // 1. Strava-grade Pace Profile in Distance Domain (Inverted Y-axis, 2nd-98th percentile clamped)
            if (hasPace) {
                PaceDistanceChart(
                    series = series,
                    scrubDistanceMeters = scrubDistanceMeters,
                    onScrubChange = onScrubChange
                )
            }

            // 2. Strava-grade Cadence Profile in Distance Domain (shows only if sensor present, mean ±15 SPM window)
            if (hasCadence) {
                CadenceDistanceChart(
                    series = series,
                    scrubDistanceMeters = scrubDistanceMeters,
                    onScrubChange = onScrubChange
                )
            }

            // 3. Strava-grade Elevation Profile in Distance Domain (Topographic Silhouette)
            if (hasElevation) {
                ElevationDistanceChart(
                    series = series,
                    scrubDistanceMeters = scrubDistanceMeters,
                    onScrubChange = onScrubChange
                )
            }
        }
    }
}

@Composable
fun PaceDistanceChart(
    series: SmoothedTelemetrySeries,
    scrubDistanceMeters: Double?,
    onScrubChange: (Double?) -> Unit
) {
    val totalDist = series.distanceBinsMeters.lastOrNull() ?: 1.0
    if (totalDist < 20.0 || series.smoothedPaceSecPerKm.size < 2) return

    // Invert Y-axis: faster pace at top, slower pace at bottom
    // Clamp to 2nd - 98th percentile with slight padding
    val yFast = (series.p2PaceSecPerKm - 10.0).coerceAtLeast(120.0)
    val ySlow = (series.p98PaceSecPerKm + 15.0).coerceAtMost(900.0)
    val paceRange = (ySlow - yFast).coerceAtLeast(30.0)

    val currentScrubDist = scrubDistanceMeters?.coerceIn(0.0, totalDist)
    val currentScrubPace = currentScrubDist?.let { dist ->
        AnalyticsEngine.resampleStream(
            series.distanceBinsMeters,
            series.smoothedPaceSecPerKm,
            listOf(dist)
        ).firstOrNull()
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("PACE PROFILE", color = TextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Inverted Y-axis • Distance Domain", color = TextMutedDark, fontSize = 10.sp)
            }
            if (currentScrubDist != null && currentScrubPace != null) {
                Text(
                    "${String.format(Locale.US, "%.2f km", currentScrubDist / 1000.0)} • ${formatPaceTime(currentScrubPace.roundToInt())} /km",
                    color = RunwiseOrange,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    "Avg: ${formatPaceTime(series.avgPaceSecPerKm.roundToInt())} /km",
                    color = PaceOnTarget,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceDark)
                .pointerInput(series.distanceBinsMeters) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            val f = (offset.x / size.width).coerceIn(0f, 1f)
                            onScrubChange(f * totalDist)
                        },
                        onDragEnd = { onScrubChange(null) },
                        onDragCancel = { onScrubChange(null) },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val f = (change.position.x / size.width).coerceIn(0f, 1f)
                            onScrubChange(f * totalDist)
                        }
                    )
                }
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val topPad = 8f
                val botPad = 20f
                val chartH = h - topPad - botPad

                // Draw horizontal average pace dashed line
                val avgPaceNorm = ((series.avgPaceSecPerKm - yFast) / paceRange).toFloat().coerceIn(0f, 1f)
                val avgY = topPad + avgPaceNorm * chartH
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(0f, avgY),
                    end = Offset(w, avgY),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                )

                // Draw X-axis km ticks (at every 1.0 km)
                var kmMarker = 1000.0
                while (kmMarker < totalDist) {
                    val kx = (kmMarker / totalDist).toFloat() * w
                    drawLine(
                        color = Color.White.copy(alpha = 0.15f),
                        start = Offset(kx, topPad),
                        end = Offset(kx, h - botPad),
                        strokeWidth = 1f
                    )
                    kmMarker += 1000.0
                }

                // Build pace curve path
                val path = Path()
                val n = series.distanceBinsMeters.size
                for (i in 0 until n) {
                    val d = series.distanceBinsMeters[i]
                    val p = series.smoothedPaceSecPerKm[i]
                    val x = (d / totalDist).toFloat() * w
                    val norm = ((p - yFast) / paceRange).toFloat().coerceIn(0f, 1f)
                    val y = topPad + norm * chartH
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                // Gradient fill under curve
                val fillPath = Path().apply {
                    addPath(path)
                    lineTo(w, h - botPad)
                    lineTo(0f, h - botPad)
                    close()
                }
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(PaceOnTarget.copy(alpha = 0.25f), PaceOnTarget.copy(alpha = 0.02f)),
                        startY = topPad,
                        endY = h - botPad
                    )
                )

                // Curve stroke
                drawPath(
                    path = path,
                    color = PaceOnTarget,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // Hairline scrub cursor & dot
                if (currentScrubDist != null && currentScrubPace != null) {
                    val sx = (currentScrubDist / totalDist).toFloat() * w
                    val snorm = ((currentScrubPace - yFast) / paceRange).toFloat().coerceIn(0f, 1f)
                    val sy = topPad + snorm * chartH

                    drawLine(
                        color = Color.White.copy(alpha = 0.85f),
                        start = Offset(sx, topPad),
                        end = Offset(sx, h - botPad),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    drawCircle(color = RunwiseOrange, radius = 5.dp.toPx(), center = Offset(sx, sy))
                    drawCircle(color = Color.White, radius = 5.dp.toPx(), center = Offset(sx, sy), style = Stroke(width = 2.dp.toPx()))
                }
            }

            // X-axis km labels at bottom
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0 km", color = TextMutedDark, fontSize = 9.sp)
                if (totalDist >= 1500.0) {
                    Text("1.0 km", color = TextMutedDark, fontSize = 9.sp)
                }
                if (totalDist >= 2500.0) {
                    Text("2.0 km", color = TextMutedDark, fontSize = 9.sp)
                }
                Text(String.format(Locale.US, "%.2f km", totalDist / 1000.0), color = TextMutedDark, fontSize = 9.sp)
            }
        }
    }
}

@Composable
fun CadenceDistanceChart(
    series: SmoothedTelemetrySeries,
    scrubDistanceMeters: Double?,
    onScrubChange: (Double?) -> Unit
) {
    val totalDist = series.distanceBinsMeters.lastOrNull() ?: 1.0
    if (totalDist < 20.0 || series.smoothedCadenceSpm.size < 2) return

    val actualMin = series.minCadenceSpm
    val actualMax = series.maxCadenceSpm
    val minCad = (actualMin - 6.0).coerceAtLeast(50.0)
    val maxCad = (actualMax + 6.0).coerceAtLeast(minCad + 15.0)
    val cadRange = (maxCad - minCad).coerceAtLeast(10.0)

    val currentScrubDist = scrubDistanceMeters?.coerceIn(0.0, totalDist)
    val currentScrubCad = currentScrubDist?.let { dist ->
        AnalyticsEngine.resampleStream(
            series.distanceBinsMeters,
            series.smoothedCadenceSpm,
            listOf(dist)
        ).firstOrNull()
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("CADENCE PROFILE", color = TextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Min ${actualMin.roundToInt()} • Max ${actualMax.roundToInt()} SPM",
                    color = TextMutedDark,
                    fontSize = 10.sp
                )
            }
            if (currentScrubDist != null && currentScrubCad != null) {
                Text(
                    "${String.format(Locale.US, "%.2f km", currentScrubDist / 1000.0)} • ${currentScrubCad.roundToInt()} spm",
                    color = SportRun,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    "Avg: ${series.avgCadenceSpm.roundToInt()} spm",
                    color = SportRun,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceDark)
                .pointerInput(series.distanceBinsMeters) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            val f = (offset.x / size.width).coerceIn(0f, 1f)
                            onScrubChange(f * totalDist)
                        },
                        onDragEnd = { onScrubChange(null) },
                        onDragCancel = { onScrubChange(null) },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val f = (change.position.x / size.width).coerceIn(0f, 1f)
                            onScrubChange(f * totalDist)
                        }
                    )
                }
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val topPad = 8f
                val botPad = 18f
                val chartH = h - topPad - botPad

                // Draw dashed average cadence line
                val avgNorm = ((series.avgCadenceSpm - minCad) / cadRange).toFloat().coerceIn(0f, 1f)
                val avgY = h - botPad - avgNorm * chartH
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(0f, avgY),
                    end = Offset(w, avgY),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                )

                // Build cadence curve path
                val path = Path()
                val n = series.distanceBinsMeters.size
                for (i in 0 until n) {
                    val d = series.distanceBinsMeters[i]
                    val c = series.smoothedCadenceSpm[i]
                    val x = (d / totalDist).toFloat() * w
                    val norm = ((c - minCad) / cadRange).toFloat().coerceIn(0f, 1f)
                    val y = h - botPad - norm * chartH
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                // Fill area
                val fillPath = Path().apply {
                    addPath(path)
                    lineTo(w, h - botPad)
                    lineTo(0f, h - botPad)
                    close()
                }
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(SportRun.copy(alpha = 0.25f), SportRun.copy(alpha = 0.02f)),
                        startY = topPad,
                        endY = h - botPad
                    )
                )

                // Curve stroke
                drawPath(
                    path = path,
                    color = SportRun,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // Hairline scrub cursor & dot
                if (currentScrubDist != null && currentScrubCad != null) {
                    val sx = (currentScrubDist / totalDist).toFloat() * w
                    val snorm = ((currentScrubCad - minCad) / cadRange).toFloat().coerceIn(0f, 1f)
                    val sy = h - botPad - snorm * chartH

                    drawLine(
                        color = Color.White.copy(alpha = 0.85f),
                        start = Offset(sx, topPad),
                        end = Offset(sx, h - botPad),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    drawCircle(color = SportRun, radius = 5.dp.toPx(), center = Offset(sx, sy))
                    drawCircle(color = Color.White, radius = 5.dp.toPx(), center = Offset(sx, sy), style = Stroke(width = 2.dp.toPx()))
                }
            }

            // X-axis km labels at bottom
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0 km", color = TextMutedDark, fontSize = 9.sp)
                Text(String.format(Locale.US, "%.2f km", totalDist / 1000.0), color = TextMutedDark, fontSize = 9.sp)
            }
        }
    }
}

@Composable
fun ElevationDistanceChart(
    series: SmoothedTelemetrySeries,
    scrubDistanceMeters: Double?,
    onScrubChange: (Double?) -> Unit
) {
    val totalDist = series.distanceBinsMeters.lastOrNull() ?: 1.0
    if (totalDist < 20.0 || series.smoothedElevationMeters.size < 2) return

    val minElev = series.minElevationMeters
    val maxElev = series.maxElevationMeters
    val rawDiff = maxElev - minElev
    // Strava elevation standard: Enforce a realistic minimum 30-meter vertical span so flat courses look flat
    val verticalSpan = maxOf(30.0, rawDiff * 1.5)
    val midElev = (minElev + maxElev) / 2.0
    val baseElev = maxOf(0.0, midElev - verticalSpan / 2.0)
    val ceilElev = baseElev + verticalSpan
    val elevRange = ceilElev - baseElev

    val currentScrubDist = scrubDistanceMeters?.coerceIn(0.0, totalDist)
    val currentScrubElev = currentScrubDist?.let { dist ->
        AnalyticsEngine.resampleStream(
            series.distanceBinsMeters,
            series.smoothedElevationMeters,
            listOf(dist)
        ).firstOrNull()
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("ELEVATION PROFILE", color = TextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Gain +${series.elevationGainMeters.roundToInt()}m • Min ${series.minElevationMeters.roundToInt()}m • Max ${series.maxElevationMeters.roundToInt()}m",
                    color = TextMutedDark,
                    fontSize = 10.sp
                )
            }
            if (currentScrubDist != null && currentScrubElev != null) {
                Text(
                    "${String.format(Locale.US, "%.2f km", currentScrubDist / 1000.0)} • ${String.format(Locale.US, "%.1f m", currentScrubElev)}",
                    color = RunwiseOrange,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    "+${series.elevationGainMeters.roundToInt()} m",
                    color = RunwiseOrange,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(115.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceDark)
                .pointerInput(series.distanceBinsMeters) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            val f = (offset.x / size.width).coerceIn(0f, 1f)
                            onScrubChange(f * totalDist)
                        },
                        onDragEnd = { onScrubChange(null) },
                        onDragCancel = { onScrubChange(null) },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val f = (change.position.x / size.width).coerceIn(0f, 1f)
                            onScrubChange(f * totalDist)
                        }
                    )
                }
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val topPad = 8f
                val botPad = 18f
                val chartH = h - topPad - botPad

                // Build elevation silhouette path
                val path = Path()
                val n = series.distanceBinsMeters.size
                for (i in 0 until n) {
                    val d = series.distanceBinsMeters[i]
                    val e = series.smoothedElevationMeters[i]
                    val x = (d / totalDist).toFloat() * w
                    val norm = ((e - baseElev) / elevRange).toFloat().coerceIn(0f, 1f)
                    val y = h - botPad - norm * chartH
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                // Topographic silhouette gradient fill
                val fillPath = Path().apply {
                    addPath(path)
                    lineTo(w, h - botPad)
                    lineTo(0f, h - botPad)
                    close()
                }
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(RunwiseOrange.copy(alpha = 0.40f), RunwiseOrange.copy(alpha = 0.05f)),
                        startY = topPad,
                        endY = h - botPad
                    )
                )

                // Silhouette outline stroke
                drawPath(
                    path = path,
                    color = RunwiseOrange,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // Hairline scrub cursor & dot
                if (currentScrubDist != null && currentScrubElev != null) {
                    val sx = (currentScrubDist / totalDist).toFloat() * w
                    val snorm = ((currentScrubElev - baseElev) / elevRange).toFloat().coerceIn(0f, 1f)
                    val sy = h - botPad - snorm * chartH

                    drawLine(
                        color = Color.White.copy(alpha = 0.85f),
                        start = Offset(sx, topPad),
                        end = Offset(sx, h - botPad),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    drawCircle(color = RunwiseOrange, radius = 5.dp.toPx(), center = Offset(sx, sy))
                    drawCircle(color = Color.White, radius = 5.dp.toPx(), center = Offset(sx, sy), style = Stroke(width = 2.dp.toPx()))
                }
            }

            // X-axis km labels at bottom
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0 km", color = TextMutedDark, fontSize = 9.sp)
                Text(String.format(Locale.US, "%.2f km", totalDist / 1000.0), color = TextMutedDark, fontSize = 9.sp)
            }
        }
    }
}

private fun formatPaceTime(sec: Int): String {
    if (sec <= 0) return "-"
    val m = sec / 60
    val s = sec % 60
    return String.format(Locale.US, "%d:%02d", m, s)
}

@Composable
fun ZonesDistributionSection(
    paceZones: List<ZoneDistributionItem>,
    hrZones: List<ZoneDistributionItem>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("ZONES DISTRIBUTION", color = RunwiseOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

            if (paceZones.isNotEmpty()) {
                Text("Pace Zones Distribution", color = TextPrimaryDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                val zoneColors = listOf(PaceTooSlow, PaceOnTarget, SportRun, RunwiseOrange, PaceTooFast)
                paceZones.forEachIndexed { idx, zone ->
                    ZoneBarItem(
                        name = zone.name,
                        percent = zone.percent,
                        seconds = zone.seconds,
                        color = zoneColors.getOrElse(idx) { RunwiseOrange }
                    )
                }
            }

            if (hrZones.isNotEmpty() && hrZones.any { it.seconds > 0 }) {
                HorizontalDivider(color = CardBorderDark)
                Text("Heart Rate Zones Distribution", color = TextPrimaryDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                val hrColors = listOf(Color(0xFF38BDF8), Color(0xFF22C55E), Color(0xFFFACC15), Color(0xFFFB923C), Color(0xFFEF4444))
                hrZones.forEachIndexed { idx, zone ->
                    ZoneBarItem(
                        name = "${zone.name} (${zone.range ?: ""})",
                        percent = zone.percent,
                        seconds = zone.seconds,
                        color = hrColors.getOrElse(idx) { FatigueAtl }
                    )
                }
            }
        }
    }
}

@Composable
fun ZoneBarItem(name: String, percent: Double, seconds: Int, color: Color) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(name, color = TextSecondaryDark, fontSize = 11.sp)
            Text(
                "${String.format(Locale.US, "%.1f", percent)}% (${formatDuration(seconds.toLong())})",
                color = TextPrimaryDark,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(3.5.dp))
                .background(SurfaceDark)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((percent / 100.0).toFloat().coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(3.5.dp))
                    .background(color)
            )
        }
    }
}

@Composable
fun ExportActionsSection(
    activity: ActivityItem,
    routeCoordinates: List<Pair<Double, Double>>,
    serverUrl: String,
    context: Context
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("EXPORT & SHARE WORKOUT", color = RunwiseOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        val appDir = java.io.File(context.getExternalFilesDir(null), "Runwise")
                        val gpxFile = java.io.File(appDir, "${activity.id}.gpx")
                        if (gpxFile.exists() || routeCoordinates.isNotEmpty()) {
                            shareLocalGpx(context, activity, routeCoordinates)
                        } else {
                            val gpxUrl = "$serverUrl/api/mobile/activities/${activity.id}/gpx"
                            shareOrOpenUrl(context, gpxUrl, "Download / Share GPX Route")
                        }
                    },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RunwiseOrange)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export GPX", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        val fitUrl = "$serverUrl/api/mobile/activities/${activity.id}/original"
                        shareOrOpenUrl(context, fitUrl, "Download / Share FIT File")
                    },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = SportRun, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export FIT", color = TextPrimaryDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun shareLocalGpx(context: Context, activity: ActivityItem, routeCoordinates: List<Pair<Double, Double>>) {
    try {
        val appDir = java.io.File(context.getExternalFilesDir(null), "Runwise")
        if (!appDir.exists()) appDir.mkdirs()
        val gpxFile = java.io.File(appDir, "${activity.id}.gpx")
        if (!gpxFile.exists() || gpxFile.length() < 10) {
            val xml = PolylineUtils.generateGpxXml(
                activityName = activity.name,
                sportType = activity.sportType,
                startTimeIso = activity.startDateTime.replace(" ", "T") + "Z",
                routePoints = routeCoordinates,
                elevationMeters = activity.elevationMeters
            )
            gpxFile.writeText(xml)
        }

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            gpxFile
        )

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, activity.name)
            putExtra(Intent.EXTRA_TEXT, "Workout GPX track: ${activity.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "Share GPX (${activity.name})").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not share GPX: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun shareOrOpenUrl(context: Context, url: String, title: String) {
    try {
        val sendIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(Intent.createChooser(sendIntent, title))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open browser: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun MetricTile(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text(label, color = TextMutedDark, fontSize = 10.sp)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, color = TextPrimaryDark, fontSize = 15.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun QuickStatSnippet(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextMutedDark, fontSize = 10.sp)
        Text(value, color = TextPrimaryDark, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(label, color = TextPrimaryDark, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

private fun formatDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}
