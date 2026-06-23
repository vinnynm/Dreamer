package com.enigma.dreamer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Restarts [MusicService] after a device reboot or app update so the last
 * session (persisted in SharedPreferences) can be restored.
 *
 * FIX B-12: The original receiver started the foreground service unconditionally
 * on every boot, which had two problems:
 *
 *  1. Users who had never played music (or had cleared the queue) would see a
 *     persistent "Now Playing" notification with nothing to show on every reboot.
 *
 *  2. On Android 12+ (API 31+), apps in certain background-start-restricted
 *     states cannot start foreground services from a broadcast receiver unless
 *     one of the foreground service launch exemptions applies. Starting the
 *     service unconditionally raised the risk of a ForegroundServiceStartNotAllowedException
 *     crash on restricted devices.
 *
 * Fix: read the persisted queue from SharedPreferences before starting. If the
 * queue_ids key is absent or blank, the user had nothing queued — skip the
 * service start entirely. The service will be started naturally the next time
 * the user opens the app and plays a song.
 *
 * Note: ACTION_MY_PACKAGE_REPLACED (app updated while installed) is exempt from
 * the Android 12 background-start restriction, so this check is belt-and-
 * suspenders rather than strictly necessary for that action, but it still avoids
 * the unwanted notification on update for users who weren't playing anything.
 */
class BootReceiver : BroadcastReceiver() {

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        // FIX B-12: Check whether a queue was persisted before starting the service.
        // MusicService writes "queue_ids" in persistState(); if it is blank or absent
        // there is nothing to restore and we should not start the service.
        val prefs    = context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)
        val queueIds = prefs.getString("queue_ids", "").orEmpty()

        if (queueIds.isBlank()) {
            // No persisted session — skip the service start.
            // The user will start the service naturally when they open the app.
            return
        }

        val serviceIntent = Intent(context, MusicService::class.java)
        context.startForegroundService(serviceIntent)
    }
}
