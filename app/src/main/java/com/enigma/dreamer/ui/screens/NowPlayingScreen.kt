package com.enigma.dreamer.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.enigma.dreamer.R
import com.enigma.dreamer.core.BufferingState
import com.enigma.dreamer.core.PlaybackState
import com.enigma.dreamer.core.RepeatMode as AppRepeatMode
import com.enigma.dreamer.core.Song
import com.enigma.dreamer.ui.components.AlbumArtwork
import com.enigma.dreamer.ui.components.LyricLineItem
import com.enigma.dreamer.ui.components.PlaybackControls
import com.enigma.dreamer.ui.components.PlaybackSlider
import com.enigma.dreamer.ui.components.formatDuration
import com.enigma.dreamer.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playbackState: PlaybackState,
    currentLyricLine: Int,
    showLyrics: Boolean,
    showQueue: Boolean,
    dominantColor: Int = 0xFF0D0D0D.toInt(),
    accentTextColor: Int = 0xFFEEEEEE.toInt(),
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleLyrics: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleQueue: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onStartSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    onSkipToQueue: (Int) -> Unit
) {
    val song        = playbackState.currentSong ?: return
    val lyricsState = rememberLazyListState()
    val isPreparing = playbackState.bufferingState == BufferingState.PREPARING

    val bgComposeColor = Color(dominantColor)
    val fgComposeColor = Color(accentTextColor)

    val bgColor by animateColorAsState(
        targetValue   = bgComposeColor,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label         = "bgColor"
    )

    var showOptionsMenu by remember { mutableStateOf(false) }

    LaunchedEffect(currentLyricLine) {
        if (showLyrics && currentLyricLine >= 0) {
            lyricsState.animateScrollToItem((currentLyricLine - 3).coerceAtLeast(0))
        }
    }

    // "Next up" song — the item after the current queue index
    val nextSong: Song? = remember(playbackState.queue, playbackState.queueIndex) {
        playbackState.queue.getOrNull(playbackState.queueIndex + 1)
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Layer 1: blurred album art atmosphere ─────────────────────────────
        // Renders the album art at full screen size with a heavy blur so the
        // dominant hues bleed into the background without distracting from the
        // content. The gradient overlay on Layer 2 controls opacity.
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
                .fillMaxSize()
                .blur(60.dp)           // heavy blur — only color/mood leaks through
        )

        // ── Layer 2: dominant-color gradient overlay ──────────────────────────
        // Sits on top of the blur. The gradient is dark at top and bottom so
        // controls are always legible regardless of album art color.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to bgColor.copy(alpha = 0.88f),
                        0.35f to bgColor.copy(alpha = 0.72f),
                        0.65f to bgColor.copy(alpha = 0.72f),
                        1.00f to Amoled.copy(alpha = 0.97f)
                    )
                )
        )

        // ── Layer 3: Queue overlay ────────────────────────────────────────────
        AnimatedVisibility(
            visible = showQueue,
            enter   = slideInVertically { it },
            exit    = slideOutVertically { it }
        ) {
            QueueScreen(
                queue        = playbackState.queue,
                currentIndex = playbackState.queueIndex,
                onSkipTo     = onSkipToQueue,
                onClose      = onToggleQueue
            )
        }

        // ── Layer 4: main content ─────────────────────────────────────────────
        if (!showQueue) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {

                // ── Top bar ───────────────────────────────────────────────────
                Row(
                    modifier          = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown, "Back",
                            tint     = fgComposeColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Text(
                        "NOW PLAYING",
                        style     = MaterialTheme.typography.labelSmall,
                        color     = fgComposeColor.copy(alpha = 0.5f),
                        modifier  = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                    val favTint by animateColorAsState(
                        if (song.isFavorite) Amber else fgComposeColor.copy(alpha = 0.6f),
                        label = "fav"
                    )
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            if (song.isFavorite) Icons.Filled.Favorite
                            else Icons.Filled.FavoriteBorder,
                            "Favourite",
                            tint     = favTint,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = onToggleLyrics) {
                        Icon(
                            Icons.Filled.Lyrics, "Lyrics",
                            tint     = if (showLyrics) Amber else fgComposeColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Box {
                        IconButton(onClick = { showOptionsMenu = true }) {
                            Icon(
                                Icons.Filled.MoreVert, "Options",
                                tint     = fgComposeColor.copy(alpha = 0.6f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        OptionsDropdown(
                            expanded            = showOptionsMenu,
                            onDismiss           = { showOptionsMenu = false },
                            currentSpeed        = playbackState.playbackSpeed,
                            sleepTimerActive    = playbackState.sleepTimer.isActive,
                            sleepTimerRemaining = playbackState.sleepTimer.remainingMs,
                            onSpeedChange       = { onSpeedChange(it); showOptionsMenu = false },
                            onStartSleepTimer   = { onStartSleepTimer(it); showOptionsMenu = false },
                            onCancelSleepTimer  = { onCancelSleepTimer(); showOptionsMenu = false }
                        )
                    }
                }

                // ── Artwork or Lyrics ─────────────────────────────────────────
                if (!showLyrics || song.lyricDocument == null) {
                    Spacer(Modifier.weight(0.3f))

                    val artScale by animateFloatAsState(
                        if (playbackState.isPlaying) 1f else 0.88f,
                        animationSpec = spring(Spring.DampingRatioMediumBouncy),
                        label         = "artScale"
                    )

                    Box(
                        modifier         = Modifier
                            .align(Alignment.CenterHorizontally)
                            .scale(artScale),
                        contentAlignment = Alignment.Center
                    ) {
                        val artShape = RoundedCornerShape(16.dp)

                        // Glow shadow
                        Box(
                            modifier = Modifier
                                .size(260.dp)
                                .shadow(
                                    elevation    = if (playbackState.isPlaying) 32.dp else 10.dp,
                                    shape        = artShape,
                                    ambientColor = bgColor,
                                    spotColor    = bgColor
                                )
                        )

                        // ── Vinyl disc (frozen-angle) ─────────────────────────
                        var frozenAngle by remember { mutableFloatStateOf(0f) }
                        val infiniteTransition = rememberInfiniteTransition(label = "vinylSpin")
                        val liveAngle by infiniteTransition.animateFloat(
                            initialValue  = 0f,
                            targetValue   = 360f,
                            animationSpec = infiniteRepeatable(
                                animation  = tween(3000, easing = LinearEasing),
                                repeatMode = androidx.compose.animation.core.RepeatMode.Restart
                            ),
                            label = "diskLiveAngle"
                        )
                        LaunchedEffect(playbackState.isPlaying, liveAngle) {
                            if (playbackState.isPlaying) frozenAngle = liveAngle
                        }
                        val displayAngle = if (playbackState.isPlaying) liveAngle else frozenAngle

                        Box(
                            modifier = Modifier
                                .size(268.dp)
                                .rotate(displayAngle)
                                .background(Color(0xFF111111), CircleShape)
                                .border(0.5.dp, Color.White.copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val center = size.center
                                val radius = size.minDimension / 2
                                for (i in 1..8) {
                                    drawCircle(
                                        color  = Color.White.copy(alpha = 0.03f),
                                        radius = radius * (0.4f + i * 0.07f),
                                        center = center,
                                        style  = Stroke(width = 1.dp.toPx())
                                    )
                                }
                            }
                        }

                        AlbumArtwork(
                            song     = song,
                            size     = 272.dp,
                            shape    = artShape,
                            modifier = Modifier.border(
                                1.dp, Color.White.copy(alpha = 0.1f), artShape
                            )
                        )

                        if (isPreparing) {
                            CircularProgressIndicator(
                                color       = Amber,
                                strokeWidth = 3.dp,
                                modifier    = Modifier.size(48.dp)
                            )
                        }
                    }

                    Spacer(Modifier.weight(0.3f))
                } else {
                    // ── Lyric player ──────────────────────────────────────────
                    LazyColumn(
                        state               = lyricsState,
                        modifier            = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding      = PaddingValues(vertical = 80.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        itemsIndexed(song.lyricDocument!!.lines) { idx, line ->
                            LyricLineItem(
                                text     = line.text,
                                isActive = idx == currentLyricLine
                            )
                        }
                    }
                }

                // ── Song info ─────────────────────────────────────────────────
                Column(
                    modifier            = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AnimatedContent(targetState = song.title, label = "title") { title ->
                        Text(
                            title,
                            style      = MaterialTheme.typography.displayMedium,
                            color      = fgComposeColor,
                            fontWeight = FontWeight.Bold,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                            textAlign  = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        song.artist,
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = fgComposeColor.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(20.dp))

                // ── Seek slider ───────────────────────────────────────────────
                PlaybackSlider(
                    positionMs = playbackState.positionMs,
                    durationMs = playbackState.durationMs,
                    onSeek     = onSeek,
                    fgColor    = fgComposeColor,
                    modifier   = Modifier.padding(horizontal = 20.dp)
                )

                Spacer(Modifier.height(6.dp))

                // ── Transport controls ────────────────────────────────────────
                PlaybackControls(
                    isPlaying       = playbackState.isPlaying,
                    isPreparing     = isPreparing,
                    hasNext         = playbackState.hasNext,
                    hasPrevious     = playbackState.hasPrevious,
                    repeatMode      = playbackState.repeatMode,
                    shuffleMode     = playbackState.shuffleMode,
                    onPlayPause     = onPlayPause,
                    onNext          = onNext,
                    onPrevious      = onPrevious,
                    onToggleRepeat  = onToggleRepeat,
                    onToggleShuffle = onToggleShuffle,
                    modifier        = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(Modifier.height(8.dp))

                // ── Speed / Sleep chips + Queue button ────────────────────────
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (playbackState.playbackSpeed != 1.0f) {
                        AssistChip(
                            onClick = { showOptionsMenu = true },
                            label   = {
                                Text(
                                    "${playbackState.playbackSpeed}×",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Speed, null,
                                    modifier = Modifier.size(14.dp),
                                    tint     = Amber
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = AmberDim, labelColor = Amber
                            )
                        )
                    } else {
                        Spacer(Modifier.size(1.dp))
                    }

                    if (playbackState.sleepTimer.isActive) {
                        val rem  = playbackState.sleepTimer.remainingMs
                        val mins = rem / 60000
                        val secs = (rem % 60000) / 1000
                        AssistChip(
                            onClick = { showOptionsMenu = true },
                            label   = {
                                Text(
                                    "Sleep %d:%02d".format(mins, secs),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Bedtime, null,
                                    modifier = Modifier.size(14.dp),
                                    tint     = Amber
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = AmberDim, labelColor = Amber
                            )
                        )
                    }

                    IconButton(onClick = onToggleQueue) {
                        Icon(
                            Icons.AutoMirrored.Filled.QueueMusic, "Queue",
                            tint     = fgComposeColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // ── Next Up peek ──────────────────────────────────────────────
                // Shows the upcoming song so the user knows what's coming next.
                // Mirrors the DreamMusic pattern but styled to match Dreamer.
                AnimatedVisibility(
                    visible = nextSong != null && !showLyrics,
                    enter   = fadeIn() + expandVertically(),
                    exit    = fadeOut() + shrinkVertically()
                ) {
                    nextSong?.let { next ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .padding(bottom = 8.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.07f))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "NEXT",
                                style    = MaterialTheme.typography.labelSmall,
                                color    = fgComposeColor.copy(alpha = 0.45f),
                                modifier = Modifier.width(36.dp)
                            )
                            AlbumArtwork(
                                song  = next,
                                size  = 36.dp,
                                shape = RoundedCornerShape(6.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    next.title,
                                    style    = MaterialTheme.typography.bodyMedium,
                                    color    = fgComposeColor.copy(alpha = 0.85f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    next.artist,
                                    style    = MaterialTheme.typography.bodySmall,
                                    color    = fgComposeColor.copy(alpha = 0.45f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                formatDuration(next.duration),
                                style = MaterialTheme.typography.bodySmall,
                                color = fgComposeColor.copy(alpha = 0.4f)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ── Options Dropdown ──────────────────────────────────────────────────────────

@Composable
private fun OptionsDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    currentSpeed: Float,
    sleepTimerActive: Boolean,
    sleepTimerRemaining: Long,
    onSpeedChange: (Float) -> Unit,
    onStartSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit
) {
    DropdownMenu(
        expanded         = expanded,
        onDismissRequest = onDismiss,
        containerColor   = Surface2
    ) {
        DropdownMenuItem(
            text    = {
                Text(
                    "Playback Speed",
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber
                )
            },
            onClick = {},
            enabled = false
        )
        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
            DropdownMenuItem(
                text = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        if (currentSpeed == speed)
                            Icon(
                                Icons.Filled.Check, null,
                                tint     = Amber,
                                modifier = Modifier.size(16.dp)
                            )
                        else
                            Spacer(Modifier.size(16.dp))
                        Text(
                            "${speed}×",
                            color = if (currentSpeed == speed) Amber else TextPrimary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                },
                onClick = { onSpeedChange(speed) }
            )
        }

        HorizontalDivider(color = Surface3, modifier = Modifier.padding(vertical = 4.dp))

        DropdownMenuItem(
            text    = {
                Text(
                    "Sleep Timer",
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber
                )
            },
            onClick = {},
            enabled = false
        )
        if (sleepTimerActive) {
            val mins = sleepTimerRemaining / 60000
            val secs = (sleepTimerRemaining % 60000) / 1000
            DropdownMenuItem(
                text = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Bedtime, null,
                            tint     = Amber,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            "Cancel (%d:%02d)".format(mins, secs),
                            color = ErrorRed,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                },
                onClick = onCancelSleepTimer
            )
        } else {
            listOf(5, 10, 15, 20, 30, 45, 60).forEach { minutes ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "$minutes minutes",
                            color = TextPrimary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = { onStartSleepTimer(minutes) }
                )
            }
        }
    }
}
