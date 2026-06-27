package com.enigma.dreamer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import com.enigma.dreamer.core.EqState
import com.enigma.dreamer.ui.theme.*

/**
 * 8.8 — EQ / bass boost bottom sheet.
 *
 * Shown when the user taps "Equalizer" in NowPlayingScreen's options menu.
 *
 * Controls:
 *  - Master enable/disable toggle
 *  - Bass boost strength slider (0–1000 milli-bels, shown as 0–100 %)
 *  - Preset selector (one FilterChip per preset returned by the device
 *    AudioEffect framework; hidden if the device reports zero presets)
 *
 * All changes are propagated immediately via callbacks so the user hears
 * the effect of each adjustment without needing to confirm. The ViewModel
 * applies them to [EqualizerController] through [MusicService].
 *
 * Usage — add to NowPlayingScreen's options area:
 *
 *   var showEqSheet by remember { mutableStateOf(false) }
 *   if (showEqSheet) {
 *       EqSheet(
 *           eqState        = playerState.eqState,
 *           onDismiss      = { showEqSheet = false },
 *           onToggleEq     = viewModel::setEqEnabled,
 *           onPresetChange = viewModel::setEqPreset,
 *           onBassChange   = viewModel::setEqBassBoost
 *       )
 *   }
 *
 * And add a menu item in OptionsDropdown / the MoreVert IconButton:
 *
 *   DropdownMenuItem(
 *       text        = { Text("Equalizer") },
 *       onClick     = { showEqSheet = true; showOptionsMenu = false },
 *       leadingIcon = { Icon(Icons.Filled.Equalizer, null) }
 *   )
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqSheet(
    eqState: EqState,
    onDismiss: () -> Unit,
    onToggleEq: (Boolean) -> Unit,
    onPresetChange: (Short) -> Unit,
    onBassChange: (Short) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        dragHandle       = { BottomSheetDefaults.DragHandle(color = TextMuted) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── Header + master toggle ────────────────────────────────────────
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Equalizer, null,
                    tint     = if (eqState.isEnabled) Amber else TextMuted,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Equalizer",
                    style      = MaterialTheme.typography.titleMedium,
                    color      = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier.weight(1f)
                )
                Switch(
                    checked         = eqState.isEnabled,
                    onCheckedChange = onToggleEq,
                    colors          = SwitchDefaults.colors(
                        checkedThumbColor       = Amoled,
                        checkedTrackColor       = Amber,
                        uncheckedThumbColor     = TextSecondary,
                        uncheckedTrackColor     = Surface3,
                        uncheckedBorderColor    = Surface3
                    )
                )
            }

            HorizontalDivider(color = Surface3)

            // ── Bass boost ────────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier          = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.GraphicEq, null,
                        tint     = if (eqState.isEnabled) Amber else TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Bass Boost",
                        style  = MaterialTheme.typography.bodyMedium,
                        color  = if (eqState.isEnabled) TextPrimary else TextMuted
                    )
                    Spacer(Modifier.weight(1f))
                    // Show percentage (0–100 %) mapped from 0–1000 milli-bels
                    Text(
                        "${eqState.bassBoostLevel / 10} %",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (eqState.isEnabled) Amber else TextMuted
                    )
                }
                Slider(
                    value         = eqState.bassBoostLevel / 1000f,
                    onValueChange = { onBassChange((it * 1000).toInt().toShort()) },
                    enabled       = eqState.isEnabled,
                    colors        = SliderDefaults.colors(
                        thumbColor          = Amber,
                        activeTrackColor    = Amber,
                        inactiveTrackColor  = Surface3,
                        disabledThumbColor  = TextMuted,
                        disabledActiveTrackColor   = TextMuted,
                        disabledInactiveTrackColor = Surface3
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── Preset selector ───────────────────────────────────────────────
            if (eqState.presetNames.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Preset",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (eqState.isEnabled) Amber else TextMuted
                    )
                    // Wrap chips in a scrollable row; most devices have 5–10 presets
                    androidx.compose.foundation.rememberScrollState().let { scrollState ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(scrollState),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            eqState.presetNames.forEachIndexed { idx, name ->
                                val selected = idx.toShort() == eqState.currentPreset
                                FilterChip(
                                    selected = selected,
                                    enabled  = eqState.isEnabled,
                                    onClick  = { onPresetChange(idx.toShort()) },
                                    label    = {
                                        Text(
                                            name,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor    = Amber,
                                        selectedLabelColor        = Amoled,
                                        containerColor            = Surface3,
                                        labelColor                = TextSecondary,
                                        disabledContainerColor    = Surface3,
                                        disabledLabelColor        = TextMuted,
                                        disabledSelectedContainerColor = AmberDim
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // ── No-presets fallback ───────────────────────────────────────────
            if (eqState.presetNames.isEmpty() && eqState.isEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surface3)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Info, null, tint = TextMuted,
                        modifier = Modifier.size(16.dp))
                    Text(
                        "This device doesn't expose EQ presets.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }
        }
    }
}

