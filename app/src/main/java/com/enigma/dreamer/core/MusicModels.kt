package com.enigma.dreamer.core

import android.net.Uri
import com.enigma.devlyric.core.LyricDocument

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,          // ms
    val uri: Uri,
    val albumArtUri: Uri? = null,
    val lyricDocument: LyricDocument? = null,
    val isFavorite: Boolean = false,
    val year: Int = 0,
    val trackNumber: Int = 0,
    val filePath: String = "",
    val fileSize: Long = 0L,
    val mimeType: String = ""
)

data class Playlist(
    val id: Long,
    val name: String,
    val songIds: List<Long> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

enum class RepeatMode  { NONE, ONE, ALL }
enum class ShuffleMode { OFF, ON }

enum class SortOrder {
    TITLE_ASC, TITLE_DESC,
    ARTIST_ASC, ARTIST_DESC,
    ALBUM_ASC,
    DURATION_ASC, DURATION_DESC,
    DATE_ADDED_DESC,
    FAVORITES_FIRST
}

enum class BufferingState { IDLE, PREPARING, READY, ERROR }

data class SleepTimer(
    val isActive: Boolean = false,
    val endsAtMs: Long = 0L
) {
    val remainingMs: Long get() = (endsAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
}

data class PlaybackState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.NONE,
    val shuffleMode: ShuffleMode = ShuffleMode.OFF,
    val currentPlaylistId: Long? = null,
    val queue: List<Song> = emptyList(),
    val queueIndex: Int = 0,
    val bufferingState: BufferingState = BufferingState.IDLE,
    val playbackSpeed: Float = 1.0f,
    val sleepTimer: SleepTimer = SleepTimer(),
    val error: String? = null
) {
    val progress: Float      get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val hasNext: Boolean     get() = queueIndex < queue.size - 1 || repeatMode == RepeatMode.ALL
    val hasPrevious: Boolean get() = queueIndex > 0 || repeatMode == RepeatMode.ALL
    val isPreparing: Boolean get() = bufferingState == BufferingState.PREPARING
}

// ── Phase 7.2: split state (A-1) ─────────────────────────────────────────────
//
// Previously MusicUiState.Ready carried all 11 fields — library data AND
// playback data — in a single data class. The position-tracking loop fired
// mutateReady{} every 500 ms while playing, which copied the entire object
// (including songs: List<Song> with potentially thousands of entries) just to
// update positionMs and currentLyricLine.
//
// Now state is split into two independent StateFlows:
//
//   LibraryState  — songs, playlists, search, sort
//                   Changes only on: scan, search, sort, favorite toggle,
//                   playlist edit, lyric bake. Stable between songs.
//
//   PlayerState   — playback position, current song, lyrics display,
//                   dynamic colors. The position ticker ONLY touches this.
//
// MusicUiState is kept as a thin loading/error envelope. Once loaded, both
// flows are non-null and screens subscribe to only what they need:
//   LibraryScreen   → LibraryState + PlayerState.mini (song, isPlaying, progress)
//   NowPlayingScreen → PlayerState only
//   LyricEditorScreen → already parameterised, receives individual fields

/**
 * Stable library data — songs, playlists, search/sort preferences.
 *
 * Emitted by [MusicViewModel.libraryState]. Recomposed only when the
 * library actually changes, not on every playback tick.
 */
data class LibraryState(
    val songs: List<Song> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val filteredSongs: List<Song> = emptyList(),
    val searchQuery: String = "",
    val sortOrder: SortOrder = SortOrder.TITLE_ASC
)

/**
 * Fast-changing playback data — position, current song, lyrics, colors.
 *
 * Emitted by [MusicViewModel.playerState]. Updated every 500 ms while
 * playing. Screens that don't need this (e.g. the Playlists tab) don't
 * subscribe and are never recomposed by position ticks.
 */
data class PlayerState(
    val playbackState: PlaybackState = PlaybackState(),
    val currentLyricLine: Int = -1,
    val showLyrics: Boolean = false,
    val showQueue: Boolean = false,
    val dominantColor: Int = 0xFF0D0D0D.toInt(),
    val accentTextColor: Int = 0xFFEEEEEE.toInt()
)

/**
 * Top-level UI state — loading/error envelope only.
 *
 * [Ready] carries no data itself; once in this state the UI reads from
 * [MusicViewModel.libraryState] and [MusicViewModel.playerState] directly.
 */
sealed class MusicUiState {
    object Loading : MusicUiState()
    object Ready   : MusicUiState()
    data class Error(val message: String) : MusicUiState()
}