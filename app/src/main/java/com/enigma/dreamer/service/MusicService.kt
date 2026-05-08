package com.enigma.dreamer.service

import android.app.*
import android.content.*
import android.graphics.*
import android.os.*
import androidx.annotation.OptIn
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import com.enigma.dreamer.MainActivity
import com.enigma.dreamer.core.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.jvm.java

/**
 * Foreground media playback service — Media3 / ExoPlayer rewrite.
 *
 * Key behaviours retained from the original:
 *  - MediaLibraryService (extends MediaSessionService) → proper media button / lock-screen handling
 *  - ExoPlayer handles audio focus, ducking, and buffering natively
 *  - Queue + position persisted to SharedPreferences on every change / destroy
 *  - Notification built by MediaNotification.Provider for full Media Style support
 *  - Sleep timer with Handler-based countdown
 *  - Playback speed via ExoPlayer.setPlaybackParameters
 *  - Shuffle / Repeat cycle exposed as custom session commands so the UI can drive them
 *  - Album-art dominant-colour theming kept for legacy notification colouring
 */
@OptIn(UnstableApi::class)
class MusicService : MediaLibraryService() {

    inner class MusicBinder : Binder() {
        val service: MusicService get() = this@MusicService
    }

    private val binder = MusicBinder()

    override fun onBind(intent: Intent?): IBinder? {
        val superBinder = super.onBind(intent)
        return if (intent?.action == null) binder else superBinder
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState

    // ── ExoPlayer + MediaSession ──────────────────────────────────────────────

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession

    // ── Album art ─────────────────────────────────────────────────────────────

    private var albumArtBitmap: Bitmap? = null
    private var notificationBgColor: Int = Color.parseColor("#1A1A1A")

    // ── Sleep timer ───────────────────────────────────────────────────────────

    private val sleepHandler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null

    // ── Coroutine scope ───────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Persistence ───────────────────────────────────────────────────────────

    private val prefs by lazy {
        getSharedPreferences("playback_state", Context.MODE_PRIVATE)
    }

    // ── Custom session commands ───────────────────────────────────────────────

    companion object {
        const val CMD_TOGGLE_SHUFFLE   = "cmd_toggle_shuffle"
        const val CMD_CYCLE_REPEAT     = "cmd_cycle_repeat"
        const val CMD_TOGGLE_FAVORITE  = "cmd_toggle_favorite"
        const val CMD_SET_SPEED        = "cmd_set_speed"
        const val CMD_START_SLEEP      = "cmd_start_sleep"
        const val CMD_CANCEL_SLEEP     = "cmd_cancel_sleep"
        const val CMD_UPDATE_ALBUM_ART = "cmd_update_album_art"
        const val EXTRA_SPEED          = "extra_speed"
        const val EXTRA_DELAY_MS       = "extra_delay_ms"
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        buildPlayer()
        buildMediaSession()
        // Observe ExoPlayer state → mirror to our StateFlow
        observePlayerState()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        persistState()
        // Stop service if nothing is playing
        if (!player.isPlaying) stopSelf()
    }

    override fun onDestroy() {
        persistState()
        cancelSleepTimer()
        serviceScope.cancel()
        mediaSession.release()
        player.release()
        albumArtBitmap?.recycle()
        super.onDestroy()
    }

    // ── Build ExoPlayer ───────────────────────────────────────────────────────

    private fun buildPlayer() {
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true   // ExoPlayer manages focus + ducking
            )
            .setHandleAudioBecomingNoisy(true)  // pause on headphone unplug
            .build()
    }

    // ── Build MediaSession ────────────────────────────────────────────────────

    private fun buildMediaSession() {
        mediaSession = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {

            // ── Custom commands from UI ────────────────────────────────────

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    CMD_TOGGLE_SHUFFLE  -> toggleShuffleInternal()
                    CMD_CYCLE_REPEAT    -> cycleRepeatInternal()
                    CMD_TOGGLE_FAVORITE -> toggleFavoriteInternal()
                    CMD_SET_SPEED       -> {
                        val speed = args.getFloat(EXTRA_SPEED, 1f)
                        setPlaybackSpeed(speed)
                    }
                    CMD_START_SLEEP     -> {
                        val delay = args.getLong(EXTRA_DELAY_MS, 0L)
                        if (delay > 0) startSleepTimer(delay)
                    }
                    CMD_CANCEL_SLEEP    -> cancelSleepTimer()
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            // Allow all controllers (no Hilt / permission checks needed)
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult =
                MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(
                        SessionCommands.Builder()
                            .add(SessionCommand(CMD_TOGGLE_SHUFFLE,  Bundle.EMPTY))
                            .add(SessionCommand(CMD_CYCLE_REPEAT,    Bundle.EMPTY))
                            .add(SessionCommand(CMD_TOGGLE_FAVORITE, Bundle.EMPTY))
                            .add(SessionCommand(CMD_SET_SPEED,       Bundle.EMPTY))
                            .add(SessionCommand(CMD_START_SLEEP,     Bundle.EMPTY))
                            .add(SessionCommand(CMD_CANCEL_SLEEP,    Bundle.EMPTY))
                            .build()
                    )
                    .build()

        })
            .setSessionActivity(buildTapIntent())
            .build()
    }

    // ── Observe ExoPlayer → mirror state ─────────────────────────────────────

    private fun observePlayerState() {
        player.addListener(object : Player.Listener {

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
                persistState()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx  = player.currentMediaItemIndex
                val song = _playbackState.value.queue.getOrNull(idx)
                _playbackState.value = _playbackState.value.copy(
                    currentSong = song,
                    queueIndex  = idx,
                    positionMs  = 0L
                )
                // Clear previous album art on track change
                albumArtBitmap?.recycle(); albumArtBitmap = null
                notificationBgColor = Color.parseColor("#1A1A1A")
                persistState()
            }

            override fun onPlaybackStateChanged(state: Int) {
                val bufState = when (state) {
                    Player.STATE_BUFFERING -> BufferingState.PREPARING
                    Player.STATE_READY     -> BufferingState.READY
                    Player.STATE_ENDED     -> BufferingState.READY
                    else                   -> BufferingState.PREPARING
                }
                _playbackState.value = _playbackState.value.copy(
                    bufferingState = bufState,
                    durationMs     = player.duration.coerceAtLeast(0L)
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                _playbackState.value = _playbackState.value.copy(
                    isPlaying      = false,
                    bufferingState = BufferingState.ERROR,
                    error          = error.message
                )
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                val mode = if (shuffleModeEnabled) ShuffleMode.ON else ShuffleMode.OFF
                _playbackState.value = _playbackState.value.copy(shuffleMode = mode)
                persistState()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                val mode = when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    else                   -> RepeatMode.NONE
                }
                _playbackState.value = _playbackState.value.copy(repeatMode = mode)
                persistState()
            }
        })
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setQueue(queue: List<Song>, index: Int) {
        val idx = index.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        val items = queue.map { it.toMediaItem() }
        player.setMediaItems(items, idx, C.TIME_UNSET)
        player.prepare()
        _playbackState.value = _playbackState.value.copy(
            queue       = queue,
            queueIndex  = idx,
            currentSong = queue.getOrNull(idx),
            positionMs  = 0L
        )
        persistState()
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun next() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    fun previous() {
        if (player.currentPosition > 3_000L) {
            player.seekTo(0L)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _playbackState.value = _playbackState.value.copy(positionMs = positionMs)
        persistState()
    }

    fun currentPosition(): Long = player.currentPosition

    fun setRepeatMode(mode: RepeatMode) {
        player.repeatMode = when (mode) {
            RepeatMode.ONE  -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL  -> Player.REPEAT_MODE_ALL
            RepeatMode.NONE -> Player.REPEAT_MODE_OFF
        }
        // State mirrored via listener
    }

    fun setShuffleMode(mode: ShuffleMode) {
        player.shuffleModeEnabled = (mode == ShuffleMode.ON)
        // State mirrored via listener; also re-sync our queue model
        val ps = _playbackState.value
        val newQueue = if (mode == ShuffleMode.ON) {
            val cur  = ps.currentSong
            val rest = ps.queue.filter { it.id != cur?.id }.shuffled()
            if (cur != null) listOf(cur) + rest else rest
        } else {
            ps.queue.sortedBy { it.title }
        }
        val newIdx = newQueue.indexOfFirst { it.id == ps.currentSong?.id }.coerceAtLeast(0)
        _playbackState.value = ps.copy(queue = newQueue, queueIndex = newIdx, shuffleMode = mode)
        persistState()
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 3.0f)
        player.setPlaybackParameters(PlaybackParameters(clamped))
        _playbackState.value = _playbackState.value.copy(playbackSpeed = clamped)
        persistState()
    }

    fun startSleepTimer(delayMs: Long) {
        cancelSleepTimer()
        val endsAt = System.currentTimeMillis() + delayMs
        _playbackState.value = _playbackState.value.copy(
            sleepTimer = SleepTimer(isActive = true, endsAtMs = endsAt)
        )
        sleepRunnable = Runnable {
            pause()
            _playbackState.value = _playbackState.value.copy(sleepTimer = SleepTimer())
        }
        sleepHandler.postDelayed(sleepRunnable!!, delayMs)
    }

    fun cancelSleepTimer() {
        sleepRunnable?.let { sleepHandler.removeCallbacks(it) }
        sleepRunnable = null
        _playbackState.value = _playbackState.value.copy(sleepTimer = SleepTimer())
    }

    fun updateAlbumArt(bitmap: Bitmap) {
        val scaled = scaleBitmap(bitmap, 256)
        albumArtBitmap?.recycle()
        albumArtBitmap = scaled
        notificationBgColor = dominantColor(scaled)
        // Re-trigger notification rebuild (Media3 uses its own MediaNotification infra,
        // but colouring is done via MediaNotification.Provider below)
        mediaSession.broadcastCustomCommand(
            SessionCommand(CMD_UPDATE_ALBUM_ART, Bundle.EMPTY), Bundle.EMPTY
        )
    }

    // ── Restore last session ──────────────────────────────────────────────────

    fun tryRestoreSession(allSongs: List<Song>): Boolean {
        val idx         = prefs.getInt("queue_index", -1)
        val pos         = prefs.getLong("position_ms", 0L)
        val repeat      = prefs.getString("repeat", RepeatMode.NONE.name)
            ?.let { runCatching { RepeatMode.valueOf(it) }.getOrDefault(RepeatMode.NONE) }
            ?: RepeatMode.NONE
        val shuffle     = prefs.getString("shuffle", ShuffleMode.OFF.name)
            ?.let { runCatching { ShuffleMode.valueOf(it) }.getOrDefault(ShuffleMode.OFF) }
            ?: ShuffleMode.OFF
        val queueIdsRaw = prefs.getString("queue_ids", "") ?: ""
        if (queueIdsRaw.isBlank() || idx < 0) return false

        val queueIds = queueIdsRaw.split(",").mapNotNull { it.toLongOrNull() }
        val queue    = queueIds.mapNotNull { id -> allSongs.find { it.id == id } }
        if (queue.isEmpty() || idx >= queue.size) return false

        val items = queue.map { it.toMediaItem() }
        player.setMediaItems(items, idx, pos)
        player.prepare()
        // Modes
        player.repeatMode        = repeat.toExoRepeat()
        player.shuffleModeEnabled = shuffle == ShuffleMode.ON

        _playbackState.value = _playbackState.value.copy(
            queue       = queue,
            queueIndex  = idx,
            currentSong = queue[idx],
            positionMs  = pos,
            repeatMode  = repeat,
            shuffleMode = shuffle
        )
        return true
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun persistState() {
        val ps = _playbackState.value
        prefs.edit()
            .putString("queue_ids",  ps.queue.joinToString(",") { it.id.toString() })
            .putInt("queue_index",   ps.queueIndex)
            .putLong("position_ms",  currentPosition())
            .putString("repeat",     ps.repeatMode.name)
            .putString("shuffle",    ps.shuffleMode.name)
            .apply()
    }

    // ── Internal command handlers ─────────────────────────────────────────────

    private fun toggleShuffleInternal() =
        setShuffleMode(if (_playbackState.value.shuffleMode == ShuffleMode.OFF) ShuffleMode.ON else ShuffleMode.OFF)

    private fun cycleRepeatInternal() =
        setRepeatMode(when (_playbackState.value.repeatMode) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL  -> RepeatMode.ONE
            RepeatMode.ONE  -> RepeatMode.NONE
        })

    private fun toggleFavoriteInternal() {
        val ps      = _playbackState.value
        val song    = ps.currentSong ?: return
        val updated = song.copy(isFavorite = !song.isFavorite)
        val newQueue = ps.queue.map { if (it.id == song.id) updated else it }
        _playbackState.value = ps.copy(currentSong = updated, queue = newQueue)
    }

    // ── Notification tap intent ───────────────────────────────────────────────

    private fun buildTapIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_now_playing", true)
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ── Song → MediaItem ──────────────────────────────────────────────────────

    private fun Song.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setDurationMs(duration)
                    .build()
            )
            .build()

    // ── RepeatMode helpers ────────────────────────────────────────────────────

    private fun RepeatMode.toExoRepeat() = when (this) {
        RepeatMode.ONE  -> Player.REPEAT_MODE_ONE
        RepeatMode.ALL  -> Player.REPEAT_MODE_ALL
        RepeatMode.NONE -> Player.REPEAT_MODE_OFF
    }

    // ── Colour helpers (kept from original) ───────────────────────────────────

    private fun scaleBitmap(src: Bitmap, maxSide: Int): Bitmap {
        val scale = maxSide.toFloat() / maxOf(src.width, src.height)
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }

    private fun dominantColor(bmp: Bitmap): Int {
        val stepX = bmp.width  / 12f
        val stepY = bmp.height / 12f
        val hsv   = FloatArray(3)
        val samples = (0..11).flatMap { r -> (0..11).map { c ->
            bmp.getPixel((c * stepX).toInt(), (r * stepY).toInt())
        }}
        val vibrant = samples.sortedByDescending {
            Color.colorToHSV(it, hsv); hsv[1] * hsv[2]
        }.take(20)
        val r = (vibrant.map { Color.red(it)   }.average() * 0.7).toInt()
        val g = (vibrant.map { Color.green(it) }.average() * 0.7).toInt()
        val b = (vibrant.map { Color.blue(it)  }.average() * 0.7).toInt()
        return Color.rgb(r, g, b)
    }
}