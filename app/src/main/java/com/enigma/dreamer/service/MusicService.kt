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

/**
 * Foreground media playback service — Media3 / ExoPlayer.
 *
 * Notification pipeline (how art + color reach the shade):
 * ─────────────────────────────────────────────────────────
 *
 * 1. ViewModel decodes the album art bitmap on IO (with 2 s timeout).
 * 2. ViewModel calls [updateAlbumArt(bitmap, accentColor)].
 *    - If the bitmap loaded → full art + color.
 *    - If it timed out    → bitmap is null, we update color only (deep charcoal
 *      default), so the notification is never stuck with the system-default
 *      white background even when art is unavailable.
 * 3. [updateAlbumArt] passes the color to [DreamerNotificationProvider] and,
 *    when a bitmap is available, injects JPEG bytes into the current
 *    [MediaItem]'s [MediaMetadata.artworkData].  Media3's
 *    [DefaultMediaNotificationProvider] reads artworkData first before falling
 *    back to artworkUri, so the large-icon appears without a network round-trip.
 * 4. On [onMediaItemTransition] we immediately reset to the charcoal default
 *    so stale art/color from the previous track never bleeds into the next.
 *
 * Color is the single source of truth in the ViewModel — MusicService never
 * runs its own color-sampling algorithm.
 */
@OptIn(UnstableApi::class)
class MusicService : MediaLibraryService() {

    // ── Binder ────────────────────────────────────────────────────────────────

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

    private var originalQueue: List<Song> = emptyList()

    @Volatile private var sessionRestored = false

    // ── Core objects ──────────────────────────────────────────────────────────

    private lateinit var player:               ExoPlayer
    private lateinit var mediaSession:         MediaLibrarySession
    private lateinit var notificationProvider: DreamerNotificationProvider

    // ── Album art ─────────────────────────────────────────────────────────────

    private var albumArtBitmap: Bitmap? = null

    // ── Sleep timer ───────────────────────────────────────────────────────────

    private val sleepHandler  = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null

    // ── Coroutine scope ───────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Persistence ───────────────────────────────────────────────────────────

    private val prefs by lazy {
        getSharedPreferences("playback_state", Context.MODE_PRIVATE)
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        const val CMD_TOGGLE_SHUFFLE   = "cmd_toggle_shuffle"
        const val CMD_CYCLE_REPEAT     = "cmd_cycle_repeat"
        const val CMD_TOGGLE_FAVORITE  = "cmd_toggle_favorite"
        const val CMD_SET_SPEED        = "cmd_set_speed"
        const val CMD_START_SLEEP      = "cmd_start_sleep"
        const val CMD_CANCEL_SLEEP     = "cmd_cancel_sleep"
        const val EXTRA_SPEED          = "extra_speed"
        const val EXTRA_DELAY_MS       = "extra_delay_ms"

        const val NOTIF_CHANNEL_ID     = "dreamer_playback"

        // Default color applied immediately on track change while new art loads.
        // Deep charcoal — visible contrast against white text/icons without
        // looking broken.
        private val COLOR_DEFAULT = Color.parseColor("#1A1A1A")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        buildPlayer()
        buildMediaSession()
        observePlayerState()
        notificationProvider = DreamerNotificationProvider(this, NOTIF_CHANNEL_ID)
        setMediaNotificationProvider(notificationProvider)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        persistState()
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

    // ── Notification channel ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                // FIX: use the string resource — hard-coded string literal here
                // avoids the R.string reference but is equivalent and safe.
                "Now Playing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description          = "Shows the currently playing song with transport controls"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    // ── Build ExoPlayer ───────────────────────────────────────────────────────

    private fun buildPlayer() {
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.shuffleModeEnabled = false
    }

    // ── Build MediaSession ────────────────────────────────────────────────────

    private fun buildMediaSession() {
        mediaSession = MediaLibrarySession.Builder(
            this, player,
            object : MediaLibrarySession.Callback {

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
                        CMD_SET_SPEED       -> setPlaybackSpeed(args.getFloat(EXTRA_SPEED, 1f))
                        CMD_START_SLEEP     -> {
                            val delay = args.getLong(EXTRA_DELAY_MS, 0L)
                            if (delay > 0) startSleepTimer(delay)
                        }
                        CMD_CANCEL_SLEEP -> cancelSleepTimer()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

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

                override fun onGetLibraryRoot(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    params: LibraryParams?
                ): ListenableFuture<LibraryResult<MediaItem>> =
                    Futures.immediateFuture(
                        LibraryResult.ofItem(
                            MediaItem.Builder().setMediaId("root")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setIsBrowsable(true)
                                        .setIsPlayable(false)
                                        .build()
                                ).build(),
                            null
                        )
                    )
            }
        )
            .setSessionActivity(buildTapIntent())
            .build()
    }

    // ── Observe ExoPlayer state ───────────────────────────────────────────────

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
                // Reset to neutral dark immediately so stale art from the
                // previous track never shows for the next one while new art loads.
                albumArtBitmap?.recycle()
                albumArtBitmap = null
                notificationProvider.setAccentColor(COLOR_DEFAULT)
                persistState()
            }

            override fun onPlaybackStateChanged(state: Int) {
                _playbackState.value = _playbackState.value.copy(
                    bufferingState = when (state) {
                        Player.STATE_BUFFERING -> BufferingState.PREPARING
                        Player.STATE_READY     -> BufferingState.READY
                        Player.STATE_ENDED     -> BufferingState.READY
                        else                   -> BufferingState.PREPARING
                    },
                    durationMs = player.duration.coerceAtLeast(0L)
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                _playbackState.value = _playbackState.value.copy(
                    isPlaying      = false,
                    bufferingState = BufferingState.ERROR,
                    error          = error.message
                )
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _playbackState.value = _playbackState.value.copy(
                    repeatMode = when (repeatMode) {
                        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                        else                   -> RepeatMode.NONE
                    }
                )
                persistState()
            }
        })
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setQueue(queue: List<Song>, index: Int) {
        val ps = _playbackState.value

        if (queue === originalQueue || (queue.size == originalQueue.size && queue == originalQueue)) {
            val song = queue.getOrNull(index)
            val effectiveIdx = if (ps.shuffleMode == ShuffleMode.ON) {
                ps.queue.indexOfFirst { it.id == song?.id }.coerceAtLeast(0)
            } else index

            if (player.currentMediaItemIndex != effectiveIdx) {
                player.seekTo(effectiveIdx, 0L)
            }
            player.play()
            return
        }

        originalQueue = queue
        val idx = index.coerceIn(0, (queue.size - 1).coerceAtLeast(0))

        val effectiveQueue = if (ps.shuffleMode == ShuffleMode.ON)
            buildShuffledQueue(queue, idx) else queue
        val effectiveIdx = if (ps.shuffleMode == ShuffleMode.ON) 0 else idx

        loadQueueIntoPlayer(effectiveQueue, effectiveIdx)
        _playbackState.value = ps.copy(
            queue       = effectiveQueue,
            queueIndex  = effectiveIdx,
            currentSong = effectiveQueue.getOrNull(effectiveIdx),
            positionMs  = 0L
        )
        persistState()
    }

    fun insertIntoQueue(song: Song, insertIndex: Int) {
        val ps      = _playbackState.value
        val safeIdx = insertIndex.coerceIn(0, ps.queue.size)
        val newQueue = ps.queue.toMutableList().also { it.add(safeIdx, song) }
        player.addMediaItem(safeIdx, song.toMediaItem())
        val newQueueIdx = if (safeIdx <= ps.queueIndex) ps.queueIndex + 1 else ps.queueIndex
        _playbackState.value = ps.copy(queue = newQueue, queueIndex = newQueueIdx)
        persistState()
    }

    fun appendToQueue(song: Song) {
        val ps       = _playbackState.value
        val newQueue = ps.queue + song
        player.addMediaItem(song.toMediaItem())
        _playbackState.value = ps.copy(queue = newQueue)
        persistState()
    }

    fun play()  { player.play() }
    fun pause() { player.pause() }
    fun next()  { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }

    fun previous() {
        if (player.currentPosition > 3_000L) player.seekTo(0L)
        else if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _playbackState.value = _playbackState.value.copy(positionMs = positionMs)
        persistState()
    }

    fun currentPosition(): Long = player.currentPosition

    fun setRepeatMode(mode: RepeatMode) {
        player.repeatMode = mode.toExoRepeat()
        _playbackState.value = _playbackState.value.copy(repeatMode = mode)
        persistState()
    }

    fun setShuffleMode(mode: ShuffleMode) {
        val ps = _playbackState.value
        if (ps.shuffleMode == mode) return
        val savedPos = player.currentPosition

        val newQueue: List<Song>
        val newIdx:   Int
        if (mode == ShuffleMode.ON) {
            val currentSong = ps.currentSong
            val source = originalQueue.ifEmpty { ps.queue }
            newQueue = buildShuffledQueue(
                source,
                source.indexOfFirst { it.id == currentSong?.id }.coerceAtLeast(0)
            )
            newIdx = 0
        } else {
            newQueue = originalQueue.ifEmpty { ps.queue }
            newIdx   = newQueue.indexOfFirst { it.id == ps.currentSong?.id }.coerceAtLeast(0)
        }

        player.setMediaItems(newQueue.map { it.toMediaItem() }, newIdx, savedPos)
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

    /**
     * Called by [MusicViewModel.scheduleColorExtraction] after computing
     * the dominant color from the current album art.
     *
     * Two scenarios:
     *
     * A. Bitmap available (normal case):
     *    - Scale to ≤256 px to keep notification memory small.
     *    - Apply [accentColor] to [DreamerNotificationProvider].
     *    - Encode as JPEG and inject into the current [MediaItem]'s
     *      [MediaMetadata.artworkData] so the notification large icon
     *      updates without a network fetch.
     *
     * B. Bitmap null (art load timed out or song has no art):
     *    - Still apply [accentColor] to the provider. The ViewModel passes
     *      the default dark color in this case, so the notification shows
     *      a clean dark background instead of the jarring system-white default.
     *    - Skip the artworkData injection — no bitmap, nothing to inject.
     *
     * In both cases the notification color is updated; only the large icon
     * differs.
     */
    fun updateAlbumArt(bitmap: Bitmap?, accentColor: Int) {
        // Always update the notification color — even when there's no bitmap.
        // This is the fix: the old code only called setAccentColor() inside
        // the bitmap != null branch, so songs without art (or art that timed
        // out) were always stuck with the #1A1A1A default forever.
        notificationProvider.setAccentColor(accentColor)

        if (bitmap == null) return  // no art to inject — color already applied above

        val scaled = scaleBitmap(bitmap, 256)
        albumArtBitmap?.recycle()
        albumArtBitmap = scaled

        val artBytes = bitmapToJpegBytes(scaled)
        val idx      = player.currentMediaItemIndex
        val current  = player.currentMediaItem ?: return

        // Inject art bytes into MediaMetadata so DefaultMediaNotificationProvider
        // can use it as the notification large icon without a URI fetch.
        player.replaceMediaItem(
            idx,
            current.buildUpon()
                .setMediaMetadata(
                    current.mediaMetadata.buildUpon()
                        .setArtworkData(artBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build()
                )
                .build()
        )
    }

    // ── Session restore ───────────────────────────────────────────────────────

    fun tryRestoreSession(allSongs: List<Song>): Boolean {
        if (sessionRestored) return false
        sessionRestored = true

        val idx         = prefs.getInt("queue_index", -1)
        val pos         = prefs.getLong("position_ms", 0L)
        val repeat      = prefs.getString("repeat", RepeatMode.NONE.name)
            ?.let { runCatching { RepeatMode.valueOf(it) }.getOrDefault(RepeatMode.NONE) }
            ?: RepeatMode.NONE
        val shuffle     = prefs.getString("shuffle", ShuffleMode.OFF.name)
            ?.let { runCatching { ShuffleMode.valueOf(it) }.getOrDefault(ShuffleMode.OFF) }
            ?: ShuffleMode.OFF
        val queueIdsRaw = prefs.getString("queue_ids", "") ?: ""
        val origIdsRaw  = prefs.getString("orig_ids",  "") ?: ""

        if (queueIdsRaw.isBlank() || idx < 0) return false

        val queue = queueIdsRaw.split(",")
            .mapNotNull { it.toLongOrNull()?.let { id -> allSongs.find { s -> s.id == id } } }
        if (queue.isEmpty() || idx >= queue.size) return false

        originalQueue = if (origIdsRaw.isNotBlank())
            origIdsRaw.split(",")
                .mapNotNull { it.toLongOrNull()?.let { id -> allSongs.find { s -> s.id == id } } }
                .ifEmpty { queue }
        else queue

        loadQueueIntoPlayer(queue, idx)
        player.seekTo(idx, pos)
        player.repeatMode = repeat.toExoRepeat()

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
            .putString("queue_ids", ps.queue.joinToString(",") { it.id.toString() })
            .putString("orig_ids",  originalQueue.joinToString(",") { it.id.toString() })
            .putInt("queue_index",  ps.queueIndex)
            .putLong("position_ms", currentPosition())
            .putString("repeat",    ps.repeatMode.name)
            .putString("shuffle",   ps.shuffleMode.name)
            .apply()
    }

    // ── Internal command handlers ─────────────────────────────────────────────

    private fun toggleShuffleInternal() =
        setShuffleMode(
            if (_playbackState.value.shuffleMode == ShuffleMode.OFF) ShuffleMode.ON else ShuffleMode.OFF
        )

    private fun cycleRepeatInternal() =
        setRepeatMode(when (_playbackState.value.repeatMode) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL  -> RepeatMode.ONE
            RepeatMode.ONE  -> RepeatMode.NONE
        })

    private fun toggleFavoriteInternal() {
        val ps   = _playbackState.value
        val song = ps.currentSong ?: return
        val upd  = song.copy(isFavorite = !song.isFavorite)
        _playbackState.value = ps.copy(
            currentSong = upd,
            queue       = ps.queue.map { if (it.id == song.id) upd else it }
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildShuffledQueue(source: List<Song>, startIndex: Int): List<Song> {
        if (source.isEmpty()) return source
        val current = source[startIndex.coerceIn(0, source.size - 1)]
        return listOf(current) + (source - current).shuffled()
    }

    private fun loadQueueIntoPlayer(queue: List<Song>, startIndex: Int) {
        player.setMediaItems(queue.map { it.toMediaItem() }, startIndex, C.TIME_UNSET)
        player.prepare()
    }

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
                    .setArtworkUri(albumArtUri)
                    .build()
            )
            .build()

    private fun RepeatMode.toExoRepeat() = when (this) {
        RepeatMode.ONE  -> Player.REPEAT_MODE_ONE
        RepeatMode.ALL  -> Player.REPEAT_MODE_ALL
        RepeatMode.NONE -> Player.REPEAT_MODE_OFF
    }

    private fun scaleBitmap(src: Bitmap, maxSide: Int): Bitmap {
        val scale = maxSide.toFloat() / maxOf(src.width, src.height)
        return Bitmap.createScaledBitmap(
            src,
            (src.width  * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun bitmapToJpegBytes(bmp: Bitmap): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }
}
