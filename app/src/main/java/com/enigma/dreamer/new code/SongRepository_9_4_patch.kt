// ─────────────────────────────────────────────────────────────────────────────
// PATCH FILE — SongRepository.kt  (9.4 additions)
//
// Add these two members to the SongRepository class body.
// Place them after the existing `observeSongs()` function.
//
// Purpose: expose a cold Flow<Unit> that emits whenever Android's MediaStore
// audio table changes. The ViewModel collects this flow and triggers a rescan
// (debounced by 2 s) so the UI auto-refreshes when a new song is downloaded,
// a file is deleted, or tags are edited by another app — without the user
// needing to tap the manual Rescan button.
//
// Implementation notes:
//  - We register a ContentObserver on MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.
//  - The observer is wrapped in a callbackFlow so it integrates cleanly with
//    coroutines and is automatically cancelled when the Flow collector is gone.
//  - A private var keeps a reference to the registered observer so it can be
//    unregistered in onCleared (called from the ViewModel).
//  - The Flow is created lazily (only when collected) so apps that never call
//    observeMediaStoreChanges() pay no overhead.
// ─────────────────────────────────────────────────────────────────────────────

package com.enigma.dreamer.core

// ADD these imports to the existing SongRepository import block:
// import android.database.ContentObserver
// import android.os.Handler
// import android.os.Looper
// import android.provider.MediaStore
// import kotlinx.coroutines.channels.awaitClose
// import kotlinx.coroutines.flow.callbackFlow

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

// ─────────────────────────────────────────────────────────────────────────────
// Add the following two members inside the SongRepository class:
// ─────────────────────────────────────────────────────────────────────────────

// ── 9.4: MediaStore ContentObserver ──────────────────────────────────────────

// Track the registered observer so we can unregister it in onCleared().
// Nullable; null means the observer has not been registered yet (or was
// already unregistered).
// private var mediaStoreObserver: ContentObserver? = null

/**
 * Returns a cold [Flow] that emits [Unit] whenever the OS notifies this app
 * of a change to the audio MediaStore table.
 *
 * The observer is registered when the Flow is collected and automatically
 * unregistered when the collector is cancelled (via [awaitClose]).  The ViewModel
 * debounces emissions so a burst of file-system events triggers only one rescan.
 *
 * Thread safety: ContentObserver.onChange is called on the main thread (we pass
 * the main-thread Handler in the constructor), so the trySend call is safe.
 */

// Add this snippet inside SongRepository:
/*
fun observeMediaStoreChanges(): Flow<Unit> = callbackFlow {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            trySend(Unit)
        }
    }
    mediaStoreObserver = observer
    contentResolver.registerContentObserver(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        /* notifyForDescendants = */ true,
        observer
    )
    awaitClose {
        contentResolver.unregisterContentObserver(observer)
        mediaStoreObserver = null
    }
}

fun unregisterMediaStoreObserver() {
    mediaStoreObserver?.let { contentResolver.unregisterContentObserver(it) }
    mediaStoreObserver = null
}
*/

// ─────────────────────────────────────────────────────────────────────────────
// Full self-contained class for reference (not a replacement for the whole
// SongRepository — merge the members above into your existing file).
// The class below only exists so this file compiles as a standalone reference.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Self-contained demonstration of the 9.4 additions.
 * In the real project, add [observeMediaStoreChanges] and
 * [unregisterMediaStoreObserver] directly to the existing SongRepository class.
 */
class MediaStoreObserverExtension(private val contentResolver: ContentResolver) {

    @Volatile private var mediaStoreObserver: ContentObserver? = null

    fun observeMediaStoreChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        mediaStoreObserver = observer
        contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            /* notifyForDescendants = */ true,
            observer
        )
        awaitClose {
            contentResolver.unregisterContentObserver(observer)
            mediaStoreObserver = null
        }
    }

    fun unregisterMediaStoreObserver() {
        mediaStoreObserver?.let { contentResolver.unregisterContentObserver(it) }
        mediaStoreObserver = null
    }
}
