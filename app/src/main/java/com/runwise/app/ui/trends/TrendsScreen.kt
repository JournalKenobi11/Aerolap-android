package com.runwise.app.ui.trends

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runwise.app.data.model.TotalsData
import com.runwise.app.data.model.TrainingLoadPoint
import com.runwise.app.data.model.WeeklyTrend
import com.runwise.app.data.repository.RunwiseRepository
import com.runwise.app.ui.theme.*
import java.util.Locale

@Composable
fun TrendsScreen(repository: RunwiseRepository) {
    val syncData by repository.syncData.collectAsState()
    val weeklyTrends = syncData?.trends?.weekly ?: emptyList()
    val totals = syncData?.trends?.totals
    val history = syncData?.trainingLoadHistory ?: emptyList()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "TRAINING TRENDS & METRICS",
                color = RunwiseOrange,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                "Activity & Volume Analytics",
                color = TextPrimaryDark,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        // 1. Career Totals Card (Run vs Walk vs Hike)
        item {
            CareerTotalsSection(totals = totals)
        }

        // 2. 90-Day Fitness & Fatigue Chart (CTL / ATL / TSB)
        item {
            FitnessFatigueChartCard(history = history)
        }

        // 3. Weekly KMS Breakdown Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "WEEKLY KMS BREAKDOWN",
                    color = TextSecondaryDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LegendItem("Run", SportRun)
                    LegendItem("Walk", SportWalk)
                    LegendItem("Hike", SportHike)
                }
            }
        }

        // 4. Weekly Stacked Bars
        items(weeklyTrends.take(16)) { week ->
            WeeklyTrendCard(week = week)
        }
    }
}

@Composable
fun LegendItem(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(label, color = TextSecondaryDark, fontSize = 11.sp)
    }
}

@Composable
fun CareerTotalsSection(totals: TotalsData?) {
    val run = totals?.bySport?.get("Run")
    val walk = totals?.bySport?.get("Walk")
    val hike = totals?.bySport?.get("Hike")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "CAREER TOTALS BY SPORT",
                color = TextSecondaryDark,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SportStatColumn(
                    title = "Running",
                    kms = String.format(Locale.US, "%.1f km", run?.distanceKm ?: 282.6),
                    count = "${run?.count ?: 67} runs",
                    pace = run?.averagePaceFormatted ?: "7:04",
                    color = SportRun
                )

                SportStatColumn(
                    title = "Walking",
                    kms = String.format(Locale.US, "%.1f km", walk?.distanceKm ?: 176.5),
                    count = "${walk?.count ?: 57} walks",
                    pace = walk?.averagePaceFormatted ?: "12:45",
                    color = SportWalk
                )

                SportStatColumn(
                    title = "Hiking",
                    kms = String.format(Locale.US, "%.1f km", hike?.distanceKm ?: 12.0),
                    count = "${hike?.count ?: 7} hikes",
                    pace = hike?.averagePaceFormatted ?: "16:48",
                    color = SportHike
                )
            }
        }
    }
}

@Composable
fun SportStatColumn(title: String, kms: String, count: String, pace: String, color: Color) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(title, color = TextPrimaryDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(kms, color = color, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Text(count, color = TextSecondaryDark, fontSize = 11.sp)
        Text("Avg $pace/km", color = TextMutedDark, fontSize = 11.sp)
    }
}

@Composable
fun WeeklyTrendCard(week: WeeklyTrend) {
    val runKm = week.bySport["Run"]?.distanceKm ?: 0.0
    val walkKm = week.bySport["Walk"]?.distanceKm ?: 0.0
    val hikeKm = week.bySport["Hike"]?.distanceKm ?: 0.0
    val totalKm = week.totalDistanceKm

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
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
                    week.week,
                    color = TextPrimaryDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    String.format(Locale.US, "%.2f km", totalKm),
                    color = TextPrimaryDark,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Stacked Bar Graphic
            if (totalKm > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(SurfaceDark)
                ) {
                    if (runKm > 0) {
                        Box(
                            modifier = Modifier
                                .weight((runKm / totalKm).toFloat())
                                .fillMaxHeight()
                                .background(SportRun)
                        )
                    }
                    if (walkKm > 0) {
                        Box(
                            modifier = Modifier
                                .weight((walkKm / totalKm).toFloat())
                                .fillMaxHeight()
                                .background(SportWalk)
                        )
                    }
                    if (hikeKm > 0) {
                        Box(
                            modifier = Modifier
                                .weight((hikeKm / totalKm).toFloat())
                                .fillMaxHeight()
                                .background(SportHike)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Breakdown labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (runKm > 0) {
                    Text(
                        String.format(Locale.US, "Run: %.1f km", runKm),
                        color = SportRun,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (walkKm > 0) {
                    Text(
                        String.format(Locale.US, "Walk: %.1f km", walkKm),
                        color = SportWalk,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (hikeKm > 0) {
                    Text(
                        String.format(Locale.US, "Hike: %.1f km", hikeKm),
                        color = SportHike,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun FitnessFatigueChartCard(history: List<TrainingLoadPoint>) {
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
                    "TRAINING LOAD CURVE (90 DAYS)",
                    color = TextSecondaryDark,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LegendItem("CTL", FitnessCtl)
                    LegendItem("ATL", FatigueAtl)
                    LegendItem("TSB", FormTsb)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (history.isNotEmpty()) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                ) {
                    val width = size.width
                    val height = size.height

                    val points = history.takeLast(60)
                    if (points.size < 2) return@Canvas

                    val maxVal = points.maxOf { maxOf(it.ctl, it.atl, it.tsb.toDouble()) }.coerceAtLeast(35.0)
                    val minVal = points.minOf { minOf(it.ctl, it.atl, it.tsb.toDouble()) }.coerceAtMost(-20.0)
                    val range = (maxVal - minVal).coerceAtLeast(10.0)

                    fun getY(v: Double): Float {
                        return (height - ((v - minVal) / range * height)).toFloat()
                    }

                    // Zero baseline for TSB
                    val zeroY = getY(0.0)
                    drawLine(
                        color = CardBorderDark,
                        start = Offset(0f, zeroY),
                        end = Offset(width, zeroY),
                        strokeWidth = 1.dp.toPx()
                    )

                    val stepX = width / (points.size - 1)

                    val ctlPath = Path()
                    val atlPath = Path()
                    val tsbPath = Path()

                    points.forEachIndexed { i, p ->
                        val x = i * stepX
                        val yCtl = getY(p.ctl)
                        val yAtl = getY(p.atl)
                        val yTsb = getY(p.tsb)

                        if (i == 0) {
                            ctlPath.moveTo(x, yCtl)
                            atlPath.moveTo(x, yAtl)
                            tsbPath.moveTo(x, yTsb)
                        } else {
                            ctlPath.lineTo(x, yCtl)
                            atlPath.lineTo(x, yAtl)
                            tsbPath.lineTo(x, yTsb)
                        }
                    }

                    drawPath(atlPath, FatigueAtl, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(ctlPath, FitnessCtl, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(tsbPath, FormTsb, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Sync with server to load training curves", color = TextMutedDark, fontSize = 12.sp)
                }
            }
        }
    }
}
