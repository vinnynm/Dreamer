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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.enigma.dreamer.core.Song
import com.enigma.dreamer.ui.components.AlbumArtwork
import com.enigma.dreamer.ui.components.NowPlayingIndicator
import com.enigma.dreamer.ui.components.formatDuration
import com.enigma.dreamer.ui.theme.*

/**
 * Full "Up Next" queue panel.
 *
 * 8.5: Added drag-to-reorder. Rows have a drag handle (⠿ grip icon) on the
 * right side. The user long-presses the handle to begin dragging; the item
 * snaps back with a spring animation on drop.
 *
 * Implementation notes:
 *  - Uses `sh.calvin.reorderable:reorderable:<version>` (Compose-first library).
 *    Add to build.gradle:
 *      implementation "sh.calvin.reorderable:reorderable:2.4.0"
 *  - [onReorder] is called with the updated list after each completed drag so
 *    the ViewModel / MusicService can persist the new queue order.
 *  - The currently-playing item can still be dragged — the service handles
 *    seekTo correctly after a queue reorder.
 *  - Haptic feedback fires when a drag starts (LONG_PRESS) and on drop
 *    (GESTURE_END) to match system drag-and-drop conventions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    queue: List<Song>,
    currentIndex: Int,
    onSkipTo: (Int) -> Unit,
    onClose: () -> Unit,
    // 8.5: called with the reordered list after a drag completes
    onReorder: (List<Song>) -> Unit = {}
) {
    val haptic   = LocalHapticFeedback.current
    // Local mutable copy so drags feel instant (no round-trip to ViewModel)
    var items by remember(queue) { mutableStateOf(queue) }

    val listState     = rememberLazyListState()
    val reorderState  = rememberReorderableLazyListState(listState) { from, to ->
        items = items.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    // Scroll to current song on open
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            listState.animateScrollToItem(currentIndex.coerceAtMost(items.size - 1))
        }
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
            Text("${items.size} songs", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, "Close", tint = TextSecondary)
            }
        }
        HorizontalDivider(color = Surface3)

        LazyColumn(
            state          = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier       = Modifier.fillMaxSize()
        ) {
            itemsIndexed(items, key = { idx, _ -> idx }) { idx, song ->
                val isCurrent = idx == currentIndex

                ReorderableItem(reorderState, key = idx) { isDragging ->
                    val elevation by androidx.compose.animation.core.animateDpAsState(
                        if (isDragging) 8.dp else 0.dp,
                        label = "dragElevation"
                    )

                    Surface(
                        shadowElevation = elevation,
                        color           = Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    when {
                                        isDragging -> Surface3.copy(alpha = 0.85f)
                                        isCurrent  -> Surface3
                                        else       -> Color.Transparent
                                    },
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { onSkipTo(idx) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment     = Alignment.CenterVertically,
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
                                    style      = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    color      = if (isCurrent) Amber else TextPrimary,
                                    maxLines   = 1,
                                    overflow   = TextOverflow.Ellipsis
                                )
                                Text(
                                    song.artist,
                                    style    = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Text(
                                formatDuration(song.duration),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )

                            // 8.5: drag handle
                            Icon(
                                imageVector        = Icons.Filled.DragHandle,
                                contentDescription = "Drag to reorder",
                                tint               = TextMuted,
                                modifier           = Modifier
                                    .size(20.dp)
                                    .draggableHandle(
                                        onDragStarted = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDragStopped = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            // Notify ViewModel of the new order once drag ends
                                            onReorder(items)
                                        }
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}
