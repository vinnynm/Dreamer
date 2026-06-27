package com.enigma.dreamer.core

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ── Entities ──────────────────────────────────────────────────────────────────

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [ForeignKey(
        entity        = PlaylistEntity::class,
        parentColumns = ["id"],
        childColumns  = ["playlistId"],
        onDelete      = ForeignKey.CASCADE
    )],
    indices = [Index("playlistId"), Index("songId")]
)
data class PlaylistSongCrossRef(
    val playlistId: Long,
    val songId: Long,
    val position: Int = 0
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val songId: Long,
    val addedAt: Long = System.currentTimeMillis()
)

data class PlaylistWithSongIds(
    val playlistId: Long,
    val name: String,
    val createdAt: Long,
    val songId: Long?,
    val position: Int?
) {
    fun toPlaylistEntity() = PlaylistEntity(playlistId, name, createdAt)
}

// ── DAOs ──────────────────────────────────────────────────────────────────────

@Dao
interface PlaylistDao {

    @Query("""
        SELECT p.id AS playlistId, p.name, p.createdAt, ps.songId, ps.position
        FROM playlists p
        LEFT JOIN playlist_songs ps ON ps.playlistId = p.id
        ORDER BY p.createdAt DESC, ps.position ASC
    """)
    fun observeAllWithSongIds(): Flow<List<PlaylistWithSongIds>>

    @Query("""
        SELECT p.id AS playlistId, p.name, p.createdAt, ps.songId, ps.position
        FROM playlists p
        LEFT JOIN playlist_songs ps ON ps.playlistId = p.id
        ORDER BY p.createdAt DESC, ps.position ASC
    """)
    suspend fun getAllWithSongIds(): List<PlaylistWithSongIds>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity): Int

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long): Int

    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getSongIdsForPlaylist(playlistId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSongToPlaylist(ref: PlaylistSongCrossRef): Long

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long): Int

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getSongCount(playlistId: Long): Int

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: Long): Int
}

@Dao
interface FavoriteDao {

    @Query("SELECT songId FROM favorites ORDER BY addedAt DESC")
    fun observeFavorites(): Flow<List<Long>>

    @Query("SELECT songId FROM favorites ORDER BY addedAt DESC")
    suspend fun getFavorites(): List<Long>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE songId = :songId)")
    suspend fun isFavorite(songId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(fav: FavoriteEntity): Long

    @Query("DELETE FROM favorites WHERE songId = :songId")
    suspend fun removeFavorite(songId: Long): Int

    @Transaction
    suspend fun toggle(songId: Long) {
        if (isFavorite(songId)) removeFavorite(songId)
        else addFavorite(FavoriteEntity(songId))
    }
}

// ── Database ──────────────────────────────────────────────────────────────────


@Database(
    entities     = [
        PlaylistEntity::class,
        PlaylistSongCrossRef::class,
        FavoriteEntity::class,
        SongEntity::class,
        SessionEntity::class     // NEW in v4
    ],
    version      = 4,            // was 3
    exportSchema = false
)
abstract class DevLyricDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun songDao(): SongDao
    abstract fun sessionDao(): SessionDao   // NEW in v4

    companion object {
        @Volatile private var INSTANCE: DevLyricDatabase? = null

        /** v1 → v2: recreate playlists + playlist_songs with autoGenerate PK. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS playlist_songs")
                db.execSQL("DROP TABLE IF EXISTS playlists")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS playlists (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS playlist_songs (
                        playlistId INTEGER NOT NULL,
                        songId INTEGER NOT NULL,
                        position INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(playlistId, songId),
                        FOREIGN KEY(playlistId) REFERENCES playlists(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_songs_playlistId ON playlist_songs(playlistId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_songs_songId ON playlist_songs(songId)")
            }
        }

        /** v2 → v3: adds songs table for MediaStore cache. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS songs (
                        id INTEGER PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        album TEXT NOT NULL,
                        duration INTEGER NOT NULL,
                        uri TEXT NOT NULL,
                        albumArtUri TEXT,
                        isFavorite INTEGER NOT NULL DEFAULT 0,
                        year INTEGER NOT NULL DEFAULT 0,
                        trackNumber INTEGER NOT NULL DEFAULT 0,
                        filePath TEXT NOT NULL DEFAULT '',
                        fileSize INTEGER NOT NULL DEFAULT 0,
                        mimeType TEXT NOT NULL DEFAULT '',
                        hasLyricHint INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_title ON songs(title ASC)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_artist ON songs(artist ASC)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_isFavorite ON songs(isFavorite)")
            }
        }

        /** v3 → v4: adds session table for Room-backed playback session storage. */
        private val MIGRATION_3_4 = MIGRATION_3_4_IMPL

        fun getInstance(context: Context): DevLyricDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    DevLyricDatabase::class.java,
                    "devlyric.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}


/**
 * v3 → v4: adds the [session] table for Room-backed playback session
 * persistence (replaces SharedPreferences session storage).
 *
 * No existing data is touched — playlists, favorites, and the songs cache
 * survive intact. The session table starts empty; MusicService will write
 * to it the next time playback state changes.
 */
val MIGRATION_3_4_IMPL = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS session (
                id          INTEGER PRIMARY KEY NOT NULL DEFAULT 1,
                queueIds    TEXT    NOT NULL DEFAULT '',
                origIds     TEXT    NOT NULL DEFAULT '',
                queueIndex  INTEGER NOT NULL DEFAULT -1,
                positionMs  INTEGER NOT NULL DEFAULT 0,
                repeatMode  TEXT    NOT NULL DEFAULT 'NONE',
                shuffleMode TEXT    NOT NULL DEFAULT 'OFF',
                savedAtMs   INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }
}

