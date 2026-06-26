package com.enigma.dreamer.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.enigma.dreamer.core.Playlist
import com.enigma.dreamer.core.Song
import com.enigma.dreamer.core.SortOrder
import com.enigma.dreamer.ui.components.MiniPlayer
import com.enigma.dreamer.ui.components.SongDetailSheet
import com.enigma.dreamer.ui.components.SongListItem
import com.enigma.dreamer.ui.theme.*
import kotlinx.coroutines.launch

private val TABS = listOf("Songs", "Favourites", "Playlists")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    songs: List<Song>,
    filteredSongs: List<Song>,
    playlists: List<Playlist>,
    searchQuery: String,
    sortOrder: SortOrder,
    currentSong: Song?,
    isPlaying: Boolean,
    playbackProgress: Float,
    dominantColor: Int = 0xFF161616.toInt(),
    scanProgress: Int? = null,
    onSongClick: (Song) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onSearch: (String) -> Unit,
    onSortChange: (SortOrder) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onRenamePlaylist: (Long, String) -> Unit,
    onAddSongToPlaylist: (Song, Long) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onEditLyrics: (Song) -> Unit,
    onPlayFavorites: () -> Unit,
    onMiniPlayerClick: () -> Unit,
    onMiniPlayPause: () -> Unit,
    onMiniNext: () -> Unit,
    onMiniPrevious: () -> Unit = {},
    onRescan: () -> Unit = {}
) {
    val scope      = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0) { TABS.size }

    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName          by remember { mutableStateOf("") }
    var showSortSheet            by remember { mutableStateOf(false) }
    var detailSong               by remember { mutableStateOf<Song?>(null) }

    val onLongClickRem      = remember { { song: Song -> detailSong = song } }
    val onToggleFavoriteRem = remember(onToggleFavorite) { onToggleFavorite }

    Scaffold(
        containerColor = Amoled,
        topBar = {
            LibraryTopBar(
                selectedTab           = pagerState.currentPage,
                searchQuery           = searchQuery,
                totalSongCount        = songs.size,
                filteredSongCount     = filteredSongs.size,
                isScanning            = scanProgress != null,
                scanProgress          = scanProgress,
                onSearch              = onSearch,
                onSortClick           = { showSortSheet = true },
                onCreatePlaylistClick = { showCreatePlaylistDialog = true },
                onTabSelect           = { idx -> scope.launch { pagerState.animateScrollToPage(idx) } },
                onRescan              = onRescan
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = currentSong != null,
                enter   = slideInVertically { it },
                exit    = slideOutVertically { it }
            ) {
                currentSong?.let { song ->
                    MiniPlayer(
                        song          = song,
                        isPlaying     = isPlaying,
                        progress      = playbackProgress,
                        dominantColor = Color(dominantColor),
                        onPlayPause   = onMiniPlayPause,
                        onNext        = onMiniNext,
                        onPrevious    = onMiniPrevious,
                        onClick       = onMiniPlayerClick
                    )
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state    = pagerState,
            modifier = Modifier.padding(padding)
        ) { page ->
            when (page) {
                0 -> SongsTab(
                    songs            = filteredSongs,
                    currentSong      = currentSong,
                    isPlaying        = isPlaying,
                    onSongClick      = onSongClick,
                    onLongClick      = onLongClickRem,
                    onToggleFavorite = onToggleFavoriteRem
                )
                1 -> FavoritesTab(
                    songs            = songs,
                    currentSong      = currentSong,
                    isPlaying        = isPlaying,
                    onSongClick      = onSongClick,
                    onToggleFavorite = onToggleFavoriteRem,
                    onPlayAll        = onPlayFavorites,
                    onLongClick      = onLongClickRem
                )
                2 -> PlaylistsTab(
                    playlists        = playlists,
                    songs            = songs,
                    onPlaylistClick  = onPlaylistClick,
                    onDeletePlaylist = onDeletePlaylist,
                    onRenamePlaylist = onRenamePlaylist
                )
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    if (showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false; newPlaylistName = "" },
            containerColor   = Surface2,
            title  = { Text("New Playlist", color = TextPrimary) },
            text   = {
                OutlinedTextField(
                    value         = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder   = { Text("Playlist name", color = TextMuted) },
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Amber,
                        cursorColor        = Amber
                    ),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newPlaylistName.isNotBlank()) {
                        onCreatePlaylist(newPlaylistName.trim())
                        newPlaylistName = ""
                        showCreatePlaylistDialog = false
                    }
                }) { Text("Create", color = Amber) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCreatePlaylistDialog = false
                    newPlaylistName = ""
                }) { Text("Cancel", color = TextSecondary) }
            }
        )
    }

    if (showSortSheet) {
        SortBottomSheet(
            current   = sortOrder,
            onSelect  = { onSortChange(it); showSortSheet = false },
            onDismiss = { showSortSheet = false }
        )
    }

    detailSong?.let { song ->
        SongDetailSheet(
            song             = song,
            playlists        = playlists,
            onDismiss        = { detailSong = null },
            onPlayNext       = { onPlayNext(song);                    detailSong = null },
            onAddToQueue     = { onAddToQueue(song);                  detailSong = null },
            onAddToPlaylist  = { pl -> onAddSongToPlaylist(song, pl.id); detailSong = null },
            onToggleFavorite = { onToggleFavorite(song);              detailSong = null },
            onEditLyrics     = { onEditLyrics(song);                  detailSong = null }
        )
    }
}

// ── Top Bar ───────────────────────────────────────────────────────────────────

@Composable
private fun LibraryTopBar(
    selectedTab: Int,
    searchQuery: String,
    totalSongCount: Int,
    filteredSongCount: Int,
    isScanning: Boolean,
    scanProgress: Int?,
    onSearch: (String) -> Unit,
    onSortClick: () -> Unit,
    onCreatePlaylistClick: () -> Unit,
    onTabSelect: (Int) -> Unit,
    onRescan: () -> Unit
) {
    // 8.3: number of songs hidden by the current search filter
    val hiddenCount = if (searchQuery.isNotBlank()) totalSongCount - filteredSongCount else 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "DevLyric",
                style    = MaterialTheme.typography.displayLarge,
                color    = Amber,
                modifier = Modifier.weight(1f)
            )
            if (selectedTab == 0) {
                IconButton(onClick = onRescan, enabled = !isScanning) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            color       = Amber,
                            strokeWidth = 2.dp,
                            modifier    = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(Icons.Filled.Refresh, "Rescan library", tint = TextSecondary)
                    }
                }
                IconButton(onClick = onSortClick) {
                    Icon(Icons.Filled.SortByAlpha, "Sort", tint = TextSecondary)
                }
            }
            if (selectedTab == 2) {
                IconButton(onClick = onCreatePlaylistClick) {
                    Icon(Icons.Filled.Add, "New playlist", tint = Amber)
                }
            }
        }

        AnimatedVisibility(
            visible = isScanning,
            enter   = fadeIn() + expandVertically(),
            exit    = fadeOut() + shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(bottom = 4.dp)) {
                LinearProgressIndicator(
                    modifier   = Modifier.fillMaxWidth(),
                    color      = Amber,
                    trackColor = Surface3
                )
                if (scanProgress != null && scanProgress > 0) {
                    Text(
                        "Scanning… $scanProgress songs found",
                        style    = MaterialTheme.typography.bodySmall,
                        color    = TextMuted,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value         = searchQuery,
            onValueChange = onSearch,
            placeholder   = { Text("Search songs, artists…", color = TextMuted) },
            leadingIcon   = { Icon(Icons.Filled.Search, null, tint = TextMuted) },
            trailingIcon  = if (searchQuery.isNotBlank()) ({
                IconButton(onClick = { onSearch("") }) {
                    Icon(Icons.Filled.Close, null, tint = TextMuted)
                }
            }) else null,
            modifier        = Modifier.fillMaxWidth(),
            shape           = RoundedCornerShape(16.dp),
            colors          = OutlinedTextFieldDefaults.colors(
                focusedBorderColor      = Amber,
                unfocusedBorderColor    = Surface3,
                focusedContainerColor   = Surface2,
                unfocusedContainerColor = Surface2,
                cursorColor             = Amber
            ),
            singleLine      = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )

        // 8.3: "X songs hidden by search" banner — only visible when search
        // is active and fewer results are shown than the full library.
        AnimatedVisibility(
            visible = hiddenCount > 0 && selectedTab == 0,
            enter   = fadeIn() + expandVertically(),
            exit    = fadeOut() + shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface2)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Filled.FilterList, null,
                    tint     = TextMuted,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    "$hiddenCount song${if (hiddenCount != 1) "s" else ""} hidden by search",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.weight(1f)
                )
                // Tappable "Clear" shortcut so the user doesn't have to reach the X in the field
                TextButton(
                    onClick        = { onSearch("") },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        "Clear",
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor   = Color.Transparent,
            contentColor     = Amber,
            indicator        = { positions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(positions[selectedTab]),
                    color    = Amber
                )
            }
        ) {
            TABS.forEachIndexed { idx, label ->
                Tab(
                    selected = selectedTab == idx,
                    onClick  = { onTabSelect(idx) },
                    text     = {
                        Text(
                            label,
                            color = if (selectedTab == idx) Amber else TextSecondary
                        )
                    }
                )
            }
        }
    }
}

// ── Sort Bottom Sheet ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortBottomSheet(
    current: SortOrder,
    onSelect: (SortOrder) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        dragHandle       = { BottomSheetDefaults.DragHandle(color = TextMuted) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                "Sort by",
                style    = MaterialTheme.typography.titleMedium,
                color    = TextPrimary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            SortOrder.entries.forEach { order ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (current == order) AmberDim else Color.Transparent)
                        .clickable { onSelect(order) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        if (current == order) Icons.Filled.RadioButtonChecked
                        else Icons.Filled.RadioButtonUnchecked,
                        null,
                        tint     = if (current == order) Amber else TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        order.label(),
                        color = if (current == order) Amber else TextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

private fun SortOrder.label() = when (this) {
    SortOrder.TITLE_ASC       -> "Title A → Z"
    SortOrder.TITLE_DESC      -> "Title Z → A"
    SortOrder.ARTIST_ASC      -> "Artist A → Z"
    SortOrder.ARTIST_DESC     -> "Artist Z → A"
    SortOrder.ALBUM_ASC       -> "Album A → Z"
    SortOrder.DURATION_ASC    -> "Shortest first"
    SortOrder.DURATION_DESC   -> "Longest first"
    SortOrder.DATE_ADDED_DESC -> "Recently added"
    SortOrder.FAVORITES_FIRST -> "Favourites first"
}

// ── Songs Tab ─────────────────────────────────────────────────────────────────

@Composable
fun SongsTab(
    songs: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (Song) -> Unit,
    onLongClick: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    if (songs.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.LibraryMusic, null, tint = TextMuted, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(12.dp))
                Text("No songs found", color = TextMuted, style = MaterialTheme.typography.bodyLarge)
                Text("Add music to your device", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }
    LazyColumn(
        modifier       = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    ) {
        item {
            Text(
                "${songs.size} songs",
                style    = MaterialTheme.typography.bodySmall,
                color    = TextMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        items(songs, key = { it.id }, contentType = { "song" }) { song ->
            val favTint by animateColorAsState(
                if (song.isFavorite) Amber else TextMuted,
                label = "fav${song.id}"
            )
            SongListItem(
                song        = song,
                isPlaying   = song.id == currentSong?.id && isPlaying,
                onClick     = { onSongClick(song) },
                onLongClick = { onLongClick(song) },
                trailingContent = {
                    IconButton(onClick = { onToggleFavorite(song) }) {
                        Icon(
                            if (song.isFavorite) Icons.Filled.Favorite
                            else Icons.Filled.FavoriteBorder,
                            "Favourite",
                            tint     = favTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

// ── Playlists Tab ─────────────────────────────────────────────────────────────

@Composable
fun PlaylistsTab(
    playlists: List<Playlist>,
    songs: List<Song>,
    onPlaylistClick: (Playlist) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onRenamePlaylist: (Long, String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (playlists.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = TextMuted, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(12.dp))
                Text("No playlists yet", color = TextMuted, style = MaterialTheme.typography.bodyLarge)
                Text("Tap + to create one", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }
    LazyColumn(
        modifier       = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        items(playlists, key = { it.id }, contentType = { "playlist" }) { playlist ->
            PlaylistItem(
                playlist        = playlist,
                songCount       = playlist.songIds.size,
                onPlaylistClick = { onPlaylistClick(playlist) },
                onDelete        = { onDeletePlaylist(playlist.id) },
                onRename        = { name -> onRenamePlaylist(playlist.id, name) }
            )
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
fun PlaylistItem(
    playlist: Playlist,
    songCount: Int,
    onPlaylistClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit
) {
    var showMenu   by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(playlist.name) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface2)
            .clickable(onClick = onPlaylistClick)
            .padding(16.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier         = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AmberDim),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Amber, modifier = Modifier.size(28.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                playlist.name,
                style      = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color      = TextPrimary,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
            Text("$songCount song${if (songCount != 1) "s" else ""}", style = MaterialTheme.typography.bodySmall)
        }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Filled.MoreVert, null, tint = TextSecondary)
            }
            DropdownMenu(
                expanded         = showMenu,
                onDismissRequest = { showMenu = false },
                containerColor   = Surface3
            ) {
                DropdownMenuItem(
                    text        = { Text("Rename", color = TextPrimary) },
                    onClick     = { showMenu = false; showRename = true; renameText = playlist.name },
                    leadingIcon = { Icon(Icons.Filled.Edit, null, tint = TextSecondary) }
                )
                DropdownMenuItem(
                    text        = { Text("Delete", color = ErrorRed) },
                    onClick     = { showMenu = false; onDelete() },
                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = ErrorRed) }
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            containerColor   = Surface2,
            title  = { Text("Rename Playlist", color = TextPrimary) },
            text   = {
                OutlinedTextField(
                    value         = renameText,
                    onValueChange = { renameText = it },
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Amber,
                        cursorColor        = Amber
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) { onRename(renameText.trim()); showRename = false }
                }) { Text("Save", color = Amber) }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Cancel", color = TextSecondary) }
            }
        )
    }
}
