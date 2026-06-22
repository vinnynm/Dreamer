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
 * Custom notification provider for Dreamer.
 *
 * Extends [DefaultMediaNotificationProvider] so we get all the hard stuff for
 * free — MediaStyle layout, prev/play-pause/next in compact view, lock-screen
 * visibility, artwork from [MediaMetadata.artworkData], ongoing/dismissible
 * state — and we only patch the parts we own:
 *
 *  1. **Album-art accent color** — [setAccentColor] stores the dominant color
 *     derived from the current album art. Applied unconditionally via
 *     [setColor] + [setColorized] on every notification build, so the
 *     background is always tinted even before art loads (defaults to a deep
 *     charcoal rather than the harsh system white).
 *
 *  2. **Safe channel name** — the parent constructor receives a string resource
 *     ID for the channel name. Passing `0` causes a crash on some OEMs because
 *     they try to resolve the resource before creating the channel. We pass the
 *     real string resource instead.
 *
 *  3. **Compact view** — we explicitly return [0, 1, 2] (prev, play/pause,
 *     next) so all three controls appear on the lock screen and in the pull-
 *     down shade without the user having to expand the notification.
 *
 * ### Why extend rather than build from scratch?
 * [DefaultMediaNotificationProvider] uses internal Media3 APIs to resolve
 * [CommandButton] → [NotificationCompat.Action] correctly across API levels,
 * handles foreground/ongoing state, and stays in sync with Media3 releases.
 * Rolling our own means duplicating that logic forever.
 */
@OptIn(UnstableApi::class)
class DreamerNotificationProvider(
    private val context: Context,
    channelId: String
) : DefaultMediaNotificationProvider(
    context,
    /* notificationIdProvider = */ { NOTIF_ID.get() },
    channelId,
    // FIX: was 0 — passing 0 as a resource ID causes a Resources.NotFoundException
    // on several OEM builds (Samsung, Xiaomi) because they call
    // context.getString(0) before creating the channel.
    // R.string.notification_channel_name must exist in res/values/strings.xml.
    // If you haven't added it yet, add:
    //   <string name="notification_channel_name">Now Playing</string>
    com.enigma.dreamer.R.string.notification_channel_name
) {

    // Default: deep charcoal so the notification never shows as stark white
    // before album art loads.  Updated by MusicService whenever art changes.
    @Volatile private var accentColor: Int = Color.parseColor("#1A1A1A")

    /**
     * Called by [MusicService.updateAlbumArt] after the ViewModel has computed
     * the dominant color from the current album art bitmap.
     *
     * Thread-safe: [accentColor] is @Volatile so reads from the notification
     * builder thread always see the latest value.
     */
    fun setAccentColor(color: Int) {
        accentColor = color
    }

    /**
     * Override point provided by [DefaultMediaNotificationProvider].
     *
     * The parent has already populated [builder] with:
     *   - title (song name), text (artist), sub-text (album)
     *   - large icon (from [MediaMetadata.artworkData] set by MusicService)
     *   - prev / play-pause / next actions
     *   - MediaStyle attached to the active [MediaSession]
     *   - lock-screen visibility = PUBLIC
     *
     * We add color on top and return the compact-view action indices.
     */
    override fun addNotificationActions(
        mediaSession: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory
    ): IntArray {
        // Let the parent wire up all the transport actions first
        val parentIndices = super.addNotificationActions(
            mediaSession, mediaButtons, builder, actionFactory
        )

        // Apply the album-art dominant color unconditionally.
        // setColorized(true) tells the system to use setColor() as the
        // notification background on Android 8+ and as the icon tint on older
        // versions. Without setColorized the color has no visible effect.
        builder
            .setColor(accentColor)
            .setColorized(true)

        // Return [0, 1, 2] so prev, play/pause, and next all show in the
        // compact / lock-screen view.  The parent may return fewer indices if
        // it detected only two actions; we override to guarantee three.
        //
        // Safe even when the queue has only one song — Media3 disables the
        // prev/next buttons via CommandButton.isEnabled rather than hiding them.
        return if (parentIndices.size >= 3) parentIndices
        else intArrayOf(0, 1, 2)
    }

    companion object {
        // Stable notification ID used for the foreground service notification.
        // AtomicInteger lets us change it in future without data races.
        private val NOTIF_ID = AtomicInteger(1001)
    }
}
