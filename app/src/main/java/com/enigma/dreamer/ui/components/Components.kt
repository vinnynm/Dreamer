package com.enigma.dreamer.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.enigma.dreamer.R
import com.enigma.dreamer.core.Song
import com.enigma.dreamer.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

// ── Album Artwork ─────────────────────────────────────────────────────────────

@Composable
fun AlbumArtwork(
    song: Song,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    shape: Shape = RoundedCornerShape(12.dp)
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Surface3),
        contentAlignment = Alignment.Center
    ) {
        if (song.albumArtUri != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(song.albumArtUri)
                    .crossfade(true)
                    .placeholder(R.drawable.ic_default_album_art)
                    .error(R.drawable.ic_default_album_art)
                    .build(),
                contentDescription = "Album art for ${song.title}",
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize()
            )
        } else {
            Image(
                painter            = painterResource(R.drawable.ic_default_album_art),
                contentDescription = "Default album art",
                modifier           = Modifier.fillMaxSize()
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
            modifier              = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                formatDuration(positionMs),
                style = MaterialTheme.typography.bodySmall,
                color = fgColor.copy(alpha = 0.65f)
            )
            Text(
                formatDuration(durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = fgColor.copy(alpha = 0.65f)
            )
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
        modifier              = modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        IconButton(onClick = onToggleShuffle) {
            Icon(
                Icons.Filled.Shuffle, "Shuffle",
                tint     = if (shuffleMode == com.enigma.dreamer.core.ShuffleMode.ON) Amber else TextMuted,
                modifier = Modifier.size(24.dp)
            )
        }
        IconButton(onClick = onPrevious, enabled = hasPrevious) {
            Icon(
                Icons.Filled.SkipPrevious, "Previous",
                tint     = if (hasPrevious) TextPrimary else TextMuted,
                modifier = Modifier.size(36.dp)
            )
        }
        Box(
            modifier         = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Amber)
                .clickable(enabled = !isPreparing, onClick = onPlayPause),
            contentAlignment = Alignment.Center
        ) {
            if (isPreparing) {
                CircularProgressIndicator(
                    color       = Amoled,
                    strokeWidth = 2.5.dp,
                    modifier    = Modifier.size(32.dp)
                )
            } else {
                val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
                    initialValue  = 1f,
                    targetValue   = if (isPlaying) 1.06f else 1f,
                    animationSpec = infiniteRepeatable(
                        tween(900),
                        androidx.compose.animation.core.RepeatMode.Reverse
                    ),
                    label = "scale"
                )
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint     = Amoled,
                    modifier = Modifier
                        .size(36.dp)
                        .scale(pulse)
                )
            }
        }
        IconButton(onClick = onNext, enabled = hasNext) {
            Icon(
                Icons.Filled.SkipNext, "Next",
                tint     = if (hasNext) TextPrimary else TextMuted,
                modifier = Modifier.size(36.dp)
            )
        }
        val (repeatIcon, repeatTint) = when (repeatMode) {
            com.enigma.dreamer.core.RepeatMode.NONE -> Icons.Filled.Repeat    to TextMuted
            com.enigma.dreamer.core.RepeatMode.ALL  -> Icons.Filled.Repeat    to Amber
            com.enigma.dreamer.core.RepeatMode.ONE  -> Icons.Filled.RepeatOne to Amber
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
    trailingContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        if (isPlaying) Surface3 else Color.Transparent, label = "bg"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
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

// ── Animated Now Playing Bars ─────────────────────────────────────────────────

@Composable
fun NowPlayingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "bars")
    val heights    = (0..2).map { i ->
        transition.animateFloat(
            initialValue  = 0.2f,
            targetValue   = 1f,
            animationSpec = infiniteRepeatable(
                tween(400 + i * 80, easing = FastOutSlowInEasing),
                androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "bar$i"
        )
    }
    Row(
        modifier              = modifier.height(20.dp),
        verticalAlignment     = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
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
    val color by animateColorAsState(
        if (isActive) LyricActive else LyricInactive, label = "color"
    )
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

/**
 * 8.2 — Swipe gesture visual hints.
 *
 * Previously the card gave physical drag feedback (it translated with the
 * finger) but showed no indicator of what a swipe *would do*. Users had
 * no affordance to discover the gesture.
 *
 * Now:
 *  - A faint left-arrow (◀) fades in on the left edge as the user drags right
 *    (towards previous track). Its alpha scales with drag distance so it only
 *    appears when the gesture is intentional.
 *  - A faint right-arrow (▶) fades in on the right edge as the user drags left
 *    (towards next track), symmetrically.
 *  - Both arrows are gone at rest so they don't clutter the idle UI.
 *  - The swipe-right → previous action was wired in Phase 5 (FIX B-5).
 */
@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    progress: Float,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit = {},
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dominantColor: Color = Surface2
) {
    val scope          = rememberCoroutineScope()
    val swipeThreshold = 120f

    var dragOffset by remember { mutableFloatStateOf(0f) }

    val animatedDragOffset by animateFloatAsState(
        targetValue   = dragOffset,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessMediumLow
        ),
        label = "dragFeedback"
    )

    // ── Frozen-angle vinyl spin ───────────────────────────────────────────────
    var frozenAngle by remember { mutableFloatStateOf(0f) }

    val infiniteTransition = rememberInfiniteTransition(label = "miniVinyl")
    val liveAngle by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 360f,
        animationSpec = infiniteRepeatable(
            animation  = tween(8000, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "miniVinylAngle"
    )

    LaunchedEffect(isPlaying, liveAngle) {
        if (isPlaying) frozenAngle = liveAngle
    }
    val displayAngle = if (isPlaying) liveAngle else frozenAngle

    val bgColor by animateColorAsState(
        targetValue   = dominantColor,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label         = "miniPlayerBg"
    )

    val bgColorLight = Color(
        red   = (bgColor.red   + 0.06f).coerceAtMost(1f),
        green = (bgColor.green + 0.05f).coerceAtMost(1f),
        blue  = (bgColor.blue  + 0.08f).coerceAtMost(1f),
        alpha = bgColor.alpha
    )

    // 8.2: Arrow hint alpha — scales 0→1 as drag approaches threshold.
    // leftArrowAlpha  shows when dragging right (→ previous)
    // rightArrowAlpha shows when dragging left  (→ next)
    val leftArrowAlpha  = (animatedDragOffset / swipeThreshold).coerceIn(0f, 1f)
    val rightArrowAlpha = (-animatedDragOffset / swipeThreshold).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .offset { IntOffset(animatedDragOffset.toInt(), 0) }
            .shadow(12.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(listOf(bgColor, bgColorLight)))
            .pointerInput(song.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            dragOffset < -swipeThreshold -> {
                                scope.launch { onNext(); dragOffset = 0f }
                            }
                            dragOffset > swipeThreshold -> {
                                scope.launch { onPrevious(); dragOffset = 0f }
                            }
                            else -> dragOffset = 0f
                        }
                    },
                    onDragCancel     = { dragOffset = 0f },
                    onHorizontalDrag = { _, delta ->
                        dragOffset = (dragOffset + delta * 0.6f).coerceIn(-200f, 200f)
                    }
                )
            }
            .clickable(onClick = onClick)
    ) {
        // Progress bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(2.dp)
                .background(Amber.copy(alpha = 0.9f))
        )

        // 8.2: Left arrow hint — fades in when swiping right (previous)
        if (leftArrowAlpha > 0.01f) {
            Icon(
                imageVector        = Icons.Filled.SkipPrevious,
                contentDescription = null,
                tint               = Color.White.copy(alpha = leftArrowAlpha * 0.75f),
                modifier           = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 10.dp)
                    .size(20.dp)
            )
        }

        // 8.2: Right arrow hint — fades in when swiping left (next)
        if (rightArrowAlpha > 0.01f) {
            Icon(
                imageVector        = Icons.Filled.SkipNext,
                contentDescription = null,
                tint               = Color.White.copy(alpha = rightArrowAlpha * 0.75f),
                modifier           = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp)
                    .size(20.dp)
            )
        }

        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Spinning vinyl disc ───────────────────────────────────────────
            Box(
                modifier         = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f))
                )
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(song.albumArtUri)
                        .crossfade(true)
                        .placeholder(R.drawable.ic_default_album_art)
                        .error(R.drawable.ic_default_album_art)
                        .build(),
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .rotate(displayAngle)
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(bgColor)
                )
            }

            // ── Song info ─────────────────────────────────────────────────────
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    song.title,
                    style      = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color      = Color.White,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    song.artist,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ── Transport controls ────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Amber)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint     = Amoled,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(onClick = onNext) {
                Icon(
                    Icons.Filled.SkipNext, "Next",
                    tint     = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}
