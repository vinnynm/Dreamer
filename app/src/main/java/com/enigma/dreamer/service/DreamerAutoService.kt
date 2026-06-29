@file:Suppress("DEPRECATION") // 🟢 Suppresses all "Deprecated in Java" warnings for this file

package com.enigma.dreamer.service

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri

import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat

import com.enigma.dreamer.core.DevLyricDatabase
import com.enigma.dreamer.core.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 11.1 — Android Auto / MediaBrowserServiceCompat.
 */
@SuppressLint("RestrictedApi")
class DreamerAutoService : MediaBrowserServiceCompat() {

    companion object {
        private const val MEDIA_ROOT_ID   = "__ROOT__"
        private const val SONGS_ID        = "__SONGS__"
        private const val FAVORITES_ID    = "__FAVORITES__"
        private const val PLAYLISTS_ID    = "__PLAYLISTS__"
        private const val PLAYLIST_PREFIX = "playlist:"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db by lazy { DevLyricDatabase.getInstance(this) }
    private lateinit var mediaSession: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()

        mediaSession = MediaSessionCompat(this, "DreamerAutoSession").apply {
            setCallback(AutoSessionCallback())
            isActive = true
        }

        sessionToken = mediaSession.sessionToken

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_NONE, 0L, 1f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY               or
                            PlaybackStateCompat.ACTION_PAUSE              or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT       or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS   or
                            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                            PlaybackStateCompat.ACTION_SEEK_TO
                )
                .build()
        )
    }

    override fun onDestroy() {
        mediaSession.release()
        super.onDestroy()
    }

    // 🟢 FIXED: Changed parentId from String? to String, and return type to BrowserRoot?
    override fun onGetRoot(parentId: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        return BrowserRoot(MEDIA_ROOT_ID, null)
    }

    // 🟢 FIXED: Changed parentId from String? to String
    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.detach()
        scope.launch {
            val items = buildMediaItems(parentId)
            result.sendResult(items.toMutableList())
        }
    }

    private suspend fun buildMediaItems(parentId: String): List<MediaBrowserCompat.MediaItem> {
        return when (parentId) {
            MEDIA_ROOT_ID -> buildRootItems()
            SONGS_ID      -> buildSongItems(db.songDao().getAll().map { it.toSong() })
            FAVORITES_ID  -> {
                val favIds = db.favoriteDao().getFavorites().toSet()
                buildSongItems(db.songDao().getAll()
                    .filter { it.id in favIds }
                    .map { it.toSong() })
            }
            PLAYLISTS_ID  -> buildPlaylistItems()
            else          -> {
                if (parentId.startsWith(PLAYLIST_PREFIX)) {
                    val playlistId = parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                        ?: return emptyList()
                    val songIds = db.playlistDao().getSongIdsForPlaylist(playlistId).toSet()
                    buildSongItems(db.songDao().getAll()
                        .filter { it.id in songIds }
                        .sortedBy { songIds.indexOf(it.id) }
                        .map { it.toSong() })
                } else emptyList()
            }
        }
    }

    private fun buildRootItems(): List<MediaBrowserCompat.MediaItem> = listOf(
        browseItem(SONGS_ID,     "Songs",     "Your entire music library"),
        browseItem(FAVORITES_ID, "Favourites","Songs you've starred"),
        browseItem(PLAYLISTS_ID, "Playlists", "Your custom playlists")
    )

    private suspend fun buildPlaylistItems(): List<MediaBrowserCompat.MediaItem> {
        return db.playlistDao().getAllWithSongIds()
            .distinctBy { it.playlistId }
            .map { row ->
                browseItem(
                    mediaId      = "$PLAYLIST_PREFIX${row.playlistId}",
                    title        = row.name,
                    subtitle     = ""
                )
            }
    }

    private fun buildSongItems(songs: List<Song>): List<MediaBrowserCompat.MediaItem> =
        songs.map { song ->
            val desc = MediaDescriptionCompat.Builder()
                .setMediaId(song.id.toString())
                .setTitle(song.title)
                .setSubtitle(song.artist)
                .setDescription(song.album)
                .setMediaUri(song.uri)
                .also { b -> song.albumArtUri?.let { b.setIconUri(it) } }
                .build()
            MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
        }

    private fun browseItem(
        mediaId:  String,
        title:    String,
        subtitle: String
    ) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .setSubtitle(subtitle)
            .build(),
        MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
    )

    inner class AutoSessionCallback : MediaSessionCompat.Callback() {

        override fun onPlay() {
            startService(serviceIntent("cmd_play"))
            mediaSession.setPlaybackState(playing())
        }

        override fun onPause() {
            startService(serviceIntent("cmd_pause"))
            mediaSession.setPlaybackState(paused())
        }

        override fun onSkipToNext() {
            startService(serviceIntent("cmd_next"))
        }

        override fun onSkipToPrevious() {
            startService(serviceIntent("cmd_previous"))
        }

        override fun onSeekTo(pos: Long) {
            startService(serviceIntent("cmd_seek").putExtra("seek_pos_ms", pos))
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            mediaId?.toLongOrNull() ?: return
            val songs = runBlocking { db.songDao().getAll().map { it.toSong() } }
            val song  = songs.firstOrNull { it.id.toString() == mediaId } ?: return

            val index = songs.indexOfFirst { it.id.toString() == mediaId }
            val intent = serviceIntent("cmd_play_from_id")
                .putExtra("song_id", song.id)
                .putExtra("queue_start_index", index)
            startService(intent)

            updateNowPlayingMetadata(song)
            mediaSession.setPlaybackState(playing())
        }

        private fun serviceIntent(action: String) =
            Intent(this@DreamerAutoService, MusicService::class.java)
                .setAction(action)

        private fun updateNowPlayingMetadata(song: Song) {
            val meta = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE,        song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,       song.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM,        song.album)
                .putLong(  MediaMetadataCompat.METADATA_KEY_DURATION,     song.duration)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, song.albumArtUri?.toString() ?: "")
                .build()
            mediaSession.setMetadata(meta)
        }

        private fun playing() = PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1f)
            .setActions(defaultActions()).build()

        private fun paused() = PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_PAUSED, 0L, 1f)
            .setActions(defaultActions()).build()

        private fun defaultActions() =
            PlaybackStateCompat.ACTION_PLAY               or
                    PlaybackStateCompat.ACTION_PAUSE              or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT       or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS   or
                    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                    PlaybackStateCompat.ACTION_SEEK_TO
    }
}

// 🟢 Extension function remains here; the unused warning will clear out automatically
fun com.enigma.dreamer.core.SongEntity.toSong() = Song(
    id          = id,
    title       = title,
    artist      = artist,
    album       = album,
    duration    = duration,
    uri         = uri.toUri(),
    albumArtUri = albumArtUri?.toUri(),
    isFavorite  = isFavorite,
    year        = year,
    trackNumber = trackNumber,
    filePath    = filePath,
    fileSize    = fileSize,
    mimeType    = mimeType
)