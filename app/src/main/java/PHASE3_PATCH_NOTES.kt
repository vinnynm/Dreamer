// ─────────────────────────────────────────────────────────────────────────────
// PHASE 3 PATCH NOTES — UI/UX parity
// ─────────────────────────────────────────────────────────────────────────────
//
// FILES TO REPLACE
// ────────────────
// Components.kt      → ui/components/   (MiniPlayer: swipe, vinyl, dynamic bg)
// NowPlayingScreen.kt→ ui/screens/       (blurred atmosphere, Next Up peek)
// LibraryScreen.kt   → ui/screens/       (HorizontalPager swipeable tabs)
//
// MANUAL FIXES REQUIRED
// ──────────────────────
//
// 1. Components.kt MiniPlayer — remove the duplicate IconButton/Box pair.
//    The MiniPlayer has both a bare IconButton and an Amber Box both calling
//    onPlayPause. Keep only the Amber Box (the amber circle button).
//    Delete these four lines:
//
//      IconButton(onClick = onPlayPause) {
//          Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
//               contentDescription = if (isPlaying) "Pause" else "Play",
//               tint = Color.White, modifier = Modifier.size(28.dp))
//      }
//
//    The Amber Box below it is the correct one to keep.
//
// 2. MainActivity.kt — thread dominantColor + scanProgress into LibraryScreen.
//    Inside composable<LibraryRoute> { … }, replace the LibraryScreen(...) call
//    with the version below.
//
// ─────────────────────────────────────────────────────────────────────────────
// UPDATED LibraryRoute composable<LibraryRoute> block for MainActivity.kt
// ─────────────────────────────────────────────────────────────────────────────
/*
composable<LibraryRoute> {
    val songs         = state.songs
    val filteredSongs = state.filteredSongs
    val playlists     = state.playlists
    val currentSong   = ps.currentSong
    val isPlaying     = ps.isPlaying
    val progress      = ps.progress

    val scanProgress by viewModel.scanProgress.collectAsState()

    val onSongClickRem = remember(viewModel, filteredSongs) {
        { song: Song ->
            viewModel.playSong(song, filteredSongs)
            navController.navigate(NowPlayingRoute)
        }
    }
    val onPlaylistClickRem  = remember { { pl: Playlist -> navController.navigate(PlaylistDetailRoute(pl.id)) } }
    val onToggleFavoriteRem = remember { viewModel::toggleFavorite }
    val onEditLyricsRem     = remember { { song: Song -> navController.navigate(LyricEditorRoute(song.id)) } }
    val onMiniPlayerClickRem= remember { { navController.navigate(NowPlayingRoute) } }
    val onPlayFavoritesRem  = remember(viewModel, songs) {
        {
            val favs = songs.filter { it.isFavorite }
            if (favs.isNotEmpty()) {
                viewModel.playSong(favs.first(), favs)
                navController.navigate(NowPlayingRoute)
            }
        }
    }

    LibraryScreen(
        songs               = songs,
        filteredSongs       = filteredSongs,
        playlists           = playlists,
        searchQuery         = state.searchQuery,
        sortOrder           = state.sortOrder,
        currentSong         = currentSong,
        isPlaying           = isPlaying,
        playbackProgress    = progress,
        dominantColor       = state.dominantColor,   // NEW — feeds MiniPlayer bg
        scanProgress        = scanProgress,
        onSongClick         = onSongClickRem,
        onPlaylistClick     = onPlaylistClickRem,
        onSearch            = viewModel::search,
        onSortChange        = viewModel::setSortOrder,
        onCreatePlaylist    = viewModel::createPlaylist,
        onDeletePlaylist    = viewModel::deletePlaylist,
        onRenamePlaylist    = viewModel::renamePlaylist,
        onAddSongToPlaylist = viewModel::addSongToPlaylist,
        onToggleFavorite    = onToggleFavoriteRem,
        onPlayNext          = viewModel::playNext,
        onAddToQueue        = viewModel::addToQueue,
        onEditLyrics        = onEditLyricsRem,
        onPlayFavorites     = onPlayFavoritesRem,
        onMiniPlayerClick   = onMiniPlayerClickRem,
        onMiniPlayPause     = viewModel::togglePlayPause,
        onMiniNext          = viewModel::next,
        onRescan            = viewModel::rescan
    )
}
*/

// ─────────────────────────────────────────────────────────────────────────────
// WHAT CHANGED IN EACH FILE
// ─────────────────────────────────────────────────────────────────────────────
//
// Components.kt — MiniPlayer
//   ADDED  detectHorizontalDragGestures — swipe left = next, right = cancel
//   ADDED  animatedDragOffset with spring — card physically follows finger
//   ADDED  IntOffset translation — card moves with drag
//   ADDED  spinning vinyl disc — CircleShape AsyncImage with frozen-angle rotation
//   ADDED  frozenAngle + LaunchedEffect — disc holds position when paused
//   ADDED  dominantColor parameter — horizontal gradient background
//   ADDED  animateColorAsState on bgColor — smooth transition between songs
//   ADDED  progress bar at bottom edge of card
//   KEPT   AlbumArtwork for the disc (has placeholder, better than raw AsyncImage)
//   KEPT   existing control layout (play/pause + next)
//
// NowPlayingScreen.kt
//   ADDED  Layer 1: AsyncImage blurred 60dp as full-screen atmosphere
//   ADDED  Layer 2: gradient overlay sits on top of blur (dominant color)
//   ADDED  nextSong derived from queue[queueIndex + 1]
//   ADDED  "Next Up" peek row — animated in/out, hidden during lyrics mode
//   KEPT   square album art + vinyl disc arrangement (superior to DreamMusic)
//   KEPT   lyric player, options menu, sleep timer chips, queue button
//   KEPT   frozen-angle vinyl (from Phase 1)
//   KEPT   RepeatMode alias (from Phase 1)
//
// LibraryScreen.kt
//   ADDED  HorizontalPager wrapping tab content — tabs are now swipeable
//   ADDED  pagerState + rememberCoroutineScope for animated tab transitions
//   ADDED  dominantColor parameter passed through to MiniPlayer
//   ADDED  scope.launch { pagerState.animateScrollToPage(idx) } on tab click
//   KEPT   scan progress bar + Refresh button (from Phase 2)
//   KEPT   SortOrder.entries (from Phase 1)
//   FIXED  tab index now comes from pagerState.currentPage (always in sync)
//
// MainActivity.kt (manual edit)
//   ADD    dominantColor = state.dominantColor to LibraryScreen(...)
//   (scanProgress and onRescan were already added in Phase 2)
//
// ─────────────────────────────────────────────────────────────────────────────
// GRADLE — ensure foundation dependency includes pager
// ─────────────────────────────────────────────────────────────────────────────
// HorizontalPager is in androidx.compose.foundation, same artifact as LazyColumn.
// If you're on Compose BOM 2024.02.00 or later it's included automatically.
// No new dependency needed.
//
// The blur() modifier requires API 31+. On older devices it degrades gracefully
// to no blur. To guard it explicitly:
//
//   .let { mod ->
//       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) mod.blur(60.dp) else mod
//   }
//
// ─────────────────────────────────────────────────────────────────────────────
