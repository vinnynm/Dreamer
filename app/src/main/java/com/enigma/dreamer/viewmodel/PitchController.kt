package com.enigma.dreamer.viewmodel

import com.enigma.dreamer.service.MusicService

/**
 * 11.5 — Per-song pitch adjustment.
 *
 * ExoPlayer's [PlaybackParameters] supports independent speed and pitch
 * control. We already set speed via [MusicService.setPlaybackSpeed]; pitch
 * is a separate semitone offset (–12 to +12) stored here and applied as a
 * multiplier: pitch = 2^(semitones / 12).
 *
 * Design: pitch is NOT persisted per-song in Room for the initial implementation.
 * It resets to 0 when the service restarts. Per-song persistence is tracked as
 * a future improvement (would require a new `pitch` column in `songs` table).
 *
 * Usage in [MusicViewModel]:
 *   val pitchController = PitchController()
 *   fun setPitch(semitones: Int) { pitchController.set(semitones, playback.service) }
 */
class PitchController {

    /** Current pitch offset in semitones. Range: [–12, +12]. */
    var semitones: Int = 0
        private set

    /**
     * Apply [semitones] offset to [service].
     * ExoPlayer's PlaybackParameters takes a linear pitch multiplier;
     * we convert from semitones using the standard formula.
     */
    fun set(semitones: Int, service: MusicService?) {
        this.semitones = semitones.coerceIn(-12, 12)
        service?.setPitch(pitchMultiplier())
    }

    /** Reset pitch to 0 without touching service (e.g. on service disconnect). */
    fun reset() { semitones = 0 }

    /** Linear multiplier for the given semitone offset. */
    fun pitchMultiplier(): Float = Math.pow(2.0, semitones / 12.0).toFloat()
}
