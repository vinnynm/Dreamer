package com.enigma.dreamer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enigma.dreamer.core.SleepTimer
import com.enigma.dreamer.core.SortOrder
import com.enigma.dreamer.ui.theme.Amber
import com.enigma.dreamer.ui.theme.Amoled
import com.enigma.dreamer.ui.theme.ErrorRed
import com.enigma.dreamer.ui.theme.Surface2
import com.enigma.dreamer.ui.theme.Surface3
import com.enigma.dreamer.ui.theme.TextMuted
import com.enigma.dreamer.ui.theme.TextPrimary
import com.enigma.dreamer.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    playbackSpeed: Float,
    sortOrder: SortOrder,
    sleepTimer: SleepTimer,
    onSpeedChange: (Float) -> Unit,
    onSortChange: (SortOrder) -> Unit,
    onStartSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onBack: () -> Unit
) {
    val speedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    val sleepOptions = listOf(5, 10, 15, 20, 30, 45, 60)
    var showSleepDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Amoled,
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Amoled)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── Sleep timer ─────────────────────────────────────────────────

            SectionHeader("Sleep Timer")
            if (sleepTimer.isActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Surface2)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Bedtime, null, tint = Amber, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    val remaining = sleepTimer.remainingMs
                    val mins      = remaining / 60000
                    val secs      = (remaining % 60000) / 1000
                    Text("Pausing in %d:%02d".format(mins, secs),
                        color = TextPrimary, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancelSleepTimer) {
                        Text("Cancel", color = ErrorRed)
                    }
                }
            } else {
                SettingsButton(
                    icon    = Icons.Filled.Bedtime,
                    label   = "Start Sleep Timer",
                    onClick = { showSleepDialog = true }
                )
            }

            // ── Playback speed ───────────────────────────────────────────────

            SectionHeader("Playback Speed")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface2)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                speedOptions.forEach { speed ->
                    val selected = speed == playbackSpeed
                    FilterChip(
                        selected = selected,
                        onClick  = { onSpeedChange(speed) },
                        label    = { Text("${speed}x", style = MaterialTheme.typography.bodySmall) },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor    = Amber,
                            selectedLabelColor        = Amoled,
                            containerColor            = Surface3,
                            labelColor                = TextSecondary
                        )
                    )
                }
            }

            // ── Sort order ───────────────────────────────────────────────────

            SectionHeader("Library Sort Order")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface2)
                    .padding(vertical = 4.dp)
            ) {
                // FIX: was SortOrder.values() — deprecated since Kotlin 1.9.
                // SortOrder.entries returns an immutable List instead of Array,
                // is stable across calls, and avoids array copy overhead.
                SortOrder.entries.forEach { order ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = sortOrder == order,
                            onClick  = { onSortChange(order) },
                            colors   = RadioButtonDefaults.colors(selectedColor = Amber)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            order.displayName(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (sortOrder == order) Amber else TextPrimary
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showSleepDialog) {
        AlertDialog(
            onDismissRequest = { showSleepDialog = false },
            containerColor   = Surface2,
            title = { Text("Sleep Timer", color = TextPrimary) },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sleepOptions.forEach { mins ->
                        TextButton(
                            onClick = {
                                onStartSleepTimer(mins)
                                showSleepDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("$mins minutes", color = TextPrimary)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSleepDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Amber,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp)
    )
}

@Composable
private fun SettingsButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface2)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = Amber, modifier = Modifier.size(20.dp))
        Text(label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, null, tint = TextMuted)
    }
}

private fun SortOrder.displayName(): String = when (this) {
    SortOrder.TITLE_ASC      -> "Title A→Z"
    SortOrder.TITLE_DESC     -> "Title Z→A"
    SortOrder.ARTIST_ASC     -> "Artist A→Z"
    SortOrder.ARTIST_DESC    -> "Artist Z→A"
    SortOrder.ALBUM_ASC      -> "Album A→Z"
    SortOrder.DURATION_ASC   -> "Shortest first"
    SortOrder.DURATION_DESC  -> "Longest first"
    SortOrder.DATE_ADDED_DESC-> "Recently added"
    SortOrder.FAVORITES_FIRST-> "Favourites first"
}
