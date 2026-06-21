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
        SongEntity::class          // NEW in v3
    ],
    version      = 3,
    exportSchema = false
)
abstract class DevLyricDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun songDao(): SongDao        // NEW in v3

    companion object {
        @Volatile private var INSTANCE: DevLyricDatabase? = null

        /**
         * v1 → v2: playlists.id switched to autoGenerate.
         * Clean slate — drops and recreates playlists + playlist_songs.
         */
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

        /**
         * v2 → v3: adds the songs table for the MediaStore cache.
         * No existing data is touched — playlists and favorites survive intact.
         */
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
                // Index for the common sort order used in LibraryScreen
                db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_title ON songs(title ASC)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_artist ON songs(artist ASC)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_isFavorite ON songs(isFavorite)")
            }
        }

        fun getInstance(context: Context): DevLyricDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    DevLyricDatabase::class.java,
                    "devlyric.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
