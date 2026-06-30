package com.enigma.dreamer.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Central ViewModel for Dreamer — Phase 9 refactor.
 *
 * Responsibilities retained here (orchestration only):
 *   - Service binding lifecycle
 *   - Two-phase load (cache → scan)
 *   - Search / sort / playlist / favorite actions
 *   - Mutating [libraryState] and [playerState]
 *   - Forwarding commands to the three extracted controllers
 *
 * Responsibilities moved out:
 *   - [PlaybackController]  — all MusicService invocations (9.1)
 *   - [ColorExtractor]      — album-art color extraction (9.2)
 *   - [LyricController]     — lazy lyric loading + line tracking (9.3)
 *
 * State split (Phase 7.2) and reactive observers (Phase 7.3) are unchanged.
 */
class MusicViewModel(application: Application) : AndroidViewModel(application) {

    // ── Repository + use-cases ────────────────────────────────────────────────

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

    // ── Extracted controllers (9.1, 9.2, 9.3) ────────────────────────────────

    /** Wraps every MusicService call; holds the nullable service reference. */
    val playback = PlaybackController()

    /** Derives dominant + contrast colors from album art bitmaps. */
    private val colorExtractor = ColorExtractor(application)

    private val pitchController = PitchController()

    /**
     * Lazy lyric loader + line tracker.
     * Implements [LyricController.Callbacks] anonymously so the controller
     * can call back into ViewModel state mutation without a circular reference.
     */
    private val lyricController = LyricController(
        repository = repo,
        scope      = viewModelScope,
        callbacks  = object : LyricController.Callbacks {
            override fun onLyricsLoaded(song: Song, doc: LyricDocument) {
                val updated = song.copy(lyricDocument = doc)
                mutateLibrary {
                    val newSongs = songs.map { if (it.id == song.id) updated else it }
                    copy(songs = newSongs, filteredSongs = searchSongsUseCase(newSongs, searchQuery))
                }
                mutatePlayer {
                    if (playbackState.currentSong?.id == song.id)
                        copy(playbackState = playbackState.copy(currentSong = updated))
                    else this
                }
            }
        }
    )

    // ── Split state ───────────────────────────────────────────────────────────

    private val _uiState      = MutableStateFlow<MusicUiState>(MusicUiState.Loading)
    val uiState: StateFlow<MusicUiState> = _uiState.asStateFlow()

    private val _libraryState = MutableStateFlow(LibraryState())
    val libraryState: StateFlow<LibraryState> = _libraryState.asStateFlow()

    private val _playerState  = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _scanProgress = MutableStateFlow<Int?>(null)
    val scanProgress: StateFlow<Int?> = _scanProgress.asStateFlow()

    // ── Color-extraction dedup guard ──────────────────────────────────────────

    @Volatile private var lastColorSongId: Long? = null
    private var colorJob: Job? = null

    // ── Observer jobs ─────────────────────────────────────────────────────────

    private var positionJob:         Job? = null
    private var playlistObserverJob: Job? = null
    private var favoriteObserverJob: Job? = null
    private var songObserverJob:     Job? = null
    private var mediaStoreJob:       Job? = null   // 9.4
    private var scanJob:             Job? = null

    // ── Service binding ───────────────────────────────────────────────────────

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val b = binder as MusicService.MusicBinder
            playback.service = b.service
            viewModelScope.launch {
                b.service.playbackState.collect { ps -> onPlaybackStateChanged(ps) }
            }
            startPositionTracking()
            syncEqStateFromService()   // read initial EQ state once service is bound
            // FIX B-3: tryRestoreSession is NOT called here — loadAll() calls it
            // after songs are confirmed available, avoiding the cold-start race.
        }

        override fun onServiceDisconnected(name: ComponentName) {
            playback.service = null
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
                    startObservers()
                    // FIX B-3
                    launch { playback.tryRestoreSession(cachedSongs) }
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
                        startObservers()
                        launch { playback.tryRestoreSession(freshSongs) }
                    } else {
                        mutateLibrary {
                            val lyricCache = songs
                                .filter { it.lyricDocument != null }
                                .associate { it.id to it.lyricDocument!! }
                            val merged = freshSongs.map { song ->
                                if (song.id in lyricCache)
                                    song.copy(lyricDocument = lyricCache[song.id])
                                else song
                            }
                            copy(songs = merged, filteredSongs = searchSongsUseCase(merged, searchQuery))
                        }
                    }
                }
            } catch (e: Exception) {
                _scanProgress.value = null
                if (firstLaunch) {
                    withContext(Dispatchers.Main) {
                        _uiState.value = MusicUiState.Error(e.message ?: "Could not scan music library")
                    }
                }
            }
        }
    }

    fun rescan() { launchBackgroundScan(firstLaunch = false) }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun startObservers() {
        observeSongsFromDb()
        observePlaylists()
        observeFavorites()
        observeMediaStore()   // 9.4
    }

    /** Phase 7.3 — reactive complement to the manual scan path. */
    private fun observeSongsFromDb() {
        songObserverJob?.cancel()
        songObserverJob = viewModelScope.launch {
            repo.observeSongs().drop(1).collect { dbSongs ->
                mutateLibrary {
                    val lyricCache = songs.filter { it.lyricDocument != null }
                        .associate { it.id to it.lyricDocument!! }
                    val merged = dbSongs.map { song ->
                        song.copy(lyricDocument = lyricCache[song.id], isFavorite = song.isFavorite)
                    }
                    copy(songs = merged, filteredSongs = searchSongsUseCase(merged, searchQuery))
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
                    copy(songs = updated, filteredSongs = searchSongsUseCase(updated, searchQuery))
                }
                // NEW: keep the currently-playing song's favourite flag in sync too
                mutatePlayer {
                    val cur = playbackState.currentSong
                    if (cur != null) {
                        copy(playbackState = playbackState.copy(
                            currentSong = cur.copy(isFavorite = cur.id in favIds)
                        ))
                    } else this
                }
            }
        }
    }

    /**
     * 9.4 — ContentObserver-based MediaStore change detection.
     *
     * [SongRepository.observeMediaStoreChanges] emits whenever the OS notifies
     * us of an audio MediaStore mutation (new download, deleted file, tag edit).
     * We debounce with a 2-second window to avoid redundant scans on burst writes.
     */
    @OptIn(FlowPreview::class)
    private fun observeMediaStore() {
        mediaStoreJob?.cancel()
        mediaStoreJob = viewModelScope.launch {
            repo.observeMediaStoreChanges()
                .debounce(2_000L)
                .collect {
                    // Only rescan if we're already in the Ready state so we
                    // don't stomp over a first-launch scan that's still running.
                    if (_uiState.value is MusicUiState.Ready) {
                        launchBackgroundScan(firstLaunch = false)
                    }
                }
        }
    }

    // ── Lyric loading (delegates to LyricController) ──────────────────────────

    fun loadLyricsIfNeeded(song: Song) = lyricController.loadIfNeeded(song)

    // ── Playback controls ─────────────────────────────────────────────────────

    fun playSong(song: Song, queue: List<Song> = _libraryState.value.songs) {
        val index = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        if (!playback.setQueue(queue, index)) return
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
        playback.setQueue(songs, 0)
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
        val service = playback.service ?: return
        if (service.playbackState.value.isPlaying) playback.pause() else playback.play()
    }

    fun next()     { playback.next() }
    fun previous() { playback.previous() }

    fun seekTo(positionMs: Long) {
        playback.seekTo(positionMs)
        mutatePlayer { copy(playbackState = playbackState.copy(positionMs = positionMs)) }
    }

    fun toggleRepeat() {
        val next = when (_playerState.value.playbackState.repeatMode) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL  -> RepeatMode.ONE
            RepeatMode.ONE  -> RepeatMode.NONE
        }
        playback.setRepeatMode(next)
        mutatePlayer { copy(playbackState = playbackState.copy(repeatMode = next)) }
    }

    fun toggleShuffle() {
        val current = _playerState.value.playbackState.shuffleMode
        val next    = if (current == ShuffleMode.OFF) ShuffleMode.ON else ShuffleMode.OFF
        playback.setShuffleMode(next)
        mutatePlayer { copy(playbackState = playbackState.copy(shuffleMode = next)) }
    }

    fun setPlaybackSpeed(speed: Float) {
        playback.setPlaybackSpeed(speed)
        mutatePlayer { copy(playbackState = playbackState.copy(playbackSpeed = speed)) }
    }

    fun setPitch(semitones: Int) {
        pitchController.set(semitones, playback.service)
        mutatePlayer { copy(pitchSemitones = semitones) }
    }

    // ── Queue ─────────────────────────────────────────────────────────────────

    fun toggleQueueView() { mutatePlayer { copy(showQueue = !showQueue) } }

    fun playNext(song: Song) {
        val service = playback.service ?: return
        val ps      = service.playbackState.value
        if (ps.queue.isEmpty()) { playSong(song); return }
        playback.insertIntoQueue(song, (ps.queueIndex + 1).coerceAtMost(ps.queue.size))
        mutatePlayer {
            copy(playbackState = playbackState.copy(queue = service.playbackState.value.queue))
        }
    }

    fun addToQueue(song: Song) {
        val service = playback.service ?: return
        val ps      = service.playbackState.value
        if (ps.queue.isEmpty()) { playSong(song); return }
        playback.appendToQueue(song)
        mutatePlayer {
            copy(playbackState = playbackState.copy(queue = service.playbackState.value.queue))
        }
    }

    fun skipToQueueItem(index: Int) {
        val service = playback.service ?: return
        val ps      = service.playbackState.value
        val song    = ps.queue.getOrNull(index) ?: return
        playback.setQueue(ps.queue, index)
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

    fun reorderQueue(newQueue: List<Song>) {
        playback.reorderQueue(newQueue)
        mutatePlayer {
            val newIndex = newQueue.indexOfFirst { it.id == playbackState.currentSong?.id }
                .coerceAtLeast(0)
            copy(playbackState = playbackState.copy(queue = newQueue, queueIndex = newIndex))
        }
    }

    // ── Sleep timer ───────────────────────────────────────────────────────────

    fun startSleepTimer(delayMinutes: Int) { playback.startSleepTimer(delayMinutes * 60_000L) }
    fun cancelSleepTimer()                 { playback.cancelSleepTimer() }

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

    fun reorderPlaylistSongs(playlistId: Long, newSongIds: List<Long>) {
        viewModelScope.launch { repo.reorderPlaylistSongs(playlistId, newSongIds) }
    }

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
            copy(songs = newSongs, filteredSongs = searchSongsUseCase(newSongs, searchQuery))
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

    // ── Color extraction (delegates to ColorExtractor, 9.2) ──────────────────

    fun scheduleColorExtraction(artUri: android.net.Uri?, songId: Long) {
        if (songId == lastColorSongId) return
        lastColorSongId = songId

        colorJob?.cancel()
        colorJob = viewModelScope.launch {
            val result = colorExtractor.extract(artUri)
            mutatePlayer { copy(dominantColor = result.dominantColor, accentTextColor = result.accentTextColor) }
            playback.updateAlbumArt(null, result.dominantColor)
            // Album art bitmap is passed via the color extraction path;
            // the service handles notification art internally via MediaMetadata.
        }
    }

    // ── EQ ────────────────────────────────────────────────────────────────────

    fun setEqEnabled(enabled: Boolean) {
        playback.setEqEnabled(enabled)
        mutatePlayer { copy(eqState = eqState.copy(isEnabled = enabled)) }
    }

    fun setEqPreset(preset: Short) {
        playback.setEqPreset(preset)
        mutatePlayer { copy(eqState = eqState.copy(currentPreset = preset)) }
    }

    fun setEqBassBoost(strength: Short) {
        playback.setEqBassBoost(strength)
        mutatePlayer { copy(eqState = eqState.copy(bassBoostLevel = strength)) }
    }

    private fun syncEqStateFromService() {
        val eq = playback.equalizerController ?: return
        mutatePlayer {
            copy(eqState = EqState(
                isEnabled      = eq.isEnabled,
                bassBoostLevel = eq.bassBoostLevel,
                currentPreset  = eq.currentPreset,
                presetNames    = eq.presetNames
            ))
        }
    }

    // ── Position tracking ─────────────────────────────────────────────────────

    private fun startPositionTracking() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (true) {
                delay(500)
                val service = playback.service ?: continue
                val pst      = service.playbackState.value
                // FIX B-13: skip work when paused, UNLESS sleep timer is active (N-10)
                if (!pst.isPlaying && !pst.sleepTimer.isActive) continue

                val pos     = playback.currentPosition()
                val lib     = _libraryState.value
                val ps      = _playerState.value
                val song    = ps.playbackState.currentSong
                val lyricDoc = song?.lyricDocument
                    ?: lib.songs.find { it.id == song?.id }?.lyricDocument
                val lyricIdx = lyricDoc?.let { lyricController.currentLine(it, pos) } ?: -1

                // Only mutate PlayerState — LibraryState is never touched here.
                mutatePlayer {
                    copy(playbackState = playbackState.copy(positionMs = pos), currentLyricLine = lyricIdx)
                }
            }
        }
    }

    // ── Playback state changes from service ───────────────────────────────────

    private fun onPlaybackStateChanged(ps: PlaybackState) {
        mutatePlayer { copy(playbackState = ps) }
        val song = ps.currentSong ?: return
        if (song.id != lastColorSongId) {
            scheduleColorExtraction(song.albumArtUri, song.id)
        }
        loadLyricsIfNeeded(song)
        // Sync EQ preset names on song change (device may change audio session)
        syncEqStateFromService()
    }

    // ── State mutation helpers ────────────────────────────────────────────────

    private fun mutateLibrary(block: LibraryState.() -> LibraryState) {
        _libraryState.value = _libraryState.value.block()
    }

    private fun mutatePlayer(block: PlayerState.() -> PlayerState) {
        _playerState.value = _playerState.value.block()
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        positionJob?.cancel()
        playlistObserverJob?.cancel()
        favoriteObserverJob?.cancel()
        songObserverJob?.cancel()
        mediaStoreJob?.cancel()
        scanJob?.cancel()
        colorJob?.cancel()
        getApplication<Application>().unbindService(serviceConnection)
        // 9.4: unregister ContentObserver
        repo.unregisterMediaStoreObserver()
    }
}
