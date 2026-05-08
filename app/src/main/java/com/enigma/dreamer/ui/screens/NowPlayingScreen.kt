package com.enigma.dreamer.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.enigma.dreamer.core.BufferingState
import com.enigma.dreamer.ui.components.AlbumArtwork
import com.enigma.dreamer.ui.components.LyricLineItem
import com.enigma.dreamer.ui.components.PlaybackControls
import com.enigma.dreamer.ui.components.PlaybackSlider
import com.enigma.dreamer.ui.theme.*
import com.enigma.dreamer.ui.theme.Amoled
import com.enigma.dreamer.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playbackState: com.enigma.dreamer.core.PlaybackState,
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
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    // Queue callbacks — only active when showQueue == true
    onSkipToQueue: (Int) -> Unit
) {
    val song         = playbackState.currentSong
    val lyricsState  = rememberLazyListState()
    val isPreparing  = playbackState.bufferingState == BufferingState.PREPARING

    // Derive a compose Color from the packed int
    val bgComposeColor = androidx.compose.ui.graphics.Color(dominantColor)
    val fgComposeColor = androidx.compose.ui.graphics.Color(accentTextColor)

    // Smoothly animate background when the art changes
    val bgColor by animateColorAsState(
        targetValue    = bgComposeColor,
        animationSpec  = tween(800, easing = FastOutSlowInEasing),
        label          = "bgColor"
    )

    // Auto-scroll lyrics
    LaunchedEffect(currentLyricLine) {
        if (showLyrics && currentLyricLine >= 0) {
            lyricsState.animateScrollToItem(
                (currentLyricLine - 3).coerceAtLeast(0)
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.00f to bgColor.copy(alpha = 0.90f),
                    0.45f to bgColor.copy(alpha = 0.38f),
                    1.00f to Amoled
                )
            )
    ) {
        // Ambient glow
        Box(
            modifier = Modifier
                .size(360.dp)
                .align(Alignment.TopCenter)
                .offset(y = 50.dp)
                .blur(140.dp)
                .background(
                    Brush.radialGradient(listOf(bgColor.copy(alpha = 0.5f), Amoled)),
                    CircleShape
                )
        )

        // ── Queue overlay ─────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = showQueue,
            enter   = slideInVertically { it },
            exit    = slideOutVertically { it }
        ) {
            QueueScreen(
                queue = playbackState.queue,
                currentIndex = playbackState.queueIndex,
                onSkipTo = onSkipToQueue,
                onClose = onToggleQueue
            )
        }

        if (!showQueue) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {

                // ── Top bar ───────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Back",
                            tint = fgComposeColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(28.dp))
                    }
                    Text(
                        "NOW PLAYING",
                        style = MaterialTheme.typography.labelSmall,
                        color = fgComposeColor.copy(alpha = 0.5f),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                    // Favourite
                    val favTint by animateColorAsState(
                        if (song?.isFavorite == true) Amber
                        else fgComposeColor.copy(alpha = 0.6f),
                        label = "fav"
                    )
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            if (song?.isFavorite == true) Icons.Filled.Favorite
                            else Icons.Filled.FavoriteBorder,
                            "Favourite", tint = favTint,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    // Lyrics
                    IconButton(onClick = onToggleLyrics) {
                        Icon(Icons.Filled.Lyrics, "Lyrics",
                            tint = if (showLyrics) Amber else fgComposeColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(22.dp))
                    }
                    // Settings
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, "Settings",
                            tint = fgComposeColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(22.dp))
                    }
                }

                // ── Art / Lyrics content ──────────────────────────────────────
                if (!showLyrics || song?.lyricDocument == null) {
                    Spacer(Modifier.weight(0.4f))

                    val artScale by animateFloatAsState(
                        if (playbackState.isPlaying) 1f else 0.88f,
                        animationSpec = spring(Spring.DampingRatioMediumBouncy),
                        label = "artScale"
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .scale(artScale),
                        contentAlignment = Alignment.Center
                    ) {
                        AlbumArtwork(
                            song = song!!,
                            size = 272.dp,
                            modifier = Modifier.shadow(
                                if (playbackState.isPlaying) 28.dp else 6.dp, CircleShape
                            )
                        )
                        // Buffering overlay on top of art
                        if (isPreparing) {
                            CircularProgressIndicator(
                                color       = Amber,
                                strokeWidth = 3.dp,
                                modifier    = Modifier.size(48.dp)
                            )
                        }
                    }

                    Spacer(Modifier.weight(0.4f))
                } else {
                    // Lyrics list
                    LazyColumn(
                        state          = lyricsState,
                        modifier       = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 80.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        itemsIndexed(song.lyricDocument!!.lines) { idx, line ->
                            LyricLineItem(text = line.text, isActive = idx == currentLyricLine)
                        }
                    }
                }

                // ── Song info ─────────────────────────────────────────────────
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AnimatedContent(targetState = song?.title ?: "—", label = "title") { title ->
                        Text(
                            title,
                            style     = MaterialTheme.typography.displayMedium,
                            color     = fgComposeColor,
                            fontWeight= FontWeight.Bold,
                            maxLines  = 1,
                            overflow  = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        song?.artist ?: "",
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = fgComposeColor.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(20.dp))

                // ── Slider ────────────────────────────────────────────────────
                PlaybackSlider(
                    positionMs = playbackState.positionMs,
                    durationMs = playbackState.durationMs,
                    onSeek = onSeek,
                    fgColor = fgComposeColor,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                Spacer(Modifier.height(6.dp))

                // ── Main controls ─────────────────────────────────────────────
                PlaybackControls(
                    isPlaying = playbackState.isPlaying,
                    isPreparing = isPreparing,
                    hasNext = playbackState.hasNext,
                    hasPrevious = playbackState.hasPrevious,
                    repeatMode = playbackState.repeatMode,
                    shuffleMode = playbackState.shuffleMode,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onToggleRepeat = onToggleRepeat,
                    onToggleShuffle = onToggleShuffle,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(Modifier.height(8.dp))

                // ── Speed chip + Queue button row ─────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Speed selector — compact chip cycle
                    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                    val curIdx = speeds.indexOfFirst { it == playbackState.playbackSpeed }
                        .coerceAtLeast(0)
                    FilterChip(
                        selected = playbackState.playbackSpeed != 1.0f,
                        onClick  = { onSpeedChange(speeds[(curIdx + 1) % speeds.size]) },
                        label    = {
                            Text(
                                "${playbackState.playbackSpeed}×",
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Speed, null, modifier = Modifier.size(14.dp))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberDim,
                            selectedLabelColor     = Amber,
                            containerColor         = Surface3,
                            labelColor             = TextSecondary
                        )
                    )

                    // Sleep timer indicator (when active)
                    if (playbackState.sleepTimer.isActive) {
                        val remaining = playbackState.sleepTimer.remainingMs
                        val mins      = remaining / 60000
                        val secs      = (remaining % 60000) / 1000
                        AssistChip(
                            onClick = onOpenSettings,
                            label   = {
                                Text(
                                    "Sleep %d:%02d".format(mins, secs),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.Bedtime, null,
                                    modifier = Modifier.size(14.dp), tint = Amber)
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = AmberDim,
                                labelColor     = Amber
                            )
                        )
                    }

                    // Queue button
                    IconButton(onClick = onToggleQueue) {
                        Icon(
                            Icons.Filled.QueueMusic, "Queue",
                            tint = fgComposeColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
