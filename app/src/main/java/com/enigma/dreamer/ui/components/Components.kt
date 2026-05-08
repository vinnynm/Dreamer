package com.enigma.dreamer.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.enigma.devlyric.core.*
import com.enigma.dreamer.R
import com.enigma.dreamer.ui.theme.*
import androidx.core.animation.*
import com.enigma.dreamer.core.Song


@Composable
fun AlbumArtwork(
    song: Song,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Surface3),
        contentAlignment = Alignment.Center
    ) {
        if (song?.albumArtUri != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(song.albumArtUri)
                    .crossfade(true)
                    // Use default album art drawable as placeholder AND error fallback
                    .placeholder(com.enigma.dreamer.R.drawable.ic_default_album_art)
                    .error(com.enigma.dreamer.R.drawable.ic_default_album_art)
                    .build(),
                contentDescription = "Album art for ${song.title}",
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize()
            )
        } else {
            // No URI at all — show default art directly
            Image(
                painter = painterResource(R.drawable.ic_default_album_art),
                contentDescription = "Default album art",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ── Playback Slider ───────────────────────────────────────────────────────────

@Composable
fun PlaybackSlider(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    fgColor: Color = Color.White
) {
    val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    Column(modifier = modifier) {
        Slider(
            value         = progress,
            onValueChange = { onSeek((it * durationMs).toLong()) },
            colors        = SliderDefaults.colors(
                thumbColor         = Amber,
                activeTrackColor   = Amber,
                inactiveTrackColor = Surface3
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatDuration(positionMs),
                style = MaterialTheme.typography.bodySmall,
                color = fgColor.copy(alpha = 0.65f))
            Text(formatDuration(durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = fgColor.copy(alpha = 0.65f))
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val mins     = totalSec / 60
    val secs     = totalSec % 60
    return "%d:%02d".format(mins, secs)
}

// ── Playback Controls ─────────────────────────────────────────────────────────

@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    isPreparing: Boolean,
    hasNext: Boolean,
    hasPrevious: Boolean,
    repeatMode: com.enigma.dreamer.core.RepeatMode,
    shuffleMode: com.enigma.dreamer.core.ShuffleMode,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Shuffle
        IconButton(onClick = onToggleShuffle) {
            Icon(
                Icons.Filled.Shuffle, "Shuffle",
                tint     = if (shuffleMode == com.enigma.dreamer.core.ShuffleMode.ON) Amber else TextMuted,
                modifier = Modifier.size(24.dp)
            )
        }
        // Previous
        IconButton(onClick = onPrevious, enabled = hasPrevious) {
            Icon(Icons.Filled.SkipPrevious, "Previous",
                tint     = if (hasPrevious) TextPrimary else TextMuted,
                modifier = Modifier.size(36.dp))
        }
        // Play / Pause — shows spinner while buffering
        Box(
            modifier          = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Amber)
                .clickable(enabled = !isPreparing, onClick = onPlayPause),
            contentAlignment  = Alignment.Center
        ) {
            if (isPreparing) {
                CircularProgressIndicator(
                    color       = Amoled,
                    strokeWidth = 2.5.dp,
                    modifier    = Modifier.size(32.dp)
                )
            } else {
                val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
                    initialValue   = 1f,
                    targetValue    = if (isPlaying) 1.06f else 1f,
                    animationSpec  = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                    label          = "scale"
                )
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint     = Amoled,
                    modifier = Modifier.size(36.dp).scale(pulse)
                )
            }
        }
        // Next
        IconButton(onClick = onNext, enabled = hasNext) {
            Icon(Icons.Filled.SkipNext, "Next",
                tint     = if (hasNext) TextPrimary else TextMuted,
                modifier = Modifier.size(36.dp))
        }
        // Repeat
        val (repeatIcon, repeatTint) = when (repeatMode) {
            com.enigma.dreamer.core.RepeatMode.NONE -> Icons.Filled.Repeat    to TextMuted
            com.enigma.dreamer.core.RepeatMode.ALL  -> Icons.Filled.Repeat    to Amber
            com.enigma.dreamer.core.RepeatMode.ONE  -> Icons.Filled.RepeatOne to Amber
            else -> {Icons.Filled.RepeatOne to Amber}
        }
        IconButton(onClick = onToggleRepeat) {
            Icon(repeatIcon, "Repeat", tint = repeatTint, modifier = Modifier.size(24.dp))
        }
    }
}

// ── Song List Item ────────────────────────────────────────────────────────────

@Composable
fun SongListItem(
    song: Song,
    isPlaying: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val bgColor by animateColorAsState(
        if (isPlaying) Surface3 else Color.Transparent, label = "bg"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment         = Alignment.CenterVertically,
        horizontalArrangement     = Arrangement.spacedBy(14.dp)
    ) {
        AlbumArtwork(song, size = 50.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                style      = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                color      = if (isPlaying) Amber else TextPrimary,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
            Text(
                "${song.artist} · ${song.album}",
                style    = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isPlaying) NowPlayingIndicator()
        trailingContent?.invoke()
    }
}

// ── Animated Now Playing Bars ────────────────────────────────────────────────

@Composable
fun NowPlayingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "bars")
    val heights    = (0..2).map { i ->
        transition.animateFloat(
            initialValue  = 0.2f,
            targetValue   = 1f,
            animationSpec = infiniteRepeatable(
                tween(400 + i * 80, easing = FastOutSlowInEasing),
                RepeatMode.Reverse
            ),
            label = "bar$i"
        )
    }
    Row(
        modifier  = modifier.height(20.dp),
        verticalAlignment         = Alignment.Bottom,
        horizontalArrangement     = Arrangement.spacedBy(2.dp)
    ) {
        heights.forEach { h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(h.value)
                    .background(Amber, RoundedCornerShape(2.dp))
            )
        }
    }
}

// ── Lyric Line ────────────────────────────────────────────────────────────────

@Composable
fun LyricLineItem(
    text: String,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(if (isActive) 1.06f else 1f, label = "scale")
    val color by animateColorAsState(if (isActive) LyricActive else LyricInactive, label = "color")
    Text(
        text       = text,
        modifier   = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .scale(scale),
        color      = color,
        style      = if (isActive) MaterialTheme.typography.titleMedium
        else MaterialTheme.typography.bodyMedium,
        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
        textAlign  = TextAlign.Center
    )
}

// ── Mini Player ───────────────────────────────────────────────────────────────

@Composable
fun MiniPlayer(
    song:Song,
    isPlaying: Boolean,
    progress: Float,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Surface2)
            .clickable(onClick = onClick)
    ) {
        // Progress line at top
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(2.dp)
                .background(Amber)
                .align(Alignment.TopStart)
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AlbumArtwork(song, size = 46.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(song.artist, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextSecondary)
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint     = Amber,
                    modifier = Modifier.size(28.dp)
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.SkipNext, "Next",
                    tint     = TextSecondary,
                    modifier = Modifier.size(28.dp))
            }
        }
    }
}
