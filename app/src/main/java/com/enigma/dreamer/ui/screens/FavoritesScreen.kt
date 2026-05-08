package com.enigma.dreamer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enigma.devlyric.core.*
import com.enigma.dreamer.core.Song
import com.enigma.dreamer.ui.components.*
import com.enigma.dreamer.ui.theme.*

/**
 * Dedicated Favourites tab — shows only songs where isFavorite == true,
 * with an option to play them all as a shuffled or ordered queue.
 */
@Composable
fun FavoritesTab(
    songs: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onLongClick: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    val favorites = remember(songs) { songs.filter { it.isFavorite } }

    if (favorites.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.FavoriteBorder, null,
                    tint = TextMuted,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "No favourites yet",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "Tap ♥ on any song to add it here",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${favorites.size} favourite${if (favorites.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.weight(1f)
                )
                // Play all favourites
                TextButton(
                    onClick = onPlayAll,
                    colors  = ButtonDefaults.textButtonColors(contentColor = Amber)
                ) {
                    Icon(
                        Icons.Filled.PlayArrow, null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Play all", style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }

        items(favorites, key = { it.id }) { song ->
            val favTint by animateColorAsState(
                if (song.isFavorite) Amber else TextMuted,
                label = "fav${song.id}"
            )
            SongListItem(
                song = song,
                isPlaying = song.id == currentSong?.id && isPlaying,
                onClick = { onSongClick(song) },
                onLongClick = { onLongClick(song) },
                trailingContent = {
                    IconButton(onClick = { onToggleFavorite(song) }) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = "Remove from favourites",
                            tint = favTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        }

        item { Spacer(Modifier.height(72.dp)) }
    }
}
