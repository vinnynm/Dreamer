package com.enigma.dreamer.core

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer

/**
 * 8.8 — Per-song EQ and bass boost controller.
 *
 * Wraps the platform [android.media.audiofx.Equalizer] and [android.media.audiofx.BassBoost] AudioEffects and exposes a
 * small, safe API for the ViewModel to drive. All methods are safe to call
 * from any thread; the underlying AudioEffect API is thread-safe once the
 * session ID is known.
 *
 * Lifecycle:
 *   1. Create via [EqualizerController] once when the service is ready.
 *   2. Call [attach] every time the ExoPlayer audio session changes
 *      (or on first player creation).
 *   3. Call [release] in MusicService.onDestroy().
 *
 * Design decision — EQ state is app-global (not per-song) for now.
 * The UI exposes a toggle (bass boost on/off) + preset selector, which is the
 * 95 % use case. Per-song persistence is left as a future improvement.
 */
class EqualizerController {

    @Volatile private var eq:        Equalizer? = null
    @Volatile private var bassBoost: BassBoost? = null

    // ── State ─────────────────────────────────────────────────────────────────

    var isEnabled:      Boolean = false; private set
    var bassBoostLevel: Short   = 500;   private set   // 0–1000 milli-bels
    var currentPreset:  Short   = 0;     private set

    // ── Attach / detach ───────────────────────────────────────────────────────

    /**
     * Attach effects to [audioSessionId]. Safe to call on session changes.
     * Existing effects are released before new ones are created.
     */
    fun attach(audioSessionId: Int) {
        release()
        if (audioSessionId == 0) return   // ExoPlayer not ready yet

        runCatching {
            eq = Equalizer(0, audioSessionId).apply {
                enabled = isEnabled
                if (isEnabled) {
                    runCatching { usePreset(currentPreset) }
                }
            }
        }
        runCatching {
            bassBoost = BassBoost(0, audioSessionId).apply {
                enabled    = isEnabled
                setStrength(bassBoostLevel)
            }
        }
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        eq?.enabled        = enabled
        bassBoost?.enabled = enabled
    }

    fun setBassBoost(strength: Short) {
        bassBoostLevel = strength.coerceIn(0, 1000)
        if (isEnabled) bassBoost?.setStrength(bassBoostLevel)
    }

    fun setPreset(preset: Short) {
        val safePreset = preset.coerceIn(0, (numberOfPresets - 1).coerceAtLeast(0).toShort())
        currentPreset  = safePreset
        if (isEnabled) runCatching { eq?.usePreset(safePreset) }
    }

    // ── Read-only info ────────────────────────────────────────────────────────

    val numberOfPresets: Int get() = eq?.numberOfPresets?.toInt() ?: 0

    fun presetName(index: Short): String = runCatching {
        eq?.getPresetName(index) ?: "Preset $index"
    }.getOrDefault("Preset $index")

    val presetNames: List<String>
        get() = (0 until numberOfPresets).map { presetName(it.toShort()) }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun release() {
        runCatching { eq?.release() }
        runCatching { bassBoost?.release() }
        eq        = null
        bassBoost = null
    }
}