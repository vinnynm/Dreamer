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
 * 8.5: Added drag-to-reorder.
 *
 * FIX NEW-1: After a drag completes, the local `currentIndexLocal` is updated
 * immediately to follow the moved item, so the amber "now playing" highlight
 * stays on the correct row while the ViewModel processes the reorder and
 * re-emits playerState. Without this the highlight flickers to the wrong row
 * for one or two frames until the next state emission arrives.
 *
 * The correction mirrors standard reorder index-shift logic:
 *  - If the dragged item IS the current song → its new position is to.index.
 *  - If the current song is between from and to → shift it ±1 depending on
 *    drag direction.
 *  - Otherwise → current index is unaffected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    queue: List<Song>,
    currentIndex: Int,
    onSkipTo: (Int) -> Unit,
    onClose: () -> Unit,
    onReorder: (List<Song>) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current

    var items by remember(queue) { mutableStateOf(queue) }

    // FIX NEW-1: track current-song index locally so the highlight stays correct
    // immediately after a drag, before the ViewModel re-emits playerState.
    // Reset to the prop value whenever the external queue or index changes.
    var currentIndexLocal by remember(queue, currentIndex) { mutableIntStateOf(currentIndex) }

    val listState    = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        // Update items immediately for instant visual feedback
        items = items.toMutableList().apply { add(to.index, removeAt(from.index)) }

        // FIX NEW-1: shift currentIndexLocal to stay on the same song
        currentIndexLocal = when (currentIndexLocal) {
            from.index -> to.index                       // dragged song IS the current one
            in (minOf(from.index, to.index)..maxOf(from.index, to.index)) ->
                // current song is between the drag endpoints — shift by ±1
                if (from.index < to.index) currentIndexLocal - 1
                else currentIndexLocal + 1
            else -> currentIndexLocal                    // drag didn't cross current song
        }
    }

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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Up Next",
                style    = MaterialTheme.typography.titleLarge,
                color    = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${items.size} songs",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
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
            // FIX N-8: key = index only, not "${song.id}_$idx", so Compose
            // doesn't treat every item as a different composition after a drag.
            itemsIndexed(items, key = { idx, _ -> idx }) { idx, song ->
                // Use the locally-corrected index for highlight decisions
                val isCurrent = idx == currentIndexLocal

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