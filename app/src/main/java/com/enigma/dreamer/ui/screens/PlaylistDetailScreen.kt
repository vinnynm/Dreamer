package com.enigma.dreamer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.enigma.dreamer.core.Playlist
import com.enigma.dreamer.core.Song
import com.enigma.dreamer.ui.components.SongListItem
import com.enigma.dreamer.ui.theme.Amber
import com.enigma.dreamer.ui.theme.Amoled
import com.enigma.dreamer.ui.theme.TextMuted
import com.enigma.dreamer.ui.theme.TextPrimary
import com.enigma.dreamer.ui.theme.Surface3

/**
 * Playlist detail screen.
 *
 * 8.6: Added drag-to-reorder. Each row has a drag handle on the right.
 * When the user drops a row, [onReorderSongs] is called with the new
 * ordered list of song IDs so the ViewModel can persist it to Room.
 *
 * Persistence path (wiring required in ViewModel + Repository):
 *   onReorderSongs(newIds: List<Long>)
 *     → viewModel.reorderPlaylistSongs(playlist.id, newIds)
 *     → repo.reorderPlaylistSongs(playlistId, newIds)
 *     → DAO: delete old cross-refs, re-insert with updated `position` column
 *
 * See DREAMER_AUDIT.md §8.6 and the new DAO method notes below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlist: Playlist,
    songs: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (Song) -> Unit,
    onRemoveSong: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onBack: () -> Unit,
    // 8.6: called with new ordered song-ID list after a drag completes
    onReorderSongs: (List<Long>) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current

    // Local mutable copy so drags feel instant
    var items by remember(songs) { mutableStateOf(songs) }

    val listState    = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = from.index - 1
        val toIndex   = to.index - 1
        if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
            items = items.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        }
    }

    Scaffold(
        containerColor = Amoled,
        topBar = {
            TopAppBar(
                title = { Text(playlist.name, color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Amoled),
                actions = {
                    IconButton(onClick = onPlayAll, enabled = items.isNotEmpty()) {
                        Icon(Icons.Filled.PlayCircle, "Play all", tint = Amber,
                            modifier = Modifier.size(32.dp))
                    }
                }
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.LibraryMusic, null, tint = TextMuted,
                        modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("This playlist is empty", color = TextMuted,
                        style = MaterialTheme.typography.bodyLarge)
                    Text("Long-press songs to add them", color = TextMuted,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(
                state          = listState,
                modifier       = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                item {
                    Text(
                        "${items.size} songs",
                        style    = MaterialTheme.typography.bodySmall,
                        color    = TextMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                itemsIndexed(items, key = { _, song -> song.id }) { idx, song ->
                    ReorderableItem(reorderState, key = song.id) { isDragging ->
                        val elevation by androidx.compose.animation.core.animateDpAsState(
                            if (isDragging) 8.dp else 0.dp,
                            label = "dragElevation"
                        )

                        Surface(
                            shadowElevation = elevation,
                            color           = Color.Transparent
                        ) {
                            Row(
                                modifier              = Modifier.fillMaxWidth(),
                                verticalAlignment     = Alignment.CenterVertically
                            ) {
                                // Song row (existing component, weight 1f)
                                SongListItem(
                                    song      = song,
                                    isPlaying = song.id == currentSong?.id && isPlaying,
                                    onClick   = { onSongClick(song) },
                                    modifier  = Modifier.weight(1f),
                                    trailingContent = {
                                        // Remove button
                                        IconButton(onClick = {
                                            val newList = items.toMutableList().also {
                                                it.removeAt(idx)
                                            }
                                            items = newList
                                            onRemoveSong(song)
                                        }) {
                                            Icon(
                                                Icons.Filled.RemoveCircleOutline, "Remove",
                                                tint     = TextMuted,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        // 8.6: drag handle
                                        Icon(
                                            imageVector        = Icons.Filled.DragHandle,
                                            contentDescription = "Drag to reorder",
                                            tint               = TextMuted,
                                            modifier           = Modifier
                                                .size(20.dp)
                                                .draggableHandle(
                                                    onDragStarted = {
                                                        haptic.performHapticFeedback(
                                                            HapticFeedbackType.LongPress
                                                        )
                                                    },
                                                    onDragStopped = {
                                                        haptic.performHapticFeedback(
                                                            HapticFeedbackType.LongPress
                                                        )
                                                        // Persist the new order
                                                        onReorderSongs(items.map { it.id })
                                                    }
                                                )
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
