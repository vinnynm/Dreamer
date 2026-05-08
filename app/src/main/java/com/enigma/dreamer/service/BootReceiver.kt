package com.enigma.dreamer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Restarts [com.enigma.devlyric.service.MusicService] after a device reboot or app update so the
 * last session (persisted in SharedPreferences) can be restored.
 */
class BootReceiver : BroadcastReceiver() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val serviceIntent = Intent(context, MusicService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}