package com.enigma.dreamer.ui.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.enigma.devlyric.core.*
import com.enigma.dreamer.core.Song
import com.enigma.dreamer.ui.components.AlbumArtwork
import com.enigma.dreamer.ui.components.NowPlayingIndicator
import com.enigma.dreamer.ui.components.formatDuration
import com.enigma.dreamer.ui.theme.Amber
import com.enigma.dreamer.ui.theme.Surface1
import com.enigma.dreamer.ui.theme.Surface3
import com.enigma.dreamer.ui.theme.TextMuted
import com.enigma.dreamer.ui.theme.TextPrimary
import com.enigma.dreamer.ui.theme.TextSecondary

/**
 * Full "Up Next" queue panel. Shows the current queue with the active song
 * highlighted; tapping any row skips to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    queue: List<Song>,
    currentIndex: Int,
    onSkipTo: (Int) -> Unit,
    onClose: () -> Unit
) {
    val listState = rememberLazyListState()

    // Scroll to current song on open
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) listState.animateScrollToItem(currentIndex.coerceAtMost(queue.size - 1))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Surface1)
            .statusBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Up Next", style = MaterialTheme.typography.titleLarge,
                color = TextPrimary, modifier = Modifier.weight(1f))
            Text("${queue.size} songs", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, "Close", tint = TextSecondary)
            }
        }
        HorizontalDivider(color = Surface3)

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(queue, key = { idx, song -> "${song.id}_$idx" }) { idx, song ->
                val isCurrent = idx == currentIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isCurrent) Surface3 else Color.Transparent,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onSkipTo(idx) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Track number or now-playing indicator
                    Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                        if (isCurrent) NowPlayingIndicator()
                        else Text(
                            "${idx + 1}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    AlbumArtwork(song, size = 44.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            song.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrent) Amber else TextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            song.artist,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        formatDuration(song.duration),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }
        }
    }
}
