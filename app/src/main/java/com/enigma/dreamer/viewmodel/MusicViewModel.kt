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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.collections.sort


class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SongRepository(application)

    // ── Use Cases ─────────────────────────────────────────────────────────────
    private val getSongsUseCase = GetSongsUseCase(repo)
    private val getPlaylistsUseCase = GetPlaylistsUseCase(repo)
    private val searchSongsUseCase = SearchSongsUseCase()
    private val sortSongsUseCase = SortSongsUseCase(repo)
    private val toggleFavoriteUseCase = ToggleFavoriteUseCase(repo)
    private val observeFavoritesUseCase = ObserveFavoritesUseCase(repo)
    private val createPlaylistUseCase = CreatePlaylistUseCase(repo)
    private val addSongToPlaylistUseCase = AddSongToPlaylistUseCase(repo)
    private val removeSongFromPlaylistUseCase = RemoveSongFromPlaylistUseCase(repo)
    private val deletePlaylistUseCase = DeletePlaylistUseCase(repo)
    private val renamePlaylistUseCase = RenamePlaylistUseCase(repo)

    // ── State ─────────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow<MusicUiState>(MusicUiState.Loading)
    val uiState: StateFlow<MusicUiState> = _uiState.asStateFlow()

    private val readyState: MusicUiState.Ready?
        get() = _uiState.value as? MusicUiState.Ready

    // ── Service binding ───────────────────────────────────────────────────────

    private var musicService: MusicService? = null
    private var positionJob: Job? = null
    private var playlistObserverJob: Job? = null
    private var favoriteObserverJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val b = binder as MusicService.MusicBinder
            musicService = b.service
            viewModelScope.launch {
                b.service.playbackState.collect { ps -> updatePlayback(ps) }
            }
            startPositionTracking()
            // Try to restore last session once service is bound and songs are loaded
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

    private fun loadAll() {
        viewModelScope.launch {
            _uiState.value = MusicUiState.Loading
            try {
                val songs     = getSongsUseCase()
                val playlists = getPlaylistsUseCase.loadAll()
                _uiState.value = MusicUiState.Ready(
                    songs         = songs,
                    playlists     = playlists,
                    filteredSongs = songs
                )
                observePlaylists()
                observeFavorites()
                // Session restore (service may already be connected)
                musicService?.tryRestoreSession(songs)
            } catch (e: Exception) {
                _uiState.value = MusicUiState.Error(e.message ?: "Failed to load songs")
            }
        }
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

    // ── Playback controls ─────────────────────────────────────────────────────

    fun playSong(song: Song, queue: List<Song> = readyState?.songs ?: listOf(song)) {
        val index = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        musicService?.setQueue(queue, index) ?: return
        // Mark as playing optimistically (onPrepared in service will confirm)
        mutateReady { copy(playbackState = playbackState.copy(
            isPlaying = true, bufferingState = BufferingState.PREPARING
        ))}
        extractAndApplyColor(song.albumArtUri)
    }

    fun playPlaylist(playlist: Playlist) {
        val state = readyState ?: return
        val songs = playlist.songIds.mapNotNull { id -> state.songs.find { it.id == id } }
        if (songs.isEmpty()) return
        val queue = if (currentShuffleMode() == ShuffleMode.ON) songs.shuffled() else songs
        musicService?.setQueue(queue, 0)
        mutateReady { copy(playbackState = playbackState.copy(
            currentPlaylistId = playlist.id,
            isPlaying         = true,
            bufferingState    = BufferingState.PREPARING
        ))}
        songs.firstOrNull()?.albumArtUri?.let { extractAndApplyColor(it) }
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

    // ── Queue management ──────────────────────────────────────────────────────

    fun toggleQueueView() {
        mutateReady { copy(showQueue = !showQueue) }
    }

    /** Insert [song] immediately after the current track in the queue. */
    fun playNext(song: Song) {
        val service = musicService ?: return
        val ps      = service.playbackState.value
        if (ps.queue.isEmpty()) { playSong(song); return }
        val insertAt = (ps.queueIndex + 1).coerceAtMost(ps.queue.size)
        val newQueue = ps.queue.toMutableList().also { it.add(insertAt, song) }
        service.setQueue(newQueue, ps.queueIndex)    // index unchanged — current song stays
        mutateReady {
            copy(playbackState = playbackState.copy(queue = newQueue))
        }
    }

    /** Append [song] to the end of the current queue. */
    fun addToQueue(song: Song) {
        val service = musicService ?: return
        val ps      = service.playbackState.value
        if (ps.queue.isEmpty()) { playSong(song); return }
        val newQueue = ps.queue + song
        service.setQueue(newQueue, ps.queueIndex)
        mutateReady { copy(playbackState = playbackState.copy(queue = newQueue)) }
    }

    fun skipToQueueItem(index: Int) {
        val service = musicService ?: return
        val ps      = service.playbackState.value
        val song    = ps.queue.getOrNull(index) ?: return
        service.setQueue(ps.queue, index)
        mutateReady { copy(playbackState = playbackState.copy(
            queue = ps.queue, queueIndex = index, currentSong = song
        ))}
        extractAndApplyColor(song.albumArtUri)
    }

    // ── Sleep timer ───────────────────────────────────────────────────────────

    fun startSleepTimer(delayMinutes: Int) {
        musicService?.startSleepTimer(delayMinutes * 60_000L)
    }

    fun cancelSleepTimer() {
        musicService?.cancelSleepTimer()
    }

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

    private fun applySearch(songs: List<Song>, query: String): List<Song> =
        searchSongsUseCase(songs, query)

    // ── Playlists ─────────────────────────────────────────────────────────────

    fun createPlaylist(name: String) {
        viewModelScope.launch { createPlaylistUseCase(name) }
    }

    fun addSongToPlaylist(song: Song, playlistId: Long) {
        viewModelScope.launch { addSongToPlaylistUseCase(song.id, playlistId) }
    }

    fun removeSongFromPlaylist(song: Song, playlistId: Long) {
        viewModelScope.launch { removeSongFromPlaylistUseCase(song.id, playlistId) }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch { deletePlaylistUseCase(playlistId) }
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        viewModelScope.launch { renamePlaylistUseCase(playlistId, newName) }
    }

    // ── Lyrics ────────────────────────────────────────────────────────────────

    fun toggleLyrics() {
        mutateReady { copy(showLyrics = !showLyrics) }
    }

    fun embedLyrics(song: Song, lyricText: String, format: LyricFormat) {
        viewModelScope.launch {
            try {
                val doc         = LyricParser.parse(lyricText, format)
                val updatedSong = song.copy(lyricDocument = doc)
                mutateReady { copy(songs = songs.map { if (it.id == song.id) updatedSong else it }) }
            } catch (e: Exception) {
                mutateReady { copy(playbackState = playbackState.copy(
                    error = "Lyric parse failed: ${e.message}"
                ))}
            }
        }
    }

    fun clearError() {
        mutateReady { copy(playbackState = playbackState.copy(error = null)) }
    }

    // ── Dynamic album-art colour ──────────────────────────────────────────────

    fun extractAndApplyColor(artUri: Uri?) {
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
                bitmap?.let { musicService?.updateAlbumArt(it) }
            }
        }
    }

    private fun dominantColor(bmp: android.graphics.Bitmap): Int {
        val stepX = bmp.width  / 12f
        val stepY = bmp.height / 12f
        val hsv   = FloatArray(3)
        val samples = (0..11).flatMap { row ->
            (0..11).map { col -> bmp.getPixel((col * stepX).toInt(), (row * stepY).toInt()) }
        }
        val vibrant = samples.sortedByDescending {
            Color.colorToHSV(it, hsv); hsv[1] * hsv[2]
        }.take(20)
        val r = (vibrant.map { Color.red(it)   }.average() * 0.7).toInt()
        val g = (vibrant.map { Color.green(it) }.average() * 0.7).toInt()
        val b = (vibrant.map { Color.blue(it)  }.average() * 0.7).toInt()
        return Color.rgb(r, g, b)
    }

    private fun contrastColor(bgColor: Int): Int {
        fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        val lum = 0.2126 * lin(Color.red(bgColor) / 255.0) +
                0.7152 * lin(Color.green(bgColor) / 255.0) +
                0.0722 * lin(Color.blue(bgColor)  / 255.0)
        return if (lum > 0.179) Color.parseColor("#1A1A1A") else Color.WHITE
    }

    // ── Position tracking ─────────────────────────────────────────────────────

    private fun startPositionTracking() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (true) {
                delay(500)
                val service = musicService ?: continue
                val pos     = service.currentPosition()
                val state   = readyState ?: continue
                val song    = state.playbackState.currentSong
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updatePlayback(ps: PlaybackState) {
        mutateReady { copy(playbackState = ps) }
        ps.currentSong?.albumArtUri?.let { extractAndApplyColor(it) }
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
        getApplication<Application>().unbindService(serviceConnection)
    }

    companion object {
        private val DEFAULT_BG = Color.parseColor("#0D0D0D")
    }
}
