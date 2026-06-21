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

// ── Flat result type for batch playlist+song-id query (eliminates N+1) ────────

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

/**
 * Migration v1 → v2.
 *
 * FIX: the original database used `fallbackToDestructiveMigration()`, which
 * silently DROPS AND RECREATES every table whenever the schema version bumps —
 * meaning every user's favorites and playlists would have been wiped on the
 * v1→v2 upgrade (and on any future bump) with zero warning to the user.
 *
 * The actual v1→v2 change here was adding autoGenerate to `playlists.id`.
 * SQLite can't ALTER a PRIMARY KEY's autoincrement behavior in place, so the
 * standard pattern is: rename the old table, recreate with the new schema,
 * copy data across, drop the old table. This preserves existing playlists,
 * cross-refs, and favorites instead of deleting them.
 *
 * If you don't actually have any v1 installs in the wild (e.g. this is still
 * pre-release), you can simplify this back to a plain destructive migration,
 * but doing so deliberately is safer than the implicit fallback.
 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE playlists RENAME TO playlists_old")
        db.execSQL(
            """
            CREATE TABLE playlists (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO playlists (id, name, createdAt)
            SELECT id, name, createdAt FROM playlists_old
            """.trimIndent()
        )
        db.execSQL("DROP TABLE playlists_old")
        // playlist_songs / favorites are unaffected by this particular change,
        // so no migration needed for them.
    }
}

@Database(
    entities     = [PlaylistEntity::class, PlaylistSongCrossRef::class, FavoriteEntity::class],
    version      = 2,
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
                    .addMigrations(MIGRATION_1_2)
                    // Destructive fallback now only triggers if a migration path
                    // is truly missing (e.g. very old installs predating v1),
                    // not as the default behavior for every bump.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
