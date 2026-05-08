package com.enigma.dreamer.core

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ── Entities ──────────────────────────────────────────────────────────────────

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [ForeignKey(
        entity       = PlaylistEntity::class,
        parentColumns= ["id"],
        childColumns = ["playlistId"],
        onDelete     = ForeignKey.CASCADE
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

// ── Flat result type for batch playlist+song-id query (eliminates N+1) ────────

/**
 * Result row from the single JOIN query that loads all playlists with their
 * song IDs in one database round-trip instead of N+1.
 */
data class PlaylistWithSongIds(
    val playlistId: Long,
    val name: String,
    val createdAt: Long,
    val songId: Long?,       // nullable — playlist may have 0 songs
    val position: Int?
) {
    fun toPlaylistEntity() = PlaylistEntity(playlistId, name, createdAt)
}

// ── DAOs ──────────────────────────────────────────────────────────────────────

@Dao
interface PlaylistDao {

    // Single JOIN query — one DB round-trip for ALL playlists + their song IDs.
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

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

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
        if (isFavorite(songId)) removeFavorite(songId) else addFavorite(FavoriteEntity(songId))
    }
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(
    entities     = [PlaylistEntity::class, PlaylistSongCrossRef::class, FavoriteEntity::class],
    version      = 1,
    exportSchema = false
)
abstract class DevLyricDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        @Volatile private var INSTANCE: DevLyricDatabase? = null

        fun getInstance(context: android.content.Context): DevLyricDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    DevLyricDatabase::class.java,
                    "devlyric.db"
                ).build().also { INSTANCE = it }
            }
    }
}
