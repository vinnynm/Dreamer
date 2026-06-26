package com.enigma.dreamer.core

/**
 * 8.8 — Immutable snapshot of the equalizer / bass-boost state.
 *
 * Carried inside [PlayerState] so the EQ sheet in NowPlayingScreen
 * recomposes only when EQ state actually changes — not on every position tick.
 *
 * [presetNames] is populated once when [EqualizerController.attach] succeeds.
 * Empty list means the device's AudioEffect framework didn't return any presets
 * (rare but possible on some stripped-down OEM builds).
 */
data class EqState(
    val isEnabled:      Boolean     = false,
    val bassBoostLevel: Short       = 500,
    val currentPreset:  Short       = 0,
    val presetNames:    List<String> = emptyList()
)