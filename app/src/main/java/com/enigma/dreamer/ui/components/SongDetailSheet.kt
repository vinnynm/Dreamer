package com.enigma.dreamer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.enigma.devlyric.core.*
import com.enigma.dreamer.core.Playlist
import com.enigma.dreamer.core.Song
import com.enigma.dreamer.ui.theme.*

/**
 * Bottom sheet showing full song metadata and quick-action buttons:
 * Play Next, Add to Queue, Add to Playlist, Toggle Favourite, Embed Lyrics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongDetailSheet(
    song: Song,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: (Playlist) -> Unit,
    onToggleFavorite: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest  = onDismiss,
        containerColor    = Surface2,
        dragHandle        = { BottomSheetDefaults.DragHandle(color = TextMuted) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Song identity
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AlbumArtwork(song, size = 64.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text(song.artist, style = MaterialTheme.typography.bodySmall)
                    Text(song.album,  style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }

            HorizontalDivider(color = Surface3)

            // Quick actions
            Text("Actions", style = MaterialTheme.typography.labelSmall,
                color = TextMuted, modifier = Modifier.padding(top = 4.dp))

            ActionRow(Icons.Filled.QueuePlayNext, "Play Next",        onPlayNext)
            ActionRow(Icons.Filled.AddToQueue,    "Add to Queue",     onAddToQueue)
            ActionRow(
                if (song.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                if (song.isFavorite) "Remove from Favourites" else "Add to Favourites",
                onToggleFavorite,
                tint = if (song.isFavorite) Amber else TextPrimary
            )

            if (playlists.isNotEmpty()) {
                HorizontalDivider(color = Surface3)
                Text("Add to Playlist", style = MaterialTheme.typography.labelSmall,
                    color = TextMuted)
                playlists.forEach { pl ->
                    ActionRow(Icons.AutoMirrored.Filled.PlaylistAdd, pl.name, { onAddToPlaylist(pl) })
                }
            }

            HorizontalDivider(color = Surface3)

            // Metadata grid
            Text("Info", style = MaterialTheme.typography.labelSmall,
                color = TextMuted)
            MetaGrid(song)
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = TextPrimary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface3, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .then(
                Modifier.clickable(onClick = onClick)   // uses extension from foundation
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

@Composable
private fun MetaGrid(song: Song) {
    val rows = listOf(
        "Duration"   to formatDuration(song.duration),
        "Artist"     to song.artist,
        "Album"      to song.album,
        "Year"       to if (song.year > 0) song.year.toString() else "—",
        "Track"      to if (song.trackNumber > 0) song.trackNumber.toString() else "—",
        "Format"     to song.mimeType.substringAfterLast('/').uppercase().ifBlank { "—" },
        "File size"  to formatFileSize(song.fileSize),
        "Has lyrics" to if (song.lyricDocument != null) "Yes (${song.lyricDocument.lines.size} lines)" else "No",
        "Path"       to song.filePath.substringAfterLast('/')
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { (label, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = TextMuted,
                    modifier = Modifier.weight(0.4f))
                Text(value, style = MaterialTheme.typography.bodySmall, color = TextPrimary,
                    modifier = Modifier.weight(0.6f))
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes <= 0      -> "—"
    bytes < 1024    -> "$bytes B"
    bytes < 1024*1024 -> "%.1f KB".format(bytes / 1024f)
    else            -> "%.1f MB".format(bytes / (1024f * 1024f))
}


