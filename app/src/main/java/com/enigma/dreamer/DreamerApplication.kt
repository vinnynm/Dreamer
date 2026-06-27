package com.enigma.dreamer

import android.app.Application
import android.util.Log
import androidx.work.Configuration

/**
 * Custom Application class for Dreamer.
 * 
 * Implements [Configuration.Provider] to provide manual initialization for WorkManager.
 * This resolves the IllegalStateException: "WorkManager is not initialized properly" 
 * which can occur if the default initializer is disabled or fails to run before 
 * a background component (like BootReceiver) attempts to use it.
 */
class DreamerApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()
}
