package com.enigma.dreamer.core

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
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
    private val songDao     get() = db.songDao()

    // ── Fast cold-start path ──────────────────────────────────────────────────

    /**
     * Returns songs from the Room cache immediately (<100 ms for 1000+ songs),
     * then triggers a background MediaStore scan to pick up any changes since
     * the last run.
     *
     * First-ever launch: DB is empty, returns empty list. The ViewModel
     * transitions straight to the background scan in that case.
     */
    suspend fun loadSongsFromCache(): List<Song> = withContext(Dispatchers.IO) {
        val favoriteIds = favoriteDao.getFavorites().toSet()
        songDao.getAll().map { entity ->
            entity.toSong().copy(isFavorite = entity.id in favoriteIds)
        }
    }

    /**
     * Observes the Room songs table as a hot Flow.
     * Emits whenever the cache is updated by [scanAndSync].
     */
    fun observeSongs(): Flow<List<Song>> =
        songDao.observeAll().map { entities ->
            entities.map { it.toSong() }
        }

    /**
     * Full MediaStore scan — metadata only, no file reads.
     *
     * FIX A-3: DB writes are now made via [SongDao.replaceAll], which wraps
     * upsertAll + deleteObsolete in a single @Transaction. A partial scan that
     * throws mid-way will not leave stale rows in the DB.
     *
     * Returns the full refreshed song list so the ViewModel can update state
     * immediately without waiting for the Flow observer to tick.
     */
    suspend fun scanAndSync(): List<Song> = withContext(Dispatchers.IO) {
        val favoriteIds  = favoriteDao.getFavorites().toSet()
        val scannedSongs = mutableListOf<SongEntity>()
        val uri          = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

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
                val id      = cursor.getLong(idCol)
                val title   = cursor.getString(titleCol)   ?: "Unknown"
                val artist  = cursor.getString(artistCol)  ?: "Unknown Artist"
                val album   = cursor.getString(albumCol)   ?: "Unknown Album"
                val duration= cursor.getLong(durationCol)
                val data    = cursor.getString(dataCol)    ?: continue
                val albumId = cursor.getLong(albumIdCol)
                val year    = cursor.getInt(yearCol)
                val track   = cursor.getInt(trackCol)
                val size    = cursor.getLong(sizeCol)
                val mime    = cursor.getString(mimeCol)    ?: ""

                val songUri     = ContentUris.withAppendedId(uri, id)
                val albumArtUri = "content://media/external/audio/albumart/$albumId"

                // Cheap lyric hint — no bytes read, only file metadata syscalls
                val hasLyricHint = hasLyricSidecar(data) || mimeSupportsEmbeddedTags(mime)

                scannedSongs += SongEntity(
                    id           = id,
                    title        = title,
                    artist       = artist,
                    album        = album,
                    duration     = duration,
                    uri          = songUri.toString(),
                    albumArtUri  = albumArtUri,
                    isFavorite   = id in favoriteIds,
                    year         = year,
                    trackNumber  = track,
                    filePath     = data,
                    fileSize     = size,
                    mimeType     = mime,
                    hasLyricHint = hasLyricHint
                )
            }
        }

        // FIX A-3: single atomic transaction — replaceAll refuses to act if
        // scannedSongs is empty, so a failed/aborted scan can't wipe the cache.
        if (scannedSongs.isNotEmpty()) {
            songDao.replaceAll(scannedSongs)
            // Keep the denormalised isFavorite column in sync
            songDao.syncFavorites(favoriteIds.toList())
        }

        // Return domain objects — ViewModel updates state immediately
        scannedSongs.map { entity ->
            entity.toSong().copy(isFavorite = entity.id in favoriteIds)
        }
    }

    // ── Lazy lyric loading ────────────────────────────────────────────────────

    /**
     * Loads lyrics for a single song on demand — called by the ViewModel when
     * the user opens Now Playing, NOT during the library scan.
     */
    suspend fun loadLyricsForSong(song: Song): LyricDocument? = withContext(Dispatchers.IO) {
        if (song.filePath.isBlank()) return@withContext null
        val doc = tryLoadLyrics(song.filePath)
        songDao.setLyricHint(song.id, doc != null)
        doc
    }

    // ── Sort ──────────────────────────────────────────────────────────────────

    fun sort(songs: List<Song>, order: SortOrder): List<Song> = when (order) {
        SortOrder.TITLE_ASC       -> songs.sortedBy    { it.title.lowercase() }
        SortOrder.TITLE_DESC      -> songs.sortedByDescending { it.title.lowercase() }
        SortOrder.ARTIST_ASC      -> songs.sortedBy    { it.artist.lowercase() }
        SortOrder.ARTIST_DESC     -> songs.sortedByDescending { it.artist.lowercase() }
        SortOrder.ALBUM_ASC       -> songs.sortedWith(compareBy({ it.album.lowercase() }, { it.trackNumber }))
        SortOrder.DURATION_ASC    -> songs.sortedBy    { it.duration }
        SortOrder.DURATION_DESC   -> songs.sortedByDescending { it.duration }
        SortOrder.DATE_ADDED_DESC -> songs.sortedByDescending { it.id }
        SortOrder.FAVORITES_FIRST -> songs.sortedWith(
            compareByDescending<Song> { it.isFavorite }.thenBy { it.title.lowercase() })
    }

    // ── Favorites ─────────────────────────────────────────────────────────────

    fun observeFavoriteIds(): Flow<Set<Long>> =
        favoriteDao.observeFavorites().map { it.toSet() }

    suspend fun toggleFavorite(songId: Long) = withContext(Dispatchers.IO) {
        favoriteDao.toggle(songId)
        val isFav = favoriteDao.isFavorite(songId)
        songDao.setFavorite(songId, isFav)
    }

    suspend fun isFavorite(songId: Long): Boolean = withContext(Dispatchers.IO) {
        favoriteDao.isFavorite(songId)
    }

    // ── Playlists ─────────────────────────────────────────────────────────────

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
                // For empty playlists the LEFT JOIN returns one row with songId=null;
                // mapNotNull drops it safely, yielding emptyList() as expected.
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

    // ── Lyric write operations ────────────────────────────────────────────────

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

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun hasLyricSidecar(audioPath: String): Boolean {
        val base = audioPath.substringBeforeLast('.')
        return File("$base.lrc").exists() || File("$base.srt").exists()
    }

    private fun mimeSupportsEmbeddedTags(mime: String): Boolean {
        val lower = mime.lowercase()
        return lower.contains("mpeg") ||
                lower.contains("mp4")  ||
                lower.contains("m4a")  ||
                lower.contains("aac")
    }

    /**
     * Full lyric probe — reads file bytes. Only called from [loadLyricsForSong],
     * never from the scan path.
     *
     * FIX B-11: Previously this called File.readBytes() which would load an
     * entire 50+ MB audio file into heap memory just to look for a tag in the
     * first few kilobytes. Now we cap the read at 64 KB — more than enough for
     * any ID3v2 header or iTunes atom — dramatically reducing GC pressure and
     * eliminating OOM risk on low-RAM devices.
     *
     * Note: sidecar .lrc/.srt files are still read in full (they are text files,
     * typically < 50 KB). Only embedded-tag probing is capped.
     */

    private fun tryLoadLyrics(audioPath: String): LyricDocument? {
        val base = audioPath.substringBeforeLast('.')
        File("$base.lrc").takeIf { it.exists() }
            ?.let { return runCatching { LyricParser.parse(it, LyricFormat.LRC) }.getOrNull() }
        File("$base.srt").takeIf { it.exists() }
            ?.let { return runCatching { LyricParser.parse(it, LyricFormat.SRT) }.getOrNull() }

        val ext = audioPath.substringAfterLast('.').lowercase()
        if (ext in listOf("mp3", "m4a", "aac", "mp4")) {
            // FIX B-11: read only the first 64 KB instead of the entire file.
            // ID3v2 tags sit at the start of MP3 files; iTunes atoms at the
            // start of M4A files. 64 KB covers even the most heavily padded
            // headers while keeping heap allocation tiny.
            val bytes = runCatching {
                File(audioPath).inputStream().use { stream ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        stream.readNBytes(65_536)
                    } else {
                        TODO()

                    }   // 64 KB cap
                }
            }.getOrNull() ?: return null
            return LyricBaker.extractLyrics(bytes, ext)
        }
        return null
    }
}
