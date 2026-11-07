package com.runwise.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
fun SettingsScreen(repository: RunwiseRepository) {
    var serverUrl by remember { mutableStateOf(repository.getServerUrl()) }
    var minCadence by remember { mutableStateOf(repository.getMinCadenceAlert().toString()) }
    var audioSplitMeters by remember { mutableStateOf(repository.getAudioIntervalMeters().toString()) }
    var audio500mCueEnabled by remember { mutableStateOf(repository.isAudio500mCueEnabled()) }

    val syncData by repository.syncData.collectAsState()
    val isSyncing by repository.isSyncing.collectAsState()
    val syncError by repository.syncError.collectAsState()

    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf(false) }

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
                    "APP SETTINGS",
                    color = AerolapBlue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    "Server & Audio Alerts",
                    color = TextPrimaryDark,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        // 1. Server Connection (Tailscale & Local)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark),
                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Cloud, contentDescription = null, tint = AerolapBlue)
                        Text(
                            "Aerolap Server Connection",
                            color = TextPrimaryDark,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Quick Select Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (serverUrl.contains("100.66")) AerolapBlue.copy(alpha = 0.2f) else SurfaceDark)
                                .clickable {
                                    serverUrl = "http://100.66.54.63:8081"
                                    repository.setServerUrl(serverUrl)
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Tailscale IP", color = TextPrimaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (serverUrl.contains("127.0.0.1")) AerolapBlue.copy(alpha = 0.2f) else SurfaceDark)
                                .clickable {
                                    serverUrl = "http://127.0.0.1:8081"
                                    repository.setServerUrl(serverUrl)
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("USB Tunnel", color = TextPrimaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (serverUrl.contains("ts.net")) AerolapBlue.copy(alpha = 0.2f) else SurfaceDark)
                                .clickable {
                                    serverUrl = "http://homelab.tail863fa2.ts.net:8081"
                                    repository.setServerUrl(serverUrl)
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Tailscale DNS", color = TextPrimaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = {
                            serverUrl = it
                            repository.setServerUrl(it)
                        },
                        label = { Text("Server URL") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AerolapBlue,
                            unfocusedBorderColor = CardBorderDark,
                            focusedTextColor = TextPrimaryDark,
                            unfocusedTextColor = TextPrimaryDark
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            repository.setServerUrl(serverUrl)
                            repository.sync { success, error ->
                                isSuccess = success
                                statusMessage = if (success) {
                                    val count = syncData?.activities?.size ?: 0
                                    "✓ Sync Successful! Downloaded $count activities & readiness."
                                } else {
                                    "✗ Connection Error: $error"
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AerolapBlue),
                        enabled = !isSyncing
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("SYNCING...", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("SYNC NOW", fontWeight = FontWeight.Bold)
                        }
                    }

                    statusMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            msg,
                            color = if (isSuccess) PaceOnTarget else FatigueAtl,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    syncData?.syncedAt?.let { at ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Last Synced: $at", color = TextMutedDark, fontSize = 11.sp)
                    }
                }
            }
        }

        // 2. Audio Coaching & Hardware Cadence Alerts
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark),
                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = SportWalk)
                        Text(
                            "Audio Coach Preferences",
                            color = TextPrimaryDark,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Audio Split Distance
                    OutlinedTextField(
                        value = audioSplitMeters,
                        onValueChange = {
                            audioSplitMeters = it
                            it.toIntOrNull()?.let { m -> repository.setAudioIntervalMeters(m) }
                        },
                        label = { Text("Audio Split Distance (Meters - Default 500m)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AerolapBlue,
                            unfocusedBorderColor = CardBorderDark,
                            focusedTextColor = TextPrimaryDark,
                            unfocusedTextColor = TextPrimaryDark
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Min Cadence Alert
                    OutlinedTextField(
                        value = minCadence,
                        onValueChange = {
                            minCadence = it
                            it.toIntOrNull()?.let { c -> repository.setMinCadenceAlert(c) }
                        },
                        label = { Text("Minimum Cadence Warning Threshold (SPM)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AerolapBlue,
                            unfocusedBorderColor = CardBorderDark,
                            focusedTextColor = TextPrimaryDark,
                            unfocusedTextColor = TextPrimaryDark
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 500m Intermediate Checkpoint Cues Toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                audio500mCueEnabled = !audio500mCueEnabled
                                repository.setAudio500mCueEnabled(audio500mCueEnabled)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "500m Intermediate Cues",
                                color = TextPrimaryDark,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Announce 'Last 500 meters' split pace halfway through every kilometer",
                                color = TextMutedDark,
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = audio500mCueEnabled,
                            onCheckedChange = {
                                audio500mCueEnabled = it
                                repository.setAudio500mCueEnabled(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = AerolapBlue
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Pacing warnings (too fast / too slow) are dynamically dictated by your selected workout and plan, not static settings.",
                        color = TextMutedDark,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // 3. Athlete Profile & Race Target Info
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark),
                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderDark)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "ATHLETE & RACE PROFILE",
                        color = TextSecondaryDark,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Athlete: Aashay Kadu", color = TextPrimaryDark, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Connected Gmail: aashaykdgr@gmail.com", color = TextSecondaryDark, fontSize = 13.sp)
                    Text("Target Race: 10K on 22nd November 2026", color = AerolapBlue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Target Pace: 6:15 min/km (1:02:30 Finish)", color = PaceOnTarget, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Hardware Cadence: Redmi Note 8 Pro Step Sensor Active", color = TextMutedDark, fontSize = 12.sp)
                }
            }
        }
    }
}
