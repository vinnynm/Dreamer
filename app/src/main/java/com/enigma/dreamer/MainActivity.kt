package com.enigma.dreamer

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.enigma.devlyric.core.LyricFormat
import com.enigma.dreamer.core.MusicUiState
import com.enigma.dreamer.core.Playlist
import com.enigma.dreamer.core.Song
import kotlinx.serialization.Serializable
import com.enigma.dreamer.ui.screens.LibraryScreen
import com.enigma.dreamer.ui.screens.LyricEditorScreen
import com.enigma.dreamer.ui.screens.NowPlayingScreen
import com.enigma.dreamer.ui.screens.PlaylistDetailScreen
import com.enigma.dreamer.ui.screens.SettingsScreen
import com.enigma.dreamer.ui.theme.*
import com.enigma.dreamer.viewmodel.MusicViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MusicViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestPermissions()
        setContent {
            DevLyricApp(
                viewModel      = viewModel,
                openNowPlaying = intent?.getBooleanExtra("open_now_playing", false) == true
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("open_now_playing", false)) setIntent(intent)
    }

    private fun requestPermissions() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            add(Manifest.permission.FOREGROUND_SERVICE)
            add(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}

// ── Navigation routes ─────────────────────────────────────────────────────────

@Serializable object LibraryRoute
@Serializable object NowPlayingRoute
@Serializable object SettingsRoute
@Serializable data class PlaylistDetailRoute(val playlistId: Long)
@Serializable data class LyricEditorRoute(val songId: Long)

@Composable
fun DevLyricApp(viewModel: MusicViewModel, openNowPlaying: Boolean = false) {
    val uiState       by viewModel.uiState.collectAsState()
    val navController = rememberNavController()

    LaunchedEffect(openNowPlaying) {
        if (openNowPlaying) navController.navigate(NowPlayingRoute) { launchSingleTop = true }
    }

    // ── Derive dynamic theme colors from album art ────────────────────────────
    // Extract colors at this level so DreamerTheme can receive them and propagate
    // via LocalDynamicColors to the entire composition tree.
    val (dominantColor, accentTextColor) = when (val state = uiState) {
        is MusicUiState.Ready -> Color(state.dominantColor) to Color(state.accentTextColor)
        else                  -> Surface1 to TextPrimary
    }

    DreamerTheme(
        dominantColor   = dominantColor,
        accentTextColor = accentTextColor
    ) {
        when (val state = uiState) {
            is MusicUiState.Loading -> LoadingScreen()
            is MusicUiState.Error   -> ErrorScreen(state.message) { }
            is MusicUiState.Ready   -> {
                val ps                = state.playbackState
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(ps.error) {
                    ps.error?.let { viewModel.clearError(); snackbarHostState.showSnackbar(it) }
                }

                Box(Modifier.fillMaxSize()) {
                    NavHost(
                        navController    = navController,
                        startDestination = if (openNowPlaying) NowPlayingRoute else LibraryRoute,
                        enterTransition  = { fadeIn() + slideInVertically { it / 4 } },
                        exitTransition   = { fadeOut() + slideOutVertically { -it / 4 } }
                    ) {

                        composable<LibraryRoute> {
                            val songs           = state.songs
                            val filteredSongs   = state.filteredSongs
                            val playlists       = state.playlists
                            val currentSong     = ps.currentSong
                            val isPlaying       = ps.isPlaying
                            val progress        = ps.progress

                            // NEW: collect scan progress for the progress bar in LibraryScreen
                            val scanProgress by viewModel.scanProgress.collectAsState()

                            val onSongClickRem = remember(viewModel, filteredSongs) {
                                { song: Song ->
                                    viewModel.playSong(song, filteredSongs)
                                    navController.navigate(NowPlayingRoute)
                                }
                            }
                            val onPlaylistClickRem = remember {
                                { pl: Playlist -> navController.navigate(PlaylistDetailRoute(pl.id)) }
                            }
                            val onToggleFavoriteRem = remember { viewModel::toggleFavorite }
                            val onEditLyricsRem = remember {
                                { song: Song -> navController.navigate(LyricEditorRoute(song.id)) }
                            }
                            val onMiniPlayerClickRem = remember {
                                { navController.navigate(NowPlayingRoute) }
                            }
                            val onPlayFavoritesRem = remember(viewModel, songs) {
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
                                scanProgress        = scanProgress,          // NEW
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
                                onRescan            = viewModel::rescan       // NEW
                            )
                        }

                        composable<NowPlayingRoute> {
                            NowPlayingScreen(
                                playbackState      = ps,
                                currentLyricLine   = state.currentLyricLine,
                                showLyrics         = state.showLyrics,
                                showQueue          = state.showQueue,
                                dominantColor      = state.dominantColor,
                                accentTextColor    = state.accentTextColor,
                                onPlayPause        = viewModel::togglePlayPause,
                                onNext             = viewModel::next,
                                onPrevious         = viewModel::previous,
                                onSeek             = viewModel::seekTo,
                                onToggleRepeat     = viewModel::toggleRepeat,
                                onToggleShuffle    = viewModel::toggleShuffle,
                                onToggleLyrics     = viewModel::toggleLyrics,
                                onToggleFavorite   = { ps.currentSong?.let { viewModel.toggleFavorite(it) } },
                                onToggleQueue      = viewModel::toggleQueueView,
                                onSpeedChange      = viewModel::setPlaybackSpeed,
                                onStartSleepTimer  = viewModel::startSleepTimer,
                                onCancelSleepTimer = viewModel::cancelSleepTimer,
                                onOpenSettings     = { navController.navigate(SettingsRoute) },
                                onSkipToQueue      = viewModel::skipToQueueItem,
                                onBack             = { navController.popBackStack() }
                            )
                        }

                        composable<SettingsRoute> {
                            SettingsScreen(
                                playbackSpeed      = ps.playbackSpeed,
                                sortOrder          = state.sortOrder,
                                sleepTimer         = ps.sleepTimer,
                                onSpeedChange      = viewModel::setPlaybackSpeed,
                                onSortChange       = viewModel::setSortOrder,
                                onStartSleepTimer  = viewModel::startSleepTimer,
                                onCancelSleepTimer = viewModel::cancelSleepTimer,
                                onBack             = { navController.popBackStack() }
                            )
                        }

                        composable<PlaylistDetailRoute> { entry ->
                            val route    = entry.toRoute<PlaylistDetailRoute>()
                            val playlist = state.playlists.find { it.id == route.playlistId }
                            if (playlist != null) {
                                val songs = playlist.songIds.mapNotNull { id ->
                                    state.songs.find { it.id == id }
                                }
                                PlaylistDetailScreen(
                                    playlist    = playlist,
                                    songs       = songs,
                                    currentSong = ps.currentSong,
                                    isPlaying   = ps.isPlaying,
                                    onSongClick = { song ->
                                        viewModel.playSong(song, songs)
                                        navController.navigate(NowPlayingRoute)
                                    },
                                    onRemoveSong = { song ->
                                        viewModel.removeSongFromPlaylist(song, playlist.id)
                                    },
                                    onPlayAll = {
                                        viewModel.playPlaylist(playlist)
                                        navController.navigate(NowPlayingRoute)
                                    },
                                    onBack = { navController.popBackStack() }
                                )
                            } else {
                                navController.popBackStack()
                            }
                        }

                        composable<LyricEditorRoute> { entry ->
                            val route = entry.toRoute<LyricEditorRoute>()
                            val song  = state.songs.find { it.id == route.songId }

                            if (song == null) {
                                navController.popBackStack()
                                return@composable
                            }

                            var isSaving    by remember { mutableStateOf(false) }
                            var saveMessage by remember { mutableStateOf<String?>(null) }

                            LaunchedEffect(saveMessage) {
                                saveMessage?.let { msg ->
                                    snackbarHostState.showSnackbar(msg)
                                    saveMessage = null
                                }
                            }

                            LyricEditorScreen(
                                song          = song,
                                isSaving      = isSaving,
                                saveMessage   = saveMessage,
                                onSaveAndBake = { lyricText, format ->
                                    isSaving = true
                                    viewModel.bakeLyricsToSong(song, lyricText, format) { success, msg ->
                                        isSaving    = false
                                        saveMessage = msg
                                        if (success) navController.popBackStack()
                                    }
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier  = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 80.dp)
                    )
                }
            }
        }
    }
}

// ── Loading / Error ───────────────────────────────────────────────────────────

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(Amoled), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Amber, strokeWidth = 3.dp)
            Spacer(Modifier.height(16.dp))
            Text("Loading your music…", color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Amoled), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.ErrorOutline, null, tint = ErrorRed, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(12.dp))
            Text(message, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Amber)) {
                Text("Retry", color = Amoled)
            }
        }
    }
}
