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

    @Upsert
    suspend fun upsertAll(songs: List<SongEntity>)

    @Upsert
    suspend fun upsert(song: SongEntity)

    // FIX B-1: The raw query `DELETE FROM songs WHERE id NOT IN ()` on an empty
    // list becomes `DELETE FROM songs` in SQLite — a full table wipe.  The DAO
    // method now contains its own safety net so no caller can ever accidentally
    // pass an empty list and silently destroy the cache.
    //
    // The caller (SongRepository.scanAndSync) already has an `isNotEmpty` guard,
    // but defence-in-depth at the DAO level means a future refactor can't remove
    // the outer check and accidentally nuke user data.
    suspend fun deleteObsolete(activeIds: List<Long>) {
        if (activeIds.isEmpty()) return   // ← safety net; never wipe on empty scan
        deleteObsoleteInternal(activeIds)
    }

    @Query("DELETE FROM songs WHERE id NOT IN (:activeIds)")
    suspend fun deleteObsoleteInternal(activeIds: List<Long>)

    // FIX A-3: Atomic scan write ───────────────────────────────────────────────
    //
    // Previously upsertAll + deleteObsolete were two separate non-transactional
    // calls in SongRepository.scanAndSync(). If the process was killed between
    // them the DB would hold stale rows that no longer exist on disk, causing
    // ExoPlayer SOURCE_ERROR when the user tried to play those songs on the next
    // launch.
    //
    // @Transaction makes the pair atomic: either both succeed or neither does.
    // Room also prevents other DAO calls from interleaving on a separate
    // connection while the write is in flight.

    /**
     * Atomically replaces the song cache with [freshSongs] and removes any rows
     * whose IDs are not in the new set.
     *
     * Safe to call from any coroutine dispatcher — Room dispatches DB work on
     * its own executor. Refuses to wipe the cache when [freshSongs] is empty
     * (e.g., a failed or interrupted scan).
     */
    @Transaction
    suspend fun replaceAll(freshSongs: List<SongEntity>) {
        if (freshSongs.isEmpty()) return   // refuse to wipe cache on empty scan
        upsertAll(freshSongs)
        deleteObsolete(freshSongs.map { it.id })
    }

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
