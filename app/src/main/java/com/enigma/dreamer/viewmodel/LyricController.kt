package com.enigma.dreamer.viewmodel

import com.enigma.devlyric.core.LyricDocument
import com.enigma.dreamer.core.Song
import com.enigma.dreamer.core.SongRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 9.3 — Lyric lazy-load and line-tracking controller.
 *
 * Extracted from [MusicViewModel] (was: [loadLyricsIfNeeded],
 * [findCurrentLyricLine], [applyUpdatedLyrics]).
 *
 * Responsibilities:
 *  1. Load a [com.enigma.devlyric.core.LyricDocument] for a song on demand (IO-dispatched, no-op
 *     if lyrics are already present in the [com.enigma.dreamer.core.Song] object).
 *  2. Track the currently active lyric line given a playback position.
 *  3. Apply an updated lyric document back to app state via [callbacks].
 *
 * [callbacks] is a thin interface the ViewModel implements so this class
 * never holds a reference to the ViewModel itself, keeping the dependency
 * direction clean and the class unit-testable without a full ViewModel.
 */
class LyricController(
    private val repository: SongRepository,
    private val scope: CoroutineScope,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        /** Called on the main thread after lyrics are loaded for [song]. */
        fun onLyricsLoaded(song: Song, doc: LyricDocument)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Triggers a lazy load of lyrics for [song] if they haven't been loaded yet.
     * No-op if [song.lyricDocument] is already non-null.
     *
     * Calls [Callbacks.onLyricsLoaded] on the main thread when complete.
     */
    fun loadIfNeeded(song: Song) {
        if (song.lyricDocument != null) return
        scope.launch(Dispatchers.IO) {
            val doc = repository.loadLyricsForSong(song) ?: return@launch
            withContext(Dispatchers.Main) {
                callbacks.onLyricsLoaded(song, doc)
            }
        }
    }

    /**
     * Returns the index of the lyric line in [doc] whose timestamp is ≤
     * [posMs], or -1 if no line has been reached yet.
     *
     * Pure function — safe to call from any thread, used in the position-
     * tracking loop every 500 ms while playing.
     */
    fun currentLine(doc: LyricDocument, posMs: Long): Int {
        var last = -1
        for ((idx, line) in doc.lines.withIndex()) {
            val ts = line.timestampMs ?: continue
            if (ts <= posMs) last = idx else break
        }
        return last
    }
}