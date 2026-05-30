package com.enigma.dreamer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enigma.dreamer.core.Playlist
import com.enigma.dreamer.core.Song
import com.enigma.dreamer.ui.components.SongListItem
import com.enigma.dreamer.ui.theme.Amber
import com.enigma.dreamer.ui.theme.Amoled
import com.enigma.dreamer.ui.theme.TextMuted
import com.enigma.dreamer.ui.theme.TextPrimary

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
    onBack: () -> Unit
) {
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
                    IconButton(onClick = onPlayAll, enabled = songs.isNotEmpty()) {
                        Icon(Icons.Filled.PlayCircle, "Play all", tint = Amber, modifier = Modifier.size(32.dp))
                    }
                }
            )
        }
    ) { padding ->
        if (songs.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.LibraryMusic, null, tint = TextMuted, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("This playlist is empty", color = TextMuted, style = MaterialTheme.typography.bodyLarge)
                    Text("Long-press songs to add them", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                item {
                    Text("${songs.size} songs", style = MaterialTheme.typography.bodySmall,
                        color = TextMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                items(songs, key = { it.id }) { song ->
                    SongListItem(
                        song = song,
                        isPlaying = song.id == currentSong?.id && isPlaying,
                        onClick = { onSongClick(song) },
                        trailingContent = {
                            IconButton(onClick = { onRemoveSong(song) }) {
                                Icon(
                                    Icons.Filled.RemoveCircleOutline, "Remove",
                                    tint = TextMuted, modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

