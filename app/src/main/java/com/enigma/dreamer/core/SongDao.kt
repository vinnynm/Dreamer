package com.enigma.dreamer.core

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Hot observable — emits whenever the songs table changes. */
    @Query("SELECT * FROM songs ORDER BY title ASC")
    fun observeAll(): Flow<List<SongEntity>>

    /** One-shot load for the fast-path on cold start. */
    @Query("SELECT * FROM songs ORDER BY title ASC")
    suspend fun getAll(): List<SongEntity>

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun count(): Int

    @Query("SELECT id FROM songs")
    suspend fun getAllIds(): List<Long>

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Insert or update. Uses REPLACE strategy so re-scanning simply
     * overwrites stale metadata without manual diffing.
     */
    @Upsert
    suspend fun upsertAll(songs: List<SongEntity>)

    @Upsert
    suspend fun upsert(song: SongEntity)

    /**
     * Remove songs that are no longer present in MediaStore.
     * Called at the end of every scan with the full set of discovered IDs.
     * Runs in a transaction so observers see the delete atomically.
     */
    @Query("DELETE FROM songs WHERE id NOT IN (:activeIds)")
    suspend fun deleteObsolete(activeIds: List<Long>)

    // ── Favorites sync ────────────────────────────────────────────────────────

    /** Bulk-update the denormalised isFavorite column from the favorites table. */
    @Query("UPDATE songs SET isFavorite = (id IN (:favoriteIds))")
    suspend fun syncFavorites(favoriteIds: List<Long>)

    @Query("UPDATE songs SET isFavorite = :isFavorite WHERE id = :songId")
    suspend fun setFavorite(songId: Long, isFavorite: Boolean)

    // ── Lyric hint ────────────────────────────────────────────────────────────

    @Query("UPDATE songs SET hasLyricHint = :hint WHERE id = :songId")
    suspend fun setLyricHint(songId: Long, hint: Boolean)
}
