package com.enigma.dreamer.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.enigma.devlyric.core.LyricDocument
import com.enigma.devlyric.core.LyricFormat
import com.enigma.devlyric.core.LyricParser
import com.enigma.dreamer.core.*
import com.enigma.dreamer.domain.usecase.*
import com.enigma.dreamer.service.MusicService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SongRepository(application)

    // Use-cases that haven't changed
    private val getPlaylistsUseCase           = GetPlaylistsUseCase(repo)
    private val searchSongsUseCase            = SearchSongsUseCase()
    private val sortSongsUseCase              = SortSongsUseCase(repo)
    private val toggleFavoriteUseCase         = ToggleFavoriteUseCase(repo)
    private val observeFavoritesUseCase       = ObserveFavoritesUseCase(repo)
    private val createPlaylistUseCase         = CreatePlaylistUseCase(repo)
    private val addSongToPlaylistUseCase      = AddSongToPlaylistUseCase(repo)
    private val removeSongFromPlaylistUseCase = RemoveSongFromPlaylistUseCase(repo)
    private val deletePlaylistUseCase         = DeletePlaylistUseCase(repo)
    private val renamePlaylistUseCase         = RenamePlaylistUseCase(repo)

    // ── UI State ──────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow<MusicUiState>(MusicUiState.Loading)
    val uiState: StateFlow<MusicUiState> = _uiState.asStateFlow()

    private val readyState: MusicUiState.Ready?
        get() = _uiState.value as? MusicUiState.Ready

    // Scan progress exposed to UI — null = not scanning, 0..N = songs found so far
    private val _scanProgress = MutableStateFlow<Int?>(null)
    val scanProgress: StateFlow<Int?> = _scanProgress.asStateFlow()

    // ── Color extraction guard (Phase 1 fix carried forward) ─────────────────
    private var lastColorSongId: Long? = null

    // ── Service binding ───────────────────────────────────────────────────────

    private var musicService: MusicService? = null
    private var positionJob: Job? = null
    private var playlistObserverJob: Job? = null
    private var favoriteObserverJob: Job? = null
    private var scanJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val b = binder as MusicService.MusicBinder
            musicService = b.service
            viewModelScope.launch {
                b.service.playbackState.collect { ps -> updatePlayback(ps) }
            }
            startPositionTracking()
            readyState?.songs?.let { b.service.tryRestoreSession(it) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            musicService = null
            positionJob?.cancel()
        }
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        val app    = application
        val intent = Intent(app, MusicService::class.java)
        app.startService(intent)
        app.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        loadAll()
    }

    // ── Two-phase load ────────────────────────────────────────────────────────

    /**
     * Phase A — cache read (~50–150 ms): load whatever is already in Room and
     * show it immediately. The user sees their library almost instantly.
     *
     * Phase B — background scan: MediaStore query (metadata only, no file reads).
     * Upserts new/changed songs, removes deleted ones. UI updates via the
     * returned list, which we apply to state directly rather than waiting for
     * the Flow observer to tick (avoids a redundant extra recompose).
     *
     * First-ever launch: cache is empty → we stay in Loading state and show a
     * "Scanning…" indicator until Phase B completes, then transition to Ready.
     * Subsequent launches: Phase A shows the library immediately; Phase B
     * refreshes silently in the background.
     */
    private fun loadAll() {
        viewModelScope.launch {
            _uiState.value = MusicUiState.Loading

            try {
                // ── Phase A: instant cache read ───────────────────────────────
                val playlists   = getPlaylistsUseCase.loadAll()
                val cachedSongs = repo.loadSongsFromCache()

                if (cachedSongs.isNotEmpty()) {
                    // Show library immediately — no waiting for MediaStore
                    _uiState.value = MusicUiState.Ready(
                        songs         = cachedSongs,
                        playlists     = playlists,
                        filteredSongs = cachedSongs
                    )
                    observePlaylists()
                    observeFavorites()
                    musicService?.tryRestoreSession(cachedSongs)
                }
                // else: DB is empty (first launch), stay in Loading

                // ── Phase B: background MediaStore scan ───────────────────────
                launchBackgroundScan(firstLaunch = cachedSongs.isEmpty(), playlists = playlists)

            } catch (e: Exception) {
                _uiState.value = MusicUiState.Error(e.message ?: "Failed to load songs")
            }
        }
    }

    /**
     * Background scan — runs on IO dispatcher, never blocks the main thread.
     * Updates UI state when complete.
     *
     * [firstLaunch] true  → We're in Loading state; transition to Ready when done.
     * [firstLaunch] false → We're already in Ready; silently update songs list.
     */
    private fun launchBackgroundScan(firstLaunch: Boolean, playlists: List<Playlist> = emptyList()) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _scanProgress.value = 0
                val freshSongs = repo.scanAndSync()
                _scanProgress.value = null

                withContext(Dispatchers.Main) {
                    if (firstLaunch) {
                        // First launch: DB was empty, now we have songs — go Ready
                        val pl = getPlaylistsUseCase.loadAll()
                        _uiState.value = MusicUiState.Ready(
                            songs         = freshSongs,
                            playlists     = pl,
                            filteredSongs = freshSongs
                        )
                        observePlaylists()
                        observeFavorites()
                        musicService?.tryRestoreSession(freshSongs)
                    } else {
                        // Subsequent launch: merge fresh data into existing Ready state
                        mutateReady {
                            // Preserve lyricDocument for any songs already loaded
                            val lyricCache = songs
                                .filter { it.lyricDocument != null }
                                .associate { it.id to it.lyricDocument!! }

                            val merged = freshSongs.map { song ->
                                if (song.id in lyricCache)
                                    song.copy(lyricDocument = lyricCache[song.id])
                                else song
                            }
                            copy(
                                songs         = merged,
                                filteredSongs = searchSongsUseCase(merged, searchQuery)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _scanProgress.value = null
                // Scan failure is non-fatal if we already showed cached data
                if (firstLaunch) {
                    withContext(Dispatchers.Main) {
                        _uiState.value = MusicUiState.Error(
                            e.message ?: "Could not scan music library"
                        )
                    }
                }
            }
        }
    }

    /** Manual rescan triggered by pull-to-refresh or settings button. */
    fun rescan() {
        launchBackgroundScan(firstLaunch = false)
    }

    private fun observePlaylists() {
        playlistObserverJob?.cancel()
        playlistObserverJob = viewModelScope.launch {
            getPlaylistsUseCase().collect { playlists ->
                mutateReady { copy(playlists = playlists) }
            }
        }
    }

    private fun observeFavorites() {
        favoriteObserverJob?.cancel()
        favoriteObserverJob = viewModelScope.launch {
            observeFavoritesUseCase().collect { favIds ->
                mutateReady {
                    val updated = songs.map { it.copy(isFavorite = it.id in favIds) }
                    copy(
                        songs         = updated,
                        filteredSongs = searchSongsUseCase(updated, searchQuery)
                    )
                }
            }
        }
    }

    // ── Lazy lyric loading ────────────────────────────────────────────────────

    /**
     * Called by the ViewModel when the user navigates to Now Playing.
     * Loads lyrics for the current song if not already loaded.
     * This is the ONLY place file bytes are read — never at scan time.
     */
    fun loadLyricsIfNeeded(song: Song) {
        if (song.lyricDocument != null) return   // already loaded
        viewModelScope.launch(Dispatchers.IO) {
            val doc = repo.loadLyricsForSong(song) ?: return@launch
            val updated = song.copy(lyricDocument = doc)
            withContext(Dispatchers.Main) {
                mutateReady {
                    val newSongs = songs.map { if (it.id == song.id) updated else it }
                    copy(
                        songs         = newSongs,
                        filteredSongs = searchSongsUseCase(newSongs, searchQuery),
                        playbackState = if (playbackState.currentSong?.id == song.id)
                            playbackState.copy(currentSong = updated)
                        else playbackState
                    )
                }
            }
        }
    }

    // ── Playback controls ─────────────────────────────────────────────────────

    fun playSong(song: Song, queue: List<Song> = readyState?.songs ?: listOf(song)) {
        val index = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        musicService?.setQueue(queue, index) ?: return
        mutateReady {
            copy(playbackState = playbackState.copy(
                isPlaying = true, bufferingState = BufferingState.PREPARING
            ))
        }
        extractAndApplyColor(song.albumArtUri, song.id)
        // Lazily load lyrics now that we know which song is playing
        loadLyricsIfNeeded(song)
    }

    fun playPlaylist(playlist: Playlist) {
        val state = readyState ?: return
        val songs = playlist.songIds.mapNotNull { id -> state.songs.find { it.id == id } }
        if (songs.isEmpty()) return
        musicService?.setQueue(songs, 0)
        mutateReady {
            copy(playbackState = playbackState.copy(
                currentPlaylistId = playlist.id,
                isPlaying         = true,
                bufferingState    = BufferingState.PREPARING
            ))
        }
        songs.firstOrNull()?.let {
            extractAndApplyColor(it.albumArtUri, it.id)
            loadLyricsIfNeeded(it)
        }
    }

    fun togglePlayPause() {
        val service = musicService ?: return
        if (service.playbackState.value.isPlaying) service.pause() else service.play()
    }

    fun next()     { musicService?.next() }
    fun previous() { musicService?.previous() }

    fun seekTo(positionMs: Long) {
        musicService?.seekTo(positionMs)
        mutateReady { copy(playbackState = playbackState.copy(positionMs = positionMs)) }
    }

    fun toggleRepeat() {
        val next = when (currentRepeatMode()) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL  -> RepeatMode.ONE
            RepeatMode.ONE  -> RepeatMode.NONE
        }
        musicService?.setRepeatMode(next)
        mutateReady { copy(playbackState = playbackState.copy(repeatMode = next)) }
    }

    fun toggleShuffle() {
        val next = if (currentShuffleMode() == ShuffleMode.OFF) ShuffleMode.ON else ShuffleMode.OFF
        musicService?.setShuffleMode(next)
        mutateReady { copy(playbackState = playbackState.copy(shuffleMode = next)) }
    }

    fun setPlaybackSpeed(speed: Float) {
        musicService?.setPlaybackSpeed(speed)
        mutateReady { copy(playbackState = playbackState.copy(playbackSpeed = speed)) }
    }

    // ── Queue ─────────────────────────────────────────────────────────────────

    fun toggleQueueView() {
        mutateReady { copy(showQueue = !showQueue) }
    }

    fun playNext(song: Song) {
        val service = musicService ?: return
        val ps      = service.playbackState.value
        if (ps.queue.isEmpty()) { playSong(song); return }
        service.insertIntoQueue(song, (ps.queueIndex + 1).coerceAtMost(ps.queue.size))
        mutateReady {
            copy(playbackState = playbackState.copy(queue = service.playbackState.value.queue))
        }
    }

    fun addToQueue(song: Song) {
        val service = musicService ?: return
        val ps      = service.playbackState.value
        if (ps.queue.isEmpty()) { playSong(song); return }
        service.appendToQueue(song)
        mutateReady {
            copy(playbackState = playbackState.copy(queue = service.playbackState.value.queue))
        }
    }

    fun skipToQueueItem(index: Int) {
        val service = musicService ?: return
        val ps      = service.playbackState.value
        val song    = ps.queue.getOrNull(index) ?: return
        service.setQueue(ps.queue, index)
        mutateReady {
            copy(playbackState = playbackState.copy(
                queue       = ps.queue,
                queueIndex  = index,
                currentSong = song
            ))
        }
        extractAndApplyColor(song.albumArtUri, song.id)
        loadLyricsIfNeeded(song)
    }

    // ── Sleep timer ───────────────────────────────────────────────────────────

    fun startSleepTimer(delayMinutes: Int) { musicService?.startSleepTimer(delayMinutes * 60_000L) }
    fun cancelSleepTimer()                 { musicService?.cancelSleepTimer() }

    // ── Favorites ─────────────────────────────────────────────────────────────

    fun toggleFavorite(song: Song) {
        viewModelScope.launch { toggleFavoriteUseCase(song.id) }
    }

    // ── Search & Sort ─────────────────────────────────────────────────────────

    fun search(query: String) {
        mutateReady {
            copy(searchQuery = query, filteredSongs = searchSongsUseCase(songs, query))
        }
    }

    fun setSortOrder(order: SortOrder) {
        mutateReady {
            val sorted   = sortSongsUseCase(songs, order)
            val filtered = sortSongsUseCase(searchSongsUseCase(sorted, searchQuery), order)
            copy(songs = sorted, filteredSongs = filtered, sortOrder = order)
        }
    }

    // ── Playlists ─────────────────────────────────────────────────────────────

    fun createPlaylist(name: String)                         { viewModelScope.launch { createPlaylistUseCase(name) } }
    fun addSongToPlaylist(song: Song, playlistId: Long)      { viewModelScope.launch { addSongToPlaylistUseCase(song.id, playlistId) } }
    fun removeSongFromPlaylist(song: Song, playlistId: Long) { viewModelScope.launch { removeSongFromPlaylistUseCase(song.id, playlistId) } }
    fun deletePlaylist(playlistId: Long)                     { viewModelScope.launch { deletePlaylistUseCase(playlistId) } }
    fun renamePlaylist(playlistId: Long, newName: String)    { viewModelScope.launch { renamePlaylistUseCase(playlistId, newName) } }

    // ── Lyrics ────────────────────────────────────────────────────────────────

    fun toggleLyrics() { mutateReady { copy(showLyrics = !showLyrics) } }

    fun bakeLyricsToSong(
        song: Song,
        lyricText: String,
        format: LyricFormat,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val doc = repo.bakeLyrics(song.filePath, lyricText, format)
                if (doc != null) {
                    applyUpdatedLyrics(song, doc)
                    onResult(true, "Lyrics baked into ${song.title}")
                } else {
                    val sidecarOk = repo.saveSidecarLrc(song.filePath, lyricText)
                    if (sidecarOk) {
                        val parsed = LyricParser.parse(lyricText, format)
                        applyUpdatedLyrics(song, parsed)
                        onResult(true, "Saved as sidecar .lrc file")
                    } else {
                        onResult(false, "Could not write lyrics — check storage permission")
                    }
                }
            } catch (e: Exception) {
                onResult(false, "Error: ${e.message}")
            }
        }
    }

    private fun applyUpdatedLyrics(song: Song, doc: LyricDocument) {
        val updated = song.copy(lyricDocument = doc)
        mutateReady {
            val newSongs = songs.map { if (it.id == song.id) updated else it }
            copy(
                songs         = newSongs,
                filteredSongs = searchSongsUseCase(newSongs, searchQuery),
                playbackState = if (playbackState.currentSong?.id == song.id)
                    playbackState.copy(currentSong = updated)
                else playbackState
            )
        }
    }

    fun clearError() {
        mutateReady { copy(playbackState = playbackState.copy(error = null)) }
    }

    // ── Dynamic colour ────────────────────────────────────────────────────────

    fun extractAndApplyColor(artUri: Uri?, songId: Long) {
        if (songId == lastColorSongId) return
        lastColorSongId = songId
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = artUri?.let { uri ->
                runCatching {
                    getApplication<Application>().contentResolver
                        .openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            val dominant = if (bitmap != null) dominantColor(bitmap) else DEFAULT_BG
            val contrast = contrastColor(dominant)
            withContext(Dispatchers.Main) {
                mutateReady { copy(dominantColor = dominant, accentTextColor = contrast) }
                bitmap?.let { musicService?.updateAlbumArt(it, dominant) }
            }
        }
    }

    private fun dominantColor(bmp: android.graphics.Bitmap): Int {
        val stepX   = bmp.width  / 12f
        val stepY   = bmp.height / 12f
        val hsv     = FloatArray(3)
        val samples = (0..11).flatMap { row ->
            (0..11).map { col -> bmp.getPixel((col * stepX).toInt(), (row * stepY).toInt()) }
        }
        val vibrant = samples.sortedByDescending {
            Color.colorToHSV(it, hsv); hsv[1] * hsv[2]
        }.take(20)
        val r = (vibrant.map { Color.red(it)   }.average() * 0.7).toInt().coerceIn(0, 255)
        val g = (vibrant.map { Color.green(it) }.average() * 0.7).toInt().coerceIn(0, 255)
        val b = (vibrant.map { Color.blue(it)  }.average() * 0.7).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun contrastColor(bgColor: Int): Int {
        fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        val lum = 0.2126 * lin(Color.red(bgColor)   / 255.0) +
                0.7152 * lin(Color.green(bgColor)  / 255.0) +
                0.0722 * lin(Color.blue(bgColor)   / 255.0)
        return if (lum > 0.179) Color.parseColor("#1A1A1A") else Color.WHITE
    }

    // ── Position tracking ─────────────────────────────────────────────────────

    private fun startPositionTracking() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (true) {
                delay(500)
                val service  = musicService ?: continue
                val pos      = service.currentPosition()
                val state    = readyState   ?: continue
                val song     = state.playbackState.currentSong
                val lyricIdx = song?.lyricDocument?.let { findCurrentLyricLine(it, pos) } ?: -1
                mutateReady {
                    copy(
                        playbackState    = playbackState.copy(positionMs = pos),
                        currentLyricLine = lyricIdx
                    )
                }
            }
        }
    }

    private fun findCurrentLyricLine(doc: LyricDocument, posMs: Long): Int {
        var last = -1
        for ((idx, line) in doc.lines.withIndex()) {
            val ts = line.timestampMs ?: continue
            if (ts <= posMs) last = idx else break
        }
        return last
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun updatePlayback(ps: PlaybackState) {
        mutateReady { copy(playbackState = ps) }
        val song = ps.currentSong ?: return
        if (song.id != lastColorSongId) {
            extractAndApplyColor(song.albumArtUri, song.id)
        }
        // Load lyrics for the newly playing song if not already in memory
        loadLyricsIfNeeded(song)
    }

    private fun mutateReady(block: MusicUiState.Ready.() -> MusicUiState.Ready) {
        val current = readyState ?: return
        _uiState.value = current.block()
    }

    private fun currentRepeatMode()  = readyState?.playbackState?.repeatMode  ?: RepeatMode.NONE
    private fun currentShuffleMode() = readyState?.playbackState?.shuffleMode ?: ShuffleMode.OFF

    override fun onCleared() {
        super.onCleared()
        positionJob?.cancel()
        playlistObserverJob?.cancel()
        favoriteObserverJob?.cancel()
        scanJob?.cancel()
        getApplication<Application>().unbindService(serviceConnection)
    }

    companion object {
        private val DEFAULT_BG = Color.parseColor("#0D0D0D")
    }
}
