package com.enigma.dreamer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import com.enigma.dreamer.core.DevLyricDatabase
import kotlinx.coroutines.runBlocking

/**
 * Restarts [MusicService] after a device reboot or app update so the last
 * session can be restored.
 *
 * 9.5: Previously read `queue_ids` from SharedPreferences to decide whether
 * to start the service. Now reads from the Room [SessionDao] instead, keeping
 * the session source of truth in a single place (the database).
 *
 * The [runBlocking] call is acceptable here: [BroadcastReceiver.onReceive]
 * must complete quickly (< 10 s before ANR), and a single Room SELECT on an
 * indexed primary key completes in < 1 ms. We don't launch a coroutine because
 * BroadcastReceivers have no reliable lifecycle to scope one to.
 */
class BootReceiver : BroadcastReceiver() {

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        // 9.5: check Room session table instead of SharedPreferences.
        // If no session row exists, or the queue is blank, there's nothing to restore.
        val hasSession = runBlocking {
            val session = DevLyricDatabase.getInstance(context).sessionDao().get()
            session != null && session.queueIds.isNotBlank() && session.queueIndex >= 0
        }

        if (!hasSession) {
            // No persisted session — skip the service start.
            return
        }

        context.startForegroundService(Intent(context, MusicService::class.java))
    }
}
