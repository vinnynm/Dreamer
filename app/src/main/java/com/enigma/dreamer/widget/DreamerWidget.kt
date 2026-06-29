package com.enigma.dreamer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import com.enigma.dreamer.MainActivity
import com.enigma.dreamer.R
import com.enigma.dreamer.service.MusicService

/**
 * 11.3 — Home screen widget.
 *
 * Displays:
 *  - Album art thumbnail (circle-cropped in XML via a ClipToOutline view)
 *  - Song title + artist
 *  - Play/pause toggle button
 *  - Previous / Next buttons
 *  - Amber progress bar (width driven by a View with layout_weight)
 *
 * Widget state is pushed from [MusicService] whenever playback changes via
 * [DreamerWidget.update]. Since widgets run in a separate process, we use
 * [RemoteViews] to update the UI.
 *
 * The widget does NOT observe [PlaybackState] directly — [MusicService] broadcasts
 * [ACTION_WIDGET_UPDATE] intent whenever state changes, and [onReceive] handles it.
 *
 * Manifest registration required (see dreamer_widget_manifest_snippet.xml).
 */
class DreamerWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id ->
            updateWidget(context, appWidgetManager, id, WidgetState())
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_WIDGET_UPDATE) return

        val state = WidgetState(
            title     = intent.getStringExtra(EXTRA_TITLE)    ?: "Nothing playing",
            artist    = intent.getStringExtra(EXTRA_ARTIST)   ?: "",
            isPlaying = intent.getBooleanExtra(EXTRA_PLAYING, false),
            progress  = intent.getFloatExtra(EXTRA_PROGRESS,  0f),
            artUri    = intent.getStringExtra(EXTRA_ART_URI)?.let { Uri.parse(it) }
        )

        val manager = AppWidgetManager.getInstance(context)
        val ids     = manager.getAppWidgetIds(ComponentName(context, DreamerWidget::class.java))
        ids.forEach { id -> updateWidget(context, manager, id, state) }
    }

    companion object {
        const val ACTION_WIDGET_UPDATE = "com.enigma.dreamer.WIDGET_UPDATE"
        const val EXTRA_TITLE          = "widget_title"
        const val EXTRA_ARTIST         = "widget_artist"
        const val EXTRA_PLAYING        = "widget_playing"
        const val EXTRA_PROGRESS       = "widget_progress"
        const val EXTRA_ART_URI        = "widget_art_uri"

        // Action strings for button PendingIntents
        const val ACTION_PLAY_PAUSE = "com.enigma.dreamer.WIDGET_PLAY_PAUSE"
        const val ACTION_NEXT       = "com.enigma.dreamer.WIDGET_NEXT"
        const val ACTION_PREVIOUS   = "com.enigma.dreamer.WIDGET_PREVIOUS"

        /**
         * Called by [MusicService] whenever playback state changes.
         * Sends a broadcast that [DreamerWidget.onReceive] picks up.
         */
        fun push(
            context:   Context,
            title:     String,
            artist:    String,
            isPlaying: Boolean,
            progress:  Float,
            artUri:    Uri?
        ) {
            val intent = Intent(ACTION_WIDGET_UPDATE).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_TITLE,    title)
                putExtra(EXTRA_ARTIST,   artist)
                putExtra(EXTRA_PLAYING,  isPlaying)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_ART_URI,  artUri?.toString())
            }
            context.sendBroadcast(intent)
        }

        private fun updateWidget(
            context:          Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId:      Int,
            state:            WidgetState
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_dreamer)

            // ── Text ──────────────────────────────────────────────────────────
            views.setTextViewText(R.id.widget_title,  state.title.ifBlank { "Nothing playing" })
            views.setTextViewText(R.id.widget_artist, state.artist)

            // ── Play/Pause icon ───────────────────────────────────────────────
            views.setImageViewResource(
                R.id.widget_play_pause,
                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )

            // ── Album art ─────────────────────────────────────────────────────
            val art = state.artUri?.let { loadArtBitmap(context, it) }
            if (art != null) {
                views.setImageViewBitmap(R.id.widget_art, art)
            } else {
                views.setImageViewResource(R.id.widget_art, R.drawable.ic_default_album_art)
            }

            // ── Progress: set weight on progress bar view ─────────────────────
            // RemoteViews doesn't support layout_weight directly; we use a
            // fixed-width spacer approach or setProgressBar on a ProgressBar view.
            views.setProgressBar(R.id.widget_progress, 1000, (state.progress * 1000).toInt(), false)

            // ── Button PendingIntents ─────────────────────────────────────────
            views.setOnClickPendingIntent(
                R.id.widget_play_pause,
                broadcastPendingIntent(context, ACTION_PLAY_PAUSE)
            )
            views.setOnClickPendingIntent(
                R.id.widget_next,
                broadcastPendingIntent(context, ACTION_NEXT)
            )
            views.setOnClickPendingIntent(
                R.id.widget_previous,
                broadcastPendingIntent(context, ACTION_PREVIOUS)
            )

            // ── Tap widget body → open NowPlayingScreen ───────────────────────
            val tapIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_now_playing", true)
            }
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context, 1, tapIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /**
         * Loads album art at ≤ 128×128 px to avoid OOM in the widget process.
         * Widget process has very limited heap; full-res decode would crash it.
         */
        private fun loadArtBitmap(context: Context, uri: Uri): Bitmap? = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                // Decode bounds first
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, bounds)
                context.contentResolver.openInputStream(uri)?.use { stream2 ->
                    val sample = calculateSampleSize(bounds, 128, 128)
                    BitmapFactory.decodeStream(stream2, null,
                        BitmapFactory.Options().apply { inSampleSize = sample }
                    )
                }
            }
        }.getOrNull()

        private fun calculateSampleSize(
            options: BitmapFactory.Options,
            reqW: Int,
            reqH: Int
        ): Int {
            var s = 1
            if (options.outHeight > reqH || options.outWidth > reqW) {
                val hh = options.outHeight / 2
                val hw = options.outWidth  / 2
                while (hh / s >= reqH && hw / s >= reqW) s *= 2
            }
            return s
        }

        private fun broadcastPendingIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(action).setPackage(context.packageName)
            return PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    data class WidgetState(
        val title:     String  = "Nothing playing",
        val artist:    String  = "",
        val isPlaying: Boolean = false,
        val progress:  Float   = 0f,
        val artUri:    Uri?    = null
    )
}
