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
import androidx.core.net.toUri

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

        contentResolver.query(
            uri, projection, selection, null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )?.use { cursor ->
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
                    id            = id,
                    title         = title,
                    artist        = artist,
                    album         = album,
                    duration      = duration,
                    uri           = songUri,
                    albumArtUri   = albumArtUri,
                    lyricDocument = lyricsDoc,
                    isFavorite    = id in favoriteIds,
                    year          = year,
                    trackNumber   = track,
                    filePath      = data,
                    fileSize      = size,
                    mimeType      = mime
                )
            }
        }
        songs
    }

    fun sort(songs: List<Song>, order: SortOrder): List<Song> = when (order) {
        SortOrder.TITLE_ASC      -> songs.sortedBy    { it.title.lowercase() }
        SortOrder.TITLE_DESC     -> songs.sortedByDescending { it.title.lowercase() }
        SortOrder.ARTIST_ASC     -> songs.sortedBy    { it.artist.lowercase() }
        SortOrder.ARTIST_DESC    -> songs.sortedByDescending { it.artist.lowercase() }
        SortOrder.ALBUM_ASC      -> songs.sortedWith(compareBy({ it.album.lowercase() }, { it.trackNumber }))
        SortOrder.DURATION_ASC   -> songs.sortedBy    { it.duration }
        SortOrder.DURATION_DESC  -> songs.sortedByDescending { it.duration }
        SortOrder.DATE_ADDED_DESC-> songs.sortedByDescending { it.id }
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

    // ── Playlists (Room) ──────────────────────────────────────────────────────

    fun observePlaylists(): Flow<List<Playlist>> =
        playlistDao.observeAllWithSongIds().map { rows -> buildPlaylists(rows) }

    suspend fun loadPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        buildPlaylists(playlistDao.getAllWithSongIds())
    }

    /**
     * Converts flat LEFT-JOIN rows into Playlist domain objects.
     *
     * Fixes from original:
     * - `songId` is Long? in the row type (LEFT JOIN can produce null) but we
     *   filter nulls out here, so Playlist.songIds is always List<Long>.
     * - Removed the double `?: emptyList()` noise.
     */
    private fun buildPlaylists(rows: List<PlaylistWithSongIds>): List<Playlist> {
        val grouped = rows.groupBy { it.playlistId }
        return rows
            .distinctBy { it.playlistId }
            .map { row ->
                val entity = row.toPlaylistEntity()
                val ids: List<Long> = grouped[entity.id]
                    ?.sortedBy { it.position }
                    ?.mapNotNull { it.songId }   // filter LEFT JOIN nulls — result is List<Long>
                    ?: emptyList()
                Playlist(entity.id, entity.name, ids, entity.createdAt)
            }
    }

    /**
     * Creates a playlist. Room's autoGenerate assigns the real ID;
     * we return a Playlist with that ID so callers stay in sync.
     */
    suspend fun createPlaylist(name: String): Playlist = withContext(Dispatchers.IO) {
        val entity    = PlaylistEntity(name = name)     // id=0 → autoGenerate
        val generated = playlistDao.insertPlaylist(entity)
        Playlist(generated, name)
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

    /**
     * Bakes [lyricText] (in [format]) into the audio file at [audioPath] in-place.
     * Returns the updated [LyricDocument] on success, null on failure.
     */
    suspend fun bakeLyrics(
        audioPath: String,
        lyricText: String,
        format: LyricFormat
    ): LyricDocument? = withContext(Dispatchers.IO) {
        runCatching {
            val file   = File(audioPath)
            val ext    = audioPath.substringAfterLast('.').lowercase()
            val doc    = LyricParser.parse(lyricText, format)
            val result = LyricBaker.bake(file, doc)
            if (result is com.enigma.devlyric.core.BakeResult.Success) doc else null
        }.getOrNull()
    }

    /**
     * Saves [lyricText] as a sidecar .lrc file next to the audio file.
     * Useful when the audio format doesn't support embedded tags.
     */
    suspend fun saveSidecarLrc(audioPath: String, lyricText: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val lrcPath = audioPath.substringBeforeLast('.') + ".lrc"
                File(lrcPath).writeText(lyricText)
                true
            }.getOrDefault(false)
        }
}
