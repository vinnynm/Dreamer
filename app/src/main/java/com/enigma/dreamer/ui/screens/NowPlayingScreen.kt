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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.*
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.enigma.devlyric.core.LyricDocument
import com.enigma.devlyric.core.LyricLine
import com.enigma.dreamer.R
import com.enigma.dreamer.core.BufferingState
import com.enigma.dreamer.core.PlaybackState
import com.enigma.dreamer.core.RepeatMode as AppRepeatMode
import com.enigma.dreamer.core.Song
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
    // FIX B-2: The original code used `val song = playbackState.currentSong ?: return`,
    // which emitted a completely blank (transparent/black) composable when no song was
    // loaded — no message, no back button, no navigation. This happened reliably when
    // the user tapped the notification before the session had finished restoring.
    //
    // Now we render a proper "Nothing playing" empty state with a back button so the
    // user is never stuck staring at a void with no way out.
    val song = playbackState.currentSong
    if (song == null) {
        NowPlayingEmptyState(onBack = onBack)
        return
    }

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

    val nextSong: Song? = remember(playbackState.queue, playbackState.queueIndex) {
        playbackState.queue.getOrNull(playbackState.queueIndex + 1)
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Layer 1: blurred album art atmosphere ─────────────────────────────
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
                .blur(60.dp)
        )

        // ── Layer 2: dominant-color gradient overlay ──────────────────────────
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

                    VinylDisc(
                        song       = song,
                        isPlaying  = playbackState.isPlaying,
                        bgColor    = bgColor,
                        modifier   = Modifier.align(Alignment.CenterHorizontally)
                    )

                    Spacer(Modifier.weight(0.3f))
                } else {
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
                            Box(
                                modifier         = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Surface3),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(next.albumArtUri)
                                        .crossfade(true)
                                        .placeholder(R.drawable.ic_default_album_art)
                                        .error(R.drawable.ic_default_album_art)
                                        .build(),
                                    contentDescription = null,
                                    contentScale       = ContentScale.Crop,
                                    modifier           = Modifier.fillMaxSize()
                                )
                            }
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

// ── Empty state — FIX B-2 ────────────────────────────────────────────────────

/**
 * Shown when [NowPlayingScreen] is opened but no song is loaded yet — e.g. the
 * user tapped the notification before the session restore completed, or navigated
 * here from a deep link with an empty queue.
 *
 * Previously the screen was completely blank (bare `return` in a Composable),
 * leaving the user stranded with no UI and no way to go back. This replaces that
 * with a minimal but complete empty state that includes a working back button.
 */
@Composable
private fun NowPlayingEmptyState(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Amoled)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        IconButton(
            onClick  = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown, "Back",
                tint     = TextSecondary,
                modifier = Modifier.size(28.dp)
            )
        }
        Column(
            modifier            = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Filled.MusicNote, null,
                tint     = TextMuted,
                modifier = Modifier.size(64.dp)
            )
            Text(
                "Nothing playing",
                style = MaterialTheme.typography.titleMedium,
                color = TextMuted
            )
            Text(
                "Pick a song from the library",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
    }
}

// ── Vinyl Disc Composable ─────────────────────────────────────────────────────

@Composable
private fun VinylDisc(
    song: Song,
    isPlaying: Boolean,
    bgColor: Color,
    modifier: Modifier = Modifier
) {
    var frozenAngle by remember { mutableFloatStateOf(0f) }
    val infiniteTransition = rememberInfiniteTransition(label = "vinylSpin")
    val liveAngle by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 360f,
        animationSpec = infiniteRepeatable(
            animation  = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "vinylLiveAngle"
    )
    LaunchedEffect(isPlaying, liveAngle) {
        if (isPlaying) frozenAngle = liveAngle
    }
    val displayAngle = if (isPlaying) liveAngle else frozenAngle

    val artScale by animateFloatAsState(
        targetValue   = if (isPlaying) 1f else 0.93f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy),
        label         = "vinylScale"
    )

    Box(
        modifier         = modifier
            .size(272.dp)
            .scale(artScale),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(272.dp)
                .shadow(
                    elevation    = if (isPlaying) 40.dp else 12.dp,
                    shape        = CircleShape,
                    ambientColor = bgColor,
                    spotColor    = bgColor
                )
        )

        Box(
            modifier = Modifier
                .size(272.dp)
                .rotate(displayAngle)
                .background(Color(0xFF111111), CircleShape)
                .border(0.5.dp, Color.White.copy(alpha = 0.08f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = size.center
                val radius = size.minDimension / 2f
                for (i in 1..8) {
                    drawCircle(
                        color  = Color.White.copy(alpha = 0.04f),
                        radius = radius * (0.38f + i * 0.07f),
                        center = center,
                        style  = Stroke(width = 1.dp.toPx())
                    )
                }
            }

            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(song.albumArtUri)
                    .crossfade(true)
                    .placeholder(R.drawable.ic_default_album_art)
                    .error(R.drawable.ic_default_album_art)
                    .build(),
                contentDescription = "Album art",
                contentScale       = ContentScale.Crop,
                modifier           = Modifier
                    .size(250.dp)
                    .clip(CircleShape)
            )

            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
            )

            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(bgColor.copy(alpha = 0.9f))
            )
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
            text    = { Text("Playback Speed", style = MaterialTheme.typography.labelSmall, color = Amber) },
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
                            Icon(Icons.Filled.Check, null, tint = Amber, modifier = Modifier.size(16.dp))
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
            text    = { Text("Sleep Timer", style = MaterialTheme.typography.labelSmall, color = Amber) },
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
                        Icon(Icons.Filled.Bedtime, null, tint = Amber, modifier = Modifier.size(16.dp))
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
                    text    = { Text("$minutes minutes", color = TextPrimary, style = MaterialTheme.typography.bodyMedium) },
                    onClick = { onStartSleepTimer(minutes) }
                )
            }
        }
    }
}

// ── Preview ───────────────────────────────────────────────────────────────────
// FIX B-16: All parameters now use named arguments so the trailing lambda
// is unambiguously bound to onSkipToQueue, not onBack.

@Preview
@Composable
private fun NowPlayingPrev() {
    NowPlayingScreen(
        playbackState = PlaybackState(
            currentSong = Song(
                id    = 1,
                title = "Ariana",
                artist = "Diamond Platinum",
                album  = "Dims",
                duration = 400,
                uri    = "".toUri(),
                lyricDocument = LyricDocument(
                    title    = "Ariana",
                    artist   = "Diamond Platinum",
                    album    = "Dims",
                    offsetMs = 5,
                    lines    = listOf(
                        LyricLine(timestampMs = 0L,  "Shine bright like a diamond"),
                        LyricLine(timestampMs = 5L,  "Shine bright like a diamond"),
                        LyricLine(timestampMs = 9L,  "Find light in the beautiful sea"),
                        LyricLine(timestampMs = 13L, "I choose to be happy"),
                        LyricLine(timestampMs = 17L, "You and I, you and I"),
                        LyricLine(timestampMs = 21L, "We're like diamonds in the sky")
                    )
                )
            ),
            positionMs = 250000,
            durationMs = 400000,
            queue = listOf(
                Song(id = 1, title = "Ariana1", artist = "Diamond Platinum", album = "Dims", duration = 400, uri = "".toUri()),
                Song(id = 2, title = "Ariana2", artist = "Diamond Platinum", album = "Dims", duration = 400, uri = "".toUri()),
                Song(id = 3, title = "Ariana3", artist = "Diamond Platinum", album = "Dims", duration = 400, uri = "".toUri()),
            )
        ),
        currentLyricLine   = 2,
        showLyrics         = false,
        showQueue          = false,
        onPlayPause        = {},
        onNext             = {},
        onPrevious         = {},
        onSeek             = {},
        onToggleRepeat     = {},
        onToggleShuffle    = {},
        onToggleLyrics     = {},
        onToggleFavorite   = {},
        onToggleQueue      = {},
        onSpeedChange      = {},
        onStartSleepTimer  = {},
        onCancelSleepTimer = {},
        onOpenSettings     = {},
        onBack             = {},
        onSkipToQueue      = {}   // FIX B-16: named, no ambiguous trailing lambda
    )
}

@Preview
@Composable
private fun NowPlayingEmptyPrev() {
    // Verify the empty state renders correctly for B-2
    NowPlayingScreen(
        playbackState      = PlaybackState(currentSong = null),
        currentLyricLine   = -1,
        showLyrics         = false,
        showQueue          = false,
        onPlayPause        = {},
        onNext             = {},
        onPrevious         = {},
        onSeek             = {},
        onToggleRepeat     = {},
        onToggleShuffle    = {},
        onToggleLyrics     = {},
        onToggleFavorite   = {},
        onToggleQueue      = {},
        onSpeedChange      = {},
        onStartSleepTimer  = {},
        onCancelSleepTimer = {},
        onOpenSettings     = {},
        onBack             = {},
        onSkipToQueue      = {}
    )
}
