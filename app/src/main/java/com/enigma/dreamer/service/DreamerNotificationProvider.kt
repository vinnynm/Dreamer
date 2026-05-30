package com.enigma.dreamer.service

import android.content.Context
import android.graphics.Color
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Extends [androidx.media3.session.DefaultMediaNotificationProvider] to add:
 *
 *  1. **Album art color** — [setAccentColor] stores the dominant color derived
 *     from the current album art. It is applied via [setColor] + [setColorized]
 *     on every notification build, giving the notification a tinted background
 *     on older Android versions and a colored accent on newer ones.
 *
 *  2. **Color reset on track change** — [MusicService] calls
 *     `setAccentColor(Color.parseColor("#1A1A1A"))` in `onMediaItemTransition`
 *     so stale colors don't bleed into the next track before art loads.
 *
 * The parent class handles all the hard stuff:
 *  - MediaStyle with prev / play-pause / next in compact view
 *  - [MediaMetadata.artworkData] → large icon (set by [MusicService.updateAlbumArt])
 *  - Lock-screen visibility
 *  - Play/pause ongoing state
 *  - Notification ID management
 *
 * ### Why not a fully custom provider?
 * [androidx.media3.session.DefaultMediaNotificationProvider] uses internal Media3 APIs to resolve
 * [androidx.media3.session.CommandButton] → [androidx.core.app.NotificationCompat.Action] correctly across API levels.
 * Rolling our own from scratch means duplicating that logic and chasing Media3
 * releases. Extending it and patching only the builder is the right trade-off.
 */
@OptIn(UnstableApi::class)
class DreamerNotificationProvider(
    context: Context,
    channelId: String
) : DefaultMediaNotificationProvider(
    context,
    /* notificationIdProvider = */ { NOTIF_ID.get() },
    channelId,
    /* channelNameResourceId  = */ 0   // we create the channel ourselves in MusicService
) {

    @Volatile private var accentColor: Int = Color.parseColor("#1A1A1A")

    /** Called by [MusicService.updateAlbumArt] after computing the dominant color. */
    fun setAccentColor(color: Int) {
        accentColor = color
    }

    /**
     * [DefaultMediaNotificationProvider] calls this after it has built the base
     * notification (with title, artist, art, actions). We get the builder back and
     * apply color + colorized here.
     *
     * Note: [addNotificationActions] is the correct override point — do NOT
     * override [createNotification] directly because the parent does important
     * foreground-service / ongoing bookkeeping inside it.
     */
    override fun addNotificationActions(
        mediaSession: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory
    ): IntArray {
        // Let the parent add prev / play-pause / next actions first
        val compactViewIndices = super.addNotificationActions(
            mediaSession, mediaButtons, builder, actionFactory
        )

        // Apply accent color so the notification background is tinted with the
        // album art's dominant color instead of the system default (white).
        builder
            .setColor(accentColor)
            .setColorized(true)

        return compactViewIndices
    }

    companion object {
        private val NOTIF_ID = AtomicInteger(1001)
    }
}