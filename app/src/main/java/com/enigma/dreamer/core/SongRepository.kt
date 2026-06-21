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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    /**
     * Loads songs from MediaStore.
     *
     * PERFORMANCE FIX: the original implementation called [tryLoadLyrics] inline
     * inside the cursor loop — for any MP3/M4A without a sidecar .lrc/.srt file,
     * this read the *entire audio file into memory* synchronously just to check
     * for embedded lyrics, once per song, on every single library load. For a
     * library of a few hundred songs this could mean reading gigabytes of audio
     * data on every app start.
     *
     * Fix: collect lightweight cursor data first (no IO), then run lyric
     * detection concurrently across a bounded set of coroutines using
     * Dispatchers.IO's thread pool, so multiple files are probed in parallel
     * instead of serially blocking the loop.
     */
    suspend fun loadSongs(): List<Song> = withContext(Dispatchers.IO) {
        val favoriteIds = favoriteDao.getFavorites().toSet()
        val rows        = mutableListOf<SongRow>()
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
                val data = cursor.getString(dataCol) ?: continue
                rows += SongRow(
                    id       = cursor.getLong(idCol),
                    title    = cursor.getString(titleCol)  ?: "Unknown",
                    artist   = cursor.getString(artistCol) ?: "Unknown Artist",
                    album    = cursor.getString(albumCol)  ?: "Unknown Album",
                    duration = cursor.getLong(durationCol),
                    data     = data,
                    albumId  = cursor.getLong(albumIdCol),
                    year     = cursor.getInt(yearCol),
                    track    = cursor.getInt(trackCol),
                    size     = cursor.getLong(sizeCol),
                    mime     = cursor.getString(mimeCol) ?: ""
                )
            }
        }

        // Probe lyrics concurrently with limited parallelism to avoid OOM/blocking
        // during large library scans.
        val lyricDispatcher = Dispatchers.IO.limitedParallelism(8)
        val lyricDocs = rows.map { row ->
            async(lyricDispatcher) { row.id to tryLoadLyrics(row.data, row.mime) }
        }.awaitAll().toMap()

        rows.map { row ->
            val songUri     = ContentUris.withAppendedId(uri, row.id)
            val albumArtUri = "content://media/external/audio/albumart/${row.albumId}".toUri()
            Song(
                id            = row.id,
                title         = row.title,
                artist        = row.artist,
                album         = row.album,
                duration      = row.duration,
                uri           = songUri,
                albumArtUri   = albumArtUri,
                lyricDocument = lyricDocs[row.id],
                isFavorite    = row.id in favoriteIds,
                year          = row.year,
                trackNumber   = row.track,
                filePath      = row.data,
                fileSize      = row.size,
                mimeType      = row.mime
            )
        }
    }

    /** Plain holder for cursor-extracted fields, used only inside [loadSongs]. */
    private data class SongRow(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val duration: Long,
        val data: String,
        val albumId: Long,
        val year: Int,
        val track: Int,
        val size: Long,
        val mime: String
    )

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

    private fun buildPlaylists(rows: List<PlaylistWithSongIds>): List<Playlist> {
        val grouped = rows.groupBy { it.playlistId }
        return rows
            .distinctBy { it.playlistId }
            .map { row ->
                val entity = row.toPlaylistEntity()
                val ids: List<Long> = grouped[entity.id]
                    ?.sortedBy { it.position }
                    ?.mapNotNull { it.songId }
                    ?: emptyList()
                Playlist(entity.id, entity.name, ids, entity.createdAt)
            }
    }

    suspend fun createPlaylist(name: String): Playlist = withContext(Dispatchers.IO) {
        val entity    = PlaylistEntity(name = name)
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

    /**
     * Resolution order: sidecar .lrc → sidecar .srt → embedded tag.
     * The sidecar checks are pure filesystem stats (no content read), so they're
     * effectively free. Only the embedded-tag path reads file bytes, and only
     * for recognized audio extensions.
     */
    private fun tryLoadLyrics(audioPath: String, mime: String): LyricDocument? {
        val base = audioPath.substringBeforeLast('.')

        File("$base.lrc").takeIf { it.exists() }
            ?.let { return runCatching { LyricParser.parse(it, LyricFormat.LRC) }.getOrNull() }
        File("$base.srt").takeIf { it.exists() }
            ?.let { return runCatching { LyricParser.parse(it, LyricFormat.SRT) }.getOrNull() }

        val ext = audioPath.substringAfterLast('.').lowercase()
        if (ext !in SUPPORTED_EMBEDDED_EXTS) return null

        val bytes = runCatching { File(audioPath).readBytes() }.getOrNull() ?: return null
        return LyricBaker.extractLyrics(bytes, ext)
    }

    suspend fun bakeLyrics(
        audioPath: String,
        lyricText: String,
        format: LyricFormat
    ): LyricDocument? = withContext(Dispatchers.IO) {
        runCatching {
            val file   = File(audioPath)
            val doc    = LyricParser.parse(lyricText, format)
            val result = LyricBaker.bake(file, doc)
            if (result is com.enigma.devlyric.core.BakeResult.Success) doc else null
        }.getOrNull()
    }

    suspend fun saveSidecarLrc(audioPath: String, lyricText: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val lrcPath = audioPath.substringBeforeLast('.') + ".lrc"
                File(lrcPath).writeText(lyricText)
                true
            }.getOrDefault(false)
        }

    companion object {
        private val SUPPORTED_EMBEDDED_EXTS = setOf("mp3", "m4a", "aac", "mp4")
    }
}
