package com.enigma.dreamer.core


import androidx.core.net.toUri
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for the songs table.
 *
 * We deliberately do NOT store lyricDocument here — lyrics are loaded
 * lazily in SongRepository.loadLyricsForSong() when the user actually
 * opens the Now Playing screen. Storing serialised LRC in SQLite would
 * bloat the DB and make the initial load slower, not faster.
 *
 * isFavorite is kept as a denormalised column for fast sort/filter
 * without a join. The FavoriteDao remains the source of truth — we
 * update this column whenever favorites change via syncFavorites().
 */
@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val uri: String,            // Uri serialised as string
    val albumArtUri: String?,
    val isFavorite: Boolean = false,
    val year: Int = 0,
    val trackNumber: Int = 0,
    val filePath: String = "",
    val fileSize: Long = 0L,
    val mimeType: String = "",
    // hasLyrics: true if a sidecar .lrc/.srt was detected at last scan,
    // OR if the mime type supports embedded tags (mp3/m4a). We use this
    // as a hint in the UI ("Has lyrics: Yes") without reading the file.
    val hasLyricHint: Boolean = false
) {
    fun toSong(lyricDocument: com.enigma.devlyric.core.LyricDocument? = null) = Song(
        id            = id,
        title         = title,
        artist        = artist,
        album         = album,
        duration      = duration,
        uri           = uri.toUri(),
        albumArtUri   = albumArtUri?.toUri(),
        lyricDocument = lyricDocument,
        isFavorite    = isFavorite,
        year          = year,
        trackNumber   = trackNumber,
        filePath      = filePath,
        fileSize      = fileSize,
        mimeType      = mimeType
    )
}

fun Song.toEntity(hasLyricHint: Boolean = lyricDocument != null) = SongEntity(
    id           = id,
    title        = title,
    artist       = artist,
    album        = album,
    duration     = duration,
    uri          = uri.toString(),
    albumArtUri  = albumArtUri?.toString(),
    isFavorite   = isFavorite,
    year         = year,
    trackNumber  = trackNumber,
    filePath     = filePath,
    fileSize     = fileSize,
    mimeType     = mimeType,
    hasLyricHint = hasLyricHint
)
