package com.enigma.dreamer.viewmodel

import android.graphics.Bitmap
import com.enigma.dreamer.core.PlaybackState
import com.enigma.dreamer.core.RepeatMode
import com.enigma.dreamer.core.ShuffleMode
import com.enigma.dreamer.core.Song
import com.enigma.dreamer.service.MusicService
import kotlinx.coroutines.flow.StateFlow

/**
 * 9.1 — Thin wrapper around [com.enigma.dreamer.service.MusicService] calls.
 *
 * Extracted from [MusicViewModel], which previously called
 * `musicService?.xxx` inline throughout its 450+ lines. Moving all direct
 * service invocations here gives us:
 *
 *  1. A single null-check surface (`service ?: return false`) instead of
 *     repeated `musicService?.let { … }` guards.
 *  2. A seam for unit-testing playback logic: replace [PlaybackController]
 *     with a fake in tests, no need to mock a real [com.enigma.dreamer.service.MusicService] binder.
 *  3. Clear separation of concerns — the ViewModel owns _state_, the
 *     controller owns _commands_.
 *
 * All methods return [Boolean] indicating whether the service was available
 * to receive the command (false = service not yet bound).
 *
 * The ViewModel sets [service] when [ServiceConnection.onServiceConnected]
 * fires, and nulls it in [onServiceDisconnected].
 */
class PlaybackController {

    @Volatile var service: MusicService? = null

    val playbackState: StateFlow<PlaybackState>?
        get() = service?.playbackState

    // ── Transport ─────────────────────────────────────────────────────────────

    fun play(): Boolean  = service?.play()  != null
    fun pause(): Boolean = service?.pause() != null
    fun next(): Boolean  = service?.next()  != null

    fun previous(): Boolean {
        service?.previous() ?: return false
        return true
    }

    fun seekTo(positionMs: Long): Boolean {
        service?.seekTo(positionMs) ?: return false
        return true
    }

    fun currentPosition(): Long = service?.currentPosition() ?: 0L

    // ── Queue ─────────────────────────────────────────────────────────────────

    fun setQueue(queue: List<Song>, index: Int): Boolean {
        service?.setQueue(queue, index) ?: return false
        return true
    }

    fun insertIntoQueue(song: Song, insertIndex: Int): Boolean {
        service?.insertIntoQueue(song, insertIndex) ?: return false
        return true
    }

    fun appendToQueue(song: Song): Boolean {
        service?.appendToQueue(song) ?: return false
        return true
    }

    fun reorderQueue(newQueue: List<Song>): Boolean {
        service?.reorderQueue(newQueue) ?: return false
        return true
    }

    // ── Modes ─────────────────────────────────────────────────────────────────

    fun setRepeatMode(mode: RepeatMode): Boolean {
        service?.setRepeatMode(mode) ?: return false
        return true
    }

    fun setShuffleMode(mode: ShuffleMode): Boolean {
        service?.setShuffleMode(mode) ?: return false
        return true
    }

    fun setPlaybackSpeed(speed: Float): Boolean {
        service?.setPlaybackSpeed(speed) ?: return false
        return true
    }

    // ── Sleep timer ───────────────────────────────────────────────────────────

    fun startSleepTimer(delayMs: Long): Boolean {
        service?.startSleepTimer(delayMs) ?: return false
        return true
    }

    fun cancelSleepTimer(): Boolean {
        service?.cancelSleepTimer() ?: return false
        return true
    }

    // ── Session ───────────────────────────────────────────────────────────────

    suspend fun tryRestoreSession(allSongs: List<Song>): Boolean =
        service?.tryRestoreSession(allSongs) ?: false

    // ── Album art ─────────────────────────────────────────────────────────────

    fun updateAlbumArt(bitmap: Bitmap?, accentColor: Int): Boolean {
        service?.updateAlbumArt(bitmap, accentColor) ?: return false
        return true
    }

    // ── EQ ────────────────────────────────────────────────────────────────────

    fun setEqEnabled(enabled: Boolean): Boolean {
        service?.setEqEnabled(enabled) ?: return false
        return true
    }

    fun setEqPreset(preset: Short): Boolean {
        service?.setEqPreset(preset) ?: return false
        return true
    }

    fun setEqBassBoost(strength: Short): Boolean {
        service?.setEqBassBoost(strength) ?: return false
        return true
    }

    val equalizerController get() = service?.equalizerController
}