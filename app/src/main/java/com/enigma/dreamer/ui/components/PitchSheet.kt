package com.enigma.dreamer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enigma.dreamer.ui.theme.*

/**
 * 11.5 — Per-song pitch adjustment bottom sheet.
 *
 * Displayed from NowPlayingScreen's options menu (alongside the existing
 * Equalizer entry). Shows a slider and +/− buttons for semitone offsets
 * from –12 to +12 (one octave down to one octave up).
 *
 * Usage:
 *   var showPitchSheet by remember { mutableStateOf(false) }
 *   if (showPitchSheet) {
 *       PitchSheet(
 *           semitones  = pitchSemitones,
 *           onDismiss  = { showPitchSheet = false },
 *           onSetPitch = viewModel::setPitch
 *       )
 *   }
 *
 * Add a menu entry in OptionsDropdown:
 *   DropdownMenuItem(
 *       text        = { Text("Pitch") },
 *       onClick     = { showPitchSheet = true; showOptionsMenu = false },
 *       leadingIcon = { Icon(Icons.Filled.MusicNote, null) }
 *   )
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PitchSheet(
    semitones: Int,
    onDismiss: () -> Unit,
    onSetPitch: (Int) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        dragHandle       = { BottomSheetDefaults.DragHandle(color = TextMuted) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.MusicNote, null,
                    tint     = Amber,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Pitch",
                    style      = MaterialTheme.typography.titleMedium,
                    color      = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier.weight(1f)
                )
                // Reset button
                if (semitones != 0) {
                    TextButton(onClick = { onSetPitch(0) }) {
                        Text("Reset", color = Amber,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            HorizontalDivider(color = Surface3)

            // ── Display ───────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface3)
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val sign   = when {
                        semitones > 0 -> "+"
                        semitones < 0 -> ""
                        else          -> ""
                    }
                    Text(
                        "$sign$semitones",
                        style      = MaterialTheme.typography.displayLarge,
                        color      = if (semitones == 0) TextMuted else Amber,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        "semitones",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }

            // ── Slider ────────────────────────────────────────────────────────
            Slider(
                value         = semitones.toFloat(),
                onValueChange = { onSetPitch(it.toInt()) },
                valueRange    = -12f..12f,
                steps         = 23,   // 25 positions → 23 steps between them
                colors        = SliderDefaults.colors(
                    thumbColor         = Amber,
                    activeTrackColor   = Amber,
                    inactiveTrackColor = Surface3
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // ── Labels ────────────────────────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("−12", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                Text("0",   style = MaterialTheme.typography.bodySmall, color = TextMuted)
                Text("+12", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }

            // ── ± buttons for fine control ────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                // −1 semitone
                OutlinedButton(
                    onClick  = { onSetPitch((semitones - 1).coerceAtLeast(-12)) },
                    enabled  = semitones > -12,
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                    border   = androidx.compose.foundation.BorderStroke(
                        1.dp, if (semitones > -12) Amber else Surface3
                    )
                ) {
                    Icon(Icons.Filled.Remove, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("−1 st", style = MaterialTheme.typography.bodySmall)
                }

                // +1 semitone
                OutlinedButton(
                    onClick  = { onSetPitch((semitones + 1).coerceAtMost(12)) },
                    enabled  = semitones < 12,
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                    border   = androidx.compose.foundation.BorderStroke(
                        1.dp, if (semitones < 12) Amber else Surface3
                    )
                ) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("+1 st", style = MaterialTheme.typography.bodySmall)
                }
            }

            // ── Preset labels ─────────────────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(-12 to "−1 oct", -7 to "5th↓", 0 to "Normal",
                       7 to "5th↑", 12 to "+1 oct").forEach { (st, label) ->
                    FilterChip(
                        selected = semitones == st,
                        onClick  = { onSetPitch(st) },
                        label    = {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        },
                        modifier = Modifier.weight(1f),
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber,
                            selectedLabelColor     = Amoled,
                            containerColor         = Surface3,
                            labelColor             = TextSecondary
                        )
                    )
                }
            }
        }
    }
}
