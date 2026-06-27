// ─────────────────────────────────────────────────────────────────────────────
// PATCH FILE — MusicService.kt  (9.5 changes)
//
// Replace the SharedPreferences-based persistence with Room SessionDao.
//
// Summary of changes:
//  1. Remove `private val prefs by lazy { ... }`.
//  2. Add `private val sessionDao by lazy { ... }`.
//  3. Replace `persistState()` with the coroutine-based version below.
//  4. Replace `persistStateSync()` with the version below.
//  5. Replace `tryRestoreSession()` with the version below.
//  6. Remove `persistStateSync()` call from BootReceiver (it now checks Room).
//     BootReceiver must also be updated — see BootReceiver_9_5_patch.kt.
//
// IMPORTANT: MusicService is a foreground service; its coroutine scope
// ([serviceScope]) runs on Dispatchers.Main + SupervisorJob. Room DAO
// suspend functions must be called from a coroutine — they dispatch to the
// IO executor automatically. The synchronous path (persistStateSync) now
// uses `runBlocking` which is acceptable only in onDestroy / onTaskRemoved
// because the service is being torn down anyway.
// ─────────────────────────────────────────────────────────────────────────────

package com.enigma.dreamer.service

import com.enigma.dreamer.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// ── REMOVE this from MusicService ────────────────────────────────────────────
// private val prefs by lazy {
//     getSharedPreferences("playback_state", Context.MODE_PRIVATE)
// }

// ── ADD this to MusicService (after `private val serviceScope = ...`) ─────────
// private val sessionDao by lazy { DevLyricDatabase.getInstance(this).sessionDao() }

// ─────────────────────────────────────────────────────────────────────────────
// REPLACE persistState() with:
// ─────────────────────────────────────────────────────────────────────────────

internal fun MusicService_persistState_replacement() {
    // Conceptual — not compiled directly. Copy into MusicService.

    /*
    private fun persistState() {
        val ps = _playbackState.value
        serviceScope.launch(Dispatchers.IO) {
            sessionDao.save(SessionEntity(
                queueIds    = ps.queue.joinToString(",") { it.id.toString() },
                origIds     = originalQueue.joinToString(",") { it.id.toString() },
                queueIndex  = ps.queueIndex,
                positionMs  = currentPosition(),
                repeatMode  = ps.repeatMode.name,
                shuffleMode = ps.shuffleMode.name
            ))
        }
    }
    */
}

// ─────────────────────────────────────────────────────────────────────────────
// REPLACE persistStateSync() with:
// ─────────────────────────────────────────────────────────────────────────────

internal fun MusicService_persistStateSync_replacement() {
    // Conceptual — not compiled directly. Copy into MusicService.

    /*
    // Used in onTaskRemoved() and onDestroy() where the process may be killed
    // immediately. runBlocking is acceptable here because we're already on a
    // lifecycle callback and the scope is being torn down. Room dispatches
    // the write to its own IO executor so we don't actually block the DB
    // write on the calling thread — we block until the coroutine *launches*.
    //
    // Note: if the process is killed hard (SIGKILL) before the Room write
    // completes, the session may still be lost — this is the same race that
    // existed with SharedPreferences.commit(). Room's WAL journal means partial
    // writes are safe (no corruption), and the next launch simply starts fresh.
    private fun persistStateSync() {
        val ps = _playbackState.value
        runBlocking(Dispatchers.IO) {
            sessionDao.save(SessionEntity(
                queueIds    = ps.queue.joinToString(",") { it.id.toString() },
                origIds     = originalQueue.joinToString(",") { it.id.toString() },
                queueIndex  = ps.queueIndex,
                positionMs  = currentPosition(),
                repeatMode  = ps.repeatMode.name,
                shuffleMode = ps.shuffleMode.name
            ))
        }
    }
    */
}

// ─────────────────────────────────────────────────────────────────────────────
// REPLACE tryRestoreSession() with:
// ─────────────────────────────────────────────────────────────────────────────

internal fun MusicService_tryRestoreSession_replacement() {
    // Conceptual — not compiled directly. Copy into MusicService.
    // Signature unchanged — callers in MusicViewModel need no update.

    /*
    fun tryRestoreSession(allSongs: List<Song>): Boolean {
        if (sessionRestored) return false
        sessionRestored = true

        // Read from Room synchronously (called from the service's coroutine
        // scope once songs are available; using runBlocking here to keep the
        // existing synchronous caller signature intact).
        val session = runBlocking(Dispatchers.IO) { sessionDao.get() }
            ?: return false

        val idx         = session.queueIndex
        val pos         = session.positionMs
        val repeat      = runCatching { RepeatMode.valueOf(session.repeatMode) }
            .getOrDefault(RepeatMode.NONE)
        val shuffle     = runCatching { ShuffleMode.valueOf(session.shuffleMode) }
            .getOrDefault(ShuffleMode.OFF)
        val queueIdsRaw = session.queueIds
        val origIdsRaw  = session.origIds

        if (queueIdsRaw.isBlank() || idx < 0) return false

        val queue = queueIdsRaw.split(",")
            .mapNotNull { it.toLongOrNull()?.let { id -> allSongs.find { s -> s.id == id } } }
        if (queue.isEmpty() || idx >= queue.size) return false

        originalQueue = if (origIdsRaw.isNotBlank())
            origIdsRaw.split(",")
                .mapNotNull { it.toLongOrNull()?.let { id -> allSongs.find { s -> s.id == id } } }
                .ifEmpty { queue }
        else queue

        loadQueueIntoPlayer(queue, idx)
        player.seekTo(idx, pos)
        player.repeatMode = repeat.toExoRepeat()

        _playbackState.value = _playbackState.value.copy(
            queue       = queue,
            queueIndex  = idx,
            currentSong = queue[idx],
            positionMs  = pos,
            repeatMode  = repeat,
            shuffleMode = shuffle
        )
        return true
    }
    */
}
