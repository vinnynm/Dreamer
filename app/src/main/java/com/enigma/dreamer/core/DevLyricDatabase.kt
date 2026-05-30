package com.enigma.dreamer.core

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ── Entities ──────────────────────────────────────────────────────────────────

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,  // was manual timestamp — collision risk fixed
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
    val songId: Long,           // non-nullable — a cross-ref row without a songId is meaningless
    val position: Int = 0
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val songId: Long,
    val addedAt: Long = System.currentTimeMillis()
)

// ── Flat result type for batch playlist+song-id query (eliminates N+1) ────────

data class PlaylistWithSongIds(
    val playlistId: Long,
    val name: String,
    val createdAt: Long,
    val songId: Long?,      // nullable here only — LEFT JOIN may produce null for empty playlists
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
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long  // returns generated id

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
    entities     = [PlaylistEntity::class, PlaylistSongCrossRef::class, FavoriteEntity::class],
    version      = 2,   // bumped for schema change (autoGenerate on playlists)
    exportSchema = false
)
abstract class DevLyricDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        @Volatile private var INSTANCE: DevLyricDatabase? = null

        fun getInstance(context: Context): DevLyricDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    DevLyricDatabase::class.java,
                    "devlyric.db"
                )
                    .fallbackToDestructiveMigration()   // v1→v2: schema change, existing data cleared
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
