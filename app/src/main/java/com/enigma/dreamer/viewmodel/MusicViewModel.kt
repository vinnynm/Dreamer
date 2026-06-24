package com.enigma.dreamer.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.graphics.toColorInt
import androidx.palette.graphics.Palette
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Central ViewModel for Dreamer.
 *
 * Phase 7.2 (A-1): MusicUiState.Ready no longer carries data. State is now
 * split into two independent StateFlows:
 *
 *   [libraryState] — songs, playlists, search, sort.
 *                    Only mutated on scan, search/sort changes, favorite
 *                    toggles, playlist edits, and lyric bakes. Stable
 *                    between songs, never touched by the position ticker.
 *
 *   [playerState]  — playback position, current song, lyric line, colors.
 *                    The 500 ms position ticker ONLY mutates this flow.
 *                    Copying PlayerState (7 fields, no List<Song>) is ~100×
 *                    cheaper than copying the old MusicUiState.Ready (11
 *                    fields including a List<Song> of thousands of entries).
 *
 * Phase 7.3 (B-6): observeSongs() is now collected. The ViewModel subscribes
 * to the Room songs Flow so the UI reacts automatically to MediaStore changes
 * (downloads, deletions) without requiring a manual rescan tap. The hot scan
 * path (scanAndSync) remains the primary update mechanism; the observer is a
 * safety net for out-of-band changes.
 *
 * Previously applied fixes retained:
 *   B-3  – tryRestoreSession race removed from onServiceConnected
 *   B-9  – AndroidX Palette for color extraction
 *   B-13 – position ticker skips work when paused
 */
class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SongRepository(application)

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

    // ── Split state (Phase 7.2) ───────────────────────────────────────────────

    private val _uiState      = MutableStateFlow<MusicUiState>(MusicUiState.Loading)
    val uiState: StateFlow<MusicUiState> = _uiState.asStateFlow()

    private val _libraryState = MutableStateFlow(LibraryState())
    val libraryState: StateFlow<LibraryState> = _libraryState.asStateFlow()

    private val _playerState  = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _scanProgress = MutableStateFlow<Int?>(null)
    val scanProgress: StateFlow<Int?> = _scanProgress.asStateFlow()

    // ── Color extraction state ────────────────────────────────────────────────

    @Volatile private var lastColorSongId: Long? = null
    private var colorJob: Job? = null

    // ── Service binding ───────────────────────────────────────────────────────

    private var musicService: MusicService? = null
    private var positionJob: Job? = null
    private var playlistObserverJob: Job? = null
    private var favoriteObserverJob: Job? = null
    private var songObserverJob: Job? = null   // Phase 7.3
    private var scanJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val b = binder as MusicService.MusicBinder
            musicService = b.service
            viewModelScope.launch {
                b.service.playbackState.collect { ps -> updatePlayback(ps) }
            }
            startPositionTracking()
            // FIX B-3: tryRestoreSession NOT called here — race condition removed.
            // loadAll() calls it once songs are confirmed available.
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

    private fun loadAll() {
        viewModelScope.launch {
            _uiState.value = MusicUiState.Loading
            try {
                val playlists   = getPlaylistsUseCase.loadAll()
                val cachedSongs = repo.loadSongsFromCache()

                if (cachedSongs.isNotEmpty()) {
                    _libraryState.value = LibraryState(
                        songs         = cachedSongs,
                        playlists     = playlists,
                        filteredSongs = cachedSongs
                    )
                    _uiState.value = MusicUiState.Ready
                    observePlaylists()
                    observeFavorites()
                    observeSongsFromDb()   // Phase 7.3
                    // FIX B-3: restore session here, after songs are confirmed
                    musicService?.tryRestoreSession(cachedSongs)
                }
                launchBackgroundScan(firstLaunch = cachedSongs.isEmpty(), playlists = playlists)
            } catch (e: Exception) {
                _uiState.value = MusicUiState.Error(e.message ?: "Failed to load songs")
            }
        }
    }

    private fun launchBackgroundScan(
        firstLaunch: Boolean,
        playlists: List<Playlist> = emptyList()
    ) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _scanProgress.value = 0
                val freshSongs = repo.scanAndSync()
                _scanProgress.value = null

                withContext(Dispatchers.Main) {
                    if (firstLaunch) {
                        val pl = getPlaylistsUseCase.loadAll()
                        _libraryState.value = LibraryState(
                            songs         = freshSongs,
                            playlists     = pl,
                            filteredSongs = freshSongs
                        )
                        _uiState.value = MusicUiState.Ready
                        observePlaylists()
                        observeFavorites()
                        observeSongsFromDb()   // Phase 7.3
                        musicService?.tryRestoreSession(freshSongs)
                    } else {
                        mutateLibrary {
                            // Preserve any in-memory lyric documents from the
                            // previous library state so they survive the scan merge.
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

    fun rescan() { launchBackgroundScan(firstLaunch = false) }

    // ── Phase 7.3: wire observeSongs() (FIX B-6) ─────────────────────────────
    //
    // Previously SongRepository.observeSongs() was exported but never collected,
    // so MediaStore changes made by other apps (a file manager deleting a song,
    // a download completing) were invisible until the user tapped Rescan.
    //
    // Now we collect the Room Flow as a passive observer. When scanAndSync()
    // writes new data to Room, the Flow emits and we apply a lightweight merge
    // that preserves in-memory lyric documents. This replaces the need for
    // manual mutateReady calls after each scan.
    //
    // The active scan path (launchBackgroundScan) still drives the primary
    // update — the observer is a reactive complement, not a replacement.
    // If observeSongsFromDb and launchBackgroundScan race on first launch,
    // the scan's explicit withContext(Main) block runs last and wins, which
    // is the desired outcome (freshest data from MediaStore, not stale cache).
    private fun observeSongsFromDb() {
        songObserverJob?.cancel()
        songObserverJob = viewModelScope.launch {
            // drop(1): skip the initial emission which duplicates the cache
            // load already applied in loadAll(). Only react to subsequent
            // Room writes (i.e. from scanAndSync completing).
            repo.observeSongs().drop(1).collect { dbSongs ->
                mutateLibrary {
                    val lyricCache = songs
                        .filter { it.lyricDocument != null }
                        .associate { it.id to it.lyricDocument!! }
                    val merged = dbSongs.map { song ->
                        song.copy(
                            lyricDocument = lyricCache[song.id],
                            isFavorite    = song.isFavorite
                        )
                    }
                    copy(
                        songs         = merged,
                        filteredSongs = searchSongsUseCase(merged, searchQuery)
                    )
                }
            }
        }
    }

    private fun observePlaylists() {
        playlistObserverJob?.cancel()
        playlistObserverJob = viewModelScope.launch {
            getPlaylistsUseCase().collect { playlists ->
                mutateLibrary { copy(playlists = playlists) }
            }
        }
    }

    private fun observeFavorites() {
        favoriteObserverJob?.cancel()
        favoriteObserverJob = viewModelScope.launch {
            observeFavoritesUseCase().collect { favIds ->
                mutateLibrary {
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

    fun loadLyricsIfNeeded(song: Song) {
        if (song.lyricDocument != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val doc = repo.loadLyricsForSong(song) ?: return@launch
            val updated = song.copy(lyricDocument = doc)
            withContext(Dispatchers.Main) {
                mutateLibrary {
                    val newSongs = songs.map { if (it.id == song.id) updated else it }
                    copy(
                        songs         = newSongs,
                        filteredSongs = searchSongsUseCase(newSongs, searchQuery)
                    )
                }
                // Also update the current song in PlayerState if it's the same song
                mutatePlayer {
                    if (playbackState.currentSong?.id == song.id)
                        copy(playbackState = playbackState.copy(currentSong = updated))
                    else this
                }
            }
        }
    }

    // ── Playback controls ─────────────────────────────────────────────────────

    fun playSong(song: Song, queue: List<Song> = _libraryState.value.songs) {
        val index = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        musicService?.setQueue(queue, index) ?: return
        mutatePlayer {
            copy(playbackState = playbackState.copy(
                isPlaying      = true,
                bufferingState = BufferingState.PREPARING
            ))
        }
        loadLyricsIfNeeded(song)
        scheduleColorExtraction(song.albumArtUri, song.id)
    }

    fun playPlaylist(playlist: Playlist) {
        val lib   = _libraryState.value
        val songs = playlist.songIds.mapNotNull { id -> lib.songs.find { it.id == id } }
        if (songs.isEmpty()) return
        musicService?.setQueue(songs, 0)
        mutatePlayer {
            copy(playbackState = playbackState.copy(
                currentPlaylistId = playlist.id,
                isPlaying         = true,
                bufferingState    = BufferingState.PREPARING
            ))
        }
        songs.firstOrNull()?.let {
            loadLyricsIfNeeded(it)
            scheduleColorExtraction(it.albumArtUri, it.id)
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
        mutatePlayer { copy(playbackState = playbackState.copy(positionMs = positionMs)) }
    }

    fun toggleRepeat() {
        val next = when (_playerState.value.playbackState.repeatMode) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL  -> RepeatMode.ONE
            RepeatMode.ONE  -> RepeatMode.NONE
        }
        musicService?.setRepeatMode(next)
        mutatePlayer { copy(playbackState = playbackState.copy(repeatMode = next)) }
    }

    fun toggleShuffle() {
        val current = _playerState.value.playbackState.shuffleMode
        val next    = if (current == ShuffleMode.OFF) ShuffleMode.ON else ShuffleMode.OFF
        musicService?.setShuffleMode(next)
        mutatePlayer { copy(playbackState = playbackState.copy(shuffleMode = next)) }
    }

    fun setPlaybackSpeed(speed: Float) {
        musicService?.setPlaybackSpeed(speed)
        mutatePlayer { copy(playbackState = playbackState.copy(playbackSpeed = speed)) }
    }

    // ── Queue ─────────────────────────────────────────────────────────────────

    fun toggleQueueView() { mutatePlayer { copy(showQueue = !showQueue) } }

    fun playNext(song: Song) {
        val service = musicService ?: return
        val ps      = service.playbackState.value
        if (ps.queue.isEmpty()) { playSong(song); return }
        service.insertIntoQueue(song, (ps.queueIndex + 1).coerceAtMost(ps.queue.size))
        mutatePlayer {
            copy(playbackState = playbackState.copy(queue = service.playbackState.value.queue))
        }
    }

    fun addToQueue(song: Song) {
        val service = musicService ?: return
        val ps      = service.playbackState.value
        if (ps.queue.isEmpty()) { playSong(song); return }
        service.appendToQueue(song)
        mutatePlayer {
            copy(playbackState = playbackState.copy(queue = service.playbackState.value.queue))
        }
    }

    fun skipToQueueItem(index: Int) {
        val service = musicService ?: return
        val ps      = service.playbackState.value
        val song    = ps.queue.getOrNull(index) ?: return
        service.setQueue(ps.queue, index)
        mutatePlayer {
            copy(playbackState = playbackState.copy(
                queue       = ps.queue,
                queueIndex  = index,
                currentSong = song
            ))
        }
        loadLyricsIfNeeded(song)
        scheduleColorExtraction(song.albumArtUri, song.id)
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
        mutateLibrary {
            copy(searchQuery = query, filteredSongs = searchSongsUseCase(songs, query))
        }
    }

    fun setSortOrder(order: SortOrder) {
        mutateLibrary {
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

    fun toggleLyrics() { mutatePlayer { copy(showLyrics = !showLyrics) } }

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
        mutateLibrary {
            val newSongs = songs.map { if (it.id == song.id) updated else it }
            copy(
                songs         = newSongs,
                filteredSongs = searchSongsUseCase(newSongs, searchQuery)
            )
        }
        mutatePlayer {
            if (playbackState.currentSong?.id == song.id)
                copy(playbackState = playbackState.copy(currentSong = updated))
            else this
        }
    }

    fun clearError() {
        mutatePlayer { copy(playbackState = playbackState.copy(error = null)) }
    }

    // ── Color extraction (FIX B-9 — AndroidX Palette) ────────────────────────

    fun scheduleColorExtraction(artUri: Uri?, songId: Long) {
        if (songId == lastColorSongId) return
        lastColorSongId = songId

        colorJob?.cancel()
        colorJob = viewModelScope.launch(Dispatchers.IO) {
            val bitmap = withTimeoutOrNull(2_000L) {
                artUri?.let { uri ->
                    runCatching {
                        getApplication<Application>().contentResolver
                            .openInputStream(uri)
                            ?.use { stream -> BitmapFactory.decodeStream(stream) }
                    }.getOrNull()
                }
            }

            val dominant = if (bitmap != null) dominantColor(bitmap) else DEFAULT_BG
            val contrast = contrastColor(dominant)

            withContext(Dispatchers.Main) {
                mutatePlayer { copy(dominantColor = dominant, accentTextColor = contrast) }
                musicService?.updateAlbumArt(bitmap, dominant)
            }
        }
    }

    fun extractAndApplyColor(artUri: Uri?, songId: Long) =
        scheduleColorExtraction(artUri, songId)

    private fun dominantColor(bmp: Bitmap): Int {
        val palette = Palette.from(bmp).generate()
        val swatch  = palette.darkVibrantSwatch
            ?: palette.darkMutedSwatch
            ?: palette.vibrantSwatch
            ?: palette.mutedSwatch
            ?: return DEFAULT_BG

        val r = (Color.red(swatch.rgb)   * 0.70f).toInt().coerceIn(0, 255)
        val g = (Color.green(swatch.rgb) * 0.70f).toInt().coerceIn(0, 255)
        val b = (Color.blue(swatch.rgb)  * 0.70f).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun contrastColor(bgColor: Int): Int {
        fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        val lum = 0.2126 * lin(Color.red(bgColor)   / 255.0) +
                0.7152 * lin(Color.green(bgColor)  / 255.0) +
                0.0722 * lin(Color.blue(bgColor)   / 255.0)
        return if (lum > 0.179) "#1A1A1A".toColorInt() else Color.WHITE
    }

    // ── Position tracking (FIX B-13 — skip when paused) ──────────────────────

    private fun startPositionTracking() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (true) {
                delay(500)
                val service = musicService ?: continue
                // FIX B-13: skip expensive work when paused
                if (!service.playbackState.value.isPlaying) continue

                val pos      = service.currentPosition()
                val lib      = _libraryState.value
                val ps       = _playerState.value
                val song     = ps.playbackState.currentSong
                // Prefer in-memory lyric doc (loaded lazily) over the one in
                // libraryState, which may not yet have been merged back.
                val lyricDoc = song?.lyricDocument
                    ?: lib.songs.find { it.id == song?.id }?.lyricDocument
                val lyricIdx = lyricDoc?.let { findCurrentLyricLine(it, pos) } ?: -1

                // Phase 7.2: ONLY mutate PlayerState here — libraryState is
                // never touched by the position ticker. This is the core win:
                // NowPlayingScreen recomposes; LibraryScreen does not.
                mutatePlayer {
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
        mutatePlayer { copy(playbackState = ps) }
        val song = ps.currentSong ?: return
        if (song.id != lastColorSongId) {
            scheduleColorExtraction(song.albumArtUri, song.id)
        }
        loadLyricsIfNeeded(song)
    }

    /**
     * Mutate [_libraryState] atomically.
     * Only call from the main thread (viewModelScope default dispatcher).
     */
    private fun mutateLibrary(block: LibraryState.() -> LibraryState) {
        _libraryState.value = _libraryState.value.block()
    }

    /**
     * Mutate [_playerState] atomically.
     * Only call from the main thread (viewModelScope default dispatcher).
     */
    private fun mutatePlayer(block: PlayerState.() -> PlayerState) {
        _playerState.value = _playerState.value.block()
    }

    override fun onCleared() {
        super.onCleared()
        positionJob?.cancel()
        playlistObserverJob?.cancel()
        favoriteObserverJob?.cancel()
        songObserverJob?.cancel()
        scanJob?.cancel()
        colorJob?.cancel()
        getApplication<Application>().unbindService(serviceConnection)
    }

    companion object {
        private val DEFAULT_BG = "#0D0D0D".toColorInt()
    }
}