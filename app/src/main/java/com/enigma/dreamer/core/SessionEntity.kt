package com.enigma.dreamer.core

import androidx.room.*

// ─────────────────────────────────────────────────────────────────────────────
// 9.5 — Room session table
//
// Replaces SharedPreferences-based session persistence in MusicService.
//
// Why Room instead of SharedPreferences?
//  - SharedPreferences.apply() is asynchronous; the session can be lost if
//    the process is killed before the async write queue drains (FIX A-4 used
//    commit() as a workaround, but that blocks the main thread in onDestroy).
//  - Room writes happen on a dedicated IO thread; we can use a coroutine
//    and still get a synchronous write without blocking the main thread.
//  - Enables future features: multiple saved sessions, named queues, history.
//  - A single source of truth: the database file is backed up by Auto Backup;
//    SharedPreferences is not included in Auto Backup by default.
//
// Schema design: a single row with id = 1. We always upsert this row,
// never insert a new one. This keeps the DAO trivially simple.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Persisted playback session snapshot.
 *
 * Only one row ever exists (id = SLOT). It is upserted after every meaningful
 * playback event (song change, seek, pause, queue reorder) and read on the
 * next app launch to restore the previous session.
 */
@Entity(tableName = "session")
data class SessionEntity(
    @PrimaryKey val id:         Int    = SLOT,
    val queueIds:               String = "",   // comma-separated song IDs
    val origIds:                String = "",   // original (pre-shuffle) queue IDs
    val queueIndex:             Int    = -1,
    val positionMs:             Long   = 0L,
    val repeatMode:             String = RepeatMode.NONE.name,
    val shuffleMode:            String = ShuffleMode.OFF.name,
    val savedAtMs:              Long   = System.currentTimeMillis()
) {
    companion object {
        const val SLOT = 1
    }
}

@Dao
interface SessionDao {

    /**
     * Returns the current session, or null if no session has ever been saved.
     * (First launch: table is empty.)
     */
    @Query("SELECT * FROM session WHERE id = ${SessionEntity.SLOT} LIMIT 1")
    suspend fun get(): SessionEntity?

    /**
     * Persists the session snapshot, replacing any previous row.
     * Uses REPLACE conflict strategy so there is always at most one row.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(session: SessionEntity)

    /** Clears the session so the service won't try to restore it on next launch. */
    @Query("DELETE FROM session WHERE id = ${SessionEntity.SLOT}")
    suspend fun clear()
}
