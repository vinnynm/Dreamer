package com.enigma.dreamer.core

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.enigma.devlyric.core.LyricBaker
import com.enigma.devlyric.core.LyricDocument
import com.enigma.devlyric.core.LyricFormat
import com.enigma.devlyric.core.LyricParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.collections.map
import kotlin.collections.sortedBy
import androidx.core.net.toUri

/**
 * Single source-of-truth for songs (MediaStore) and playlists/favorites (Room).
 *
 * Playlist persistence is now backed by Room instead of SharedPreferences.
 * Favorites are stored in the `favorites` table and merged into Song objects
 * on each load/update.
 */
class SongRepository(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver
    private val db          by lazy { DevLyricDatabase.getInstance(context) }
    private val playlistDao get() = db.playlistDao()
    private val favoriteDao get() = db.favoriteDao()

    // ── Songs (MediaStore) ────────────────────────────────────────────────────

    suspend fun loadSongs(): List<Song> = withContext(Dispatchers.IO) {
        val favoriteIds = favoriteDao.getFavorites().toSet()
        val songs       = mutableListOf<Song>()
        val uri         = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 " +
                "AND ${MediaStore.Audio.Media.DURATION} > 10000"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
            val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataCol     = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val albumIdCol  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val yearCol     = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val trackCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val sizeCol     = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mimeCol     = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id       = cursor.getLong(idCol)
                val title    = cursor.getString(titleCol)   ?: "Unknown"
                val artist   = cursor.getString(artistCol)  ?: "Unknown Artist"
                val album    = cursor.getString(albumCol)   ?: "Unknown Album"
                val duration = cursor.getLong(durationCol)
                val data     = cursor.getString(dataCol)    ?: continue
                val albumId  = cursor.getLong(albumIdCol)
                val year     = cursor.getInt(yearCol)
                val track    = cursor.getInt(trackCol)
                val size     = cursor.getLong(sizeCol)
                val mime     = cursor.getString(mimeCol)    ?: ""

                val songUri     = ContentUris.withAppendedId(uri, id)
                val albumArtUri = "content://media/external/audio/albumart/$albumId".toUri()
                val lyricsDoc   = tryLoadLyrics(data)

                songs += Song(
                    id          = id,
                    title       = title,
                    artist      = artist,
                    album       = album,
                    duration    = duration,
                    uri         = songUri,
                    albumArtUri = albumArtUri,
                    lyricDocument = lyricsDoc,
                    isFavorite  = id in favoriteIds,
                    year        = year,
                    trackNumber = track,
                    filePath    = data,
                    fileSize    = size,
                    mimeType    = mime
                )
            }
        }
        songs
    }

    /** Sort a song list according to [order]. */
    fun sort(songs: List<Song>, order: SortOrder): List<Song> = when (order) {
        SortOrder.TITLE_ASC      -> songs.sortedBy    { it.title.lowercase() }
        SortOrder.TITLE_DESC     -> songs.sortedByDescending { it.title.lowercase() }
        SortOrder.ARTIST_ASC     -> songs.sortedBy    { it.artist.lowercase() }
        SortOrder.ARTIST_DESC    -> songs.sortedByDescending { it.artist.lowercase() }
        SortOrder.ALBUM_ASC      -> songs.sortedWith(
            compareBy({ it.album.lowercase() }, { it.trackNumber }))
        SortOrder.DURATION_ASC   -> songs.sortedBy    { it.duration }
        SortOrder.DURATION_DESC  -> songs.sortedByDescending { it.duration }
        SortOrder.DATE_ADDED_DESC-> songs.sortedByDescending { it.id }   // id is insertion order in MediaStore
        SortOrder.FAVORITES_FIRST-> songs.sortedWith(
            compareByDescending<Song> { it.isFavorite }.thenBy { it.title.lowercase() })
    }

    // ── Favorites ─────────────────────────────────────────────────────────────

    fun observeFavoriteIds(): Flow<Set<Long>> =
        favoriteDao.observeFavorites().map { it.toSet() }

    suspend fun toggleFavorite(songId: Long) = withContext(Dispatchers.IO) {
        favoriteDao.toggle(songId)
    }

    suspend fun isFavorite(songId: Long): Boolean = withContext(Dispatchers.IO) {
        favoriteDao.isFavorite(songId)
    }

    // ── Playlists (Room — N+1 eliminated via batch load) ─────────────────────

    /**
     * Observe all playlists as a live Flow. Uses a single batch query for all
     * song-ID lists instead of one query per playlist (eliminates N+1).
     */
    fun observePlaylists(): Flow<List<com.enigma.dreamer.core.Playlist>> =
        playlistDao.observeAllWithSongIds().map { rows -> buildPlaylists(rows) }

    suspend fun loadPlaylists(): List<com.enigma.dreamer.core.Playlist> = withContext(Dispatchers.IO) {
        buildPlaylists(playlistDao.getAllWithSongIds())
    }

    private fun buildPlaylists(rows: List<PlaylistWithSongIds>): List<Playlist> {
        // Group cross-ref rows by playlist
        val grouped = rows.groupBy { it.playlistId }
        return rows.map { it.toPlaylistEntity() }.distinctBy { it.id }.map { entity ->
            val ids = (grouped[entity.id]
                ?.sortedBy { it.position }
                ?.map { it.songId }
                ?: emptyList())?:emptyList()
            Playlist(entity.id, entity.name, ids, entity.createdAt)
        }
    }

    suspend fun createPlaylist(name: String): Playlist = withContext(Dispatchers.IO) {
        val id = System.currentTimeMillis()
        playlistDao.insertPlaylist(PlaylistEntity(id, name))
        Playlist(id, name)
    }

    suspend fun renamePlaylist(playlistId: Long, newName: String) = withContext(Dispatchers.IO) {
        playlistDao.updatePlaylist(PlaylistEntity(playlistId, newName))
    }

    suspend fun deletePlaylist(playlistId: Long) = withContext(Dispatchers.IO) {
        playlistDao.deletePlaylist(playlistId)
    }

    suspend fun addSongToPlaylist(songId: Long, playlistId: Long) = withContext(Dispatchers.IO) {
        val position = playlistDao.getSongCount(playlistId)
        playlistDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId, songId, position))
    }

    suspend fun removeSongFromPlaylist(songId: Long, playlistId: Long) = withContext(Dispatchers.IO) {
        playlistDao.removeSongFromPlaylist(playlistId, songId)
    }

    // ── Lyric helpers ─────────────────────────────────────────────────────────

    private fun tryLoadLyrics(audioPath: String): LyricDocument? {
        val base = audioPath.substringBeforeLast('.')
        File("$base.lrc").takeIf { it.exists() }
            ?.let { return runCatching { LyricParser.parse(it, LyricFormat.LRC) }.getOrNull() }
        File("$base.srt").takeIf { it.exists() }
            ?.let { return runCatching { LyricParser.parse(it, LyricFormat.SRT) }.getOrNull() }
        val ext = audioPath.substringAfterLast('.').lowercase()
        if (ext in listOf("mp3", "m4a", "aac", "mp4")) {
            val bytes = runCatching { File(audioPath).readBytes() }.getOrNull() ?: return null
            return LyricBaker.extractLyrics(bytes, ext)
        }
        return null
    }
}
