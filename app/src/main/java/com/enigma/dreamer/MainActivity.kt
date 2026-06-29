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
import com.enigma.dreamer.core.*
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

    // FIX: Two bugs corrected here.
    //
    // BUG 1 — Install-time permissions in the runtime launcher:
    //   FOREGROUND_SERVICE and FOREGROUND_SERVICE_MEDIA_PLAYBACK are install-time
    //   permissions declared in AndroidManifest.xml. They cannot be requested via
    //   ActivityResultContracts.RequestMultiplePermissions(). Passing them to the
    //   launcher causes it to behave unpredictably on API 34+ — it may reject the
    //   entire request batch, meaning READ_MEDIA_AUDIO is never shown to the user.
    //   Fix: only request genuine runtime permissions here.
    //
    // BUG 2 — Empty launcher callback, no rescan after grant:
    //   MusicViewModel.init {} calls loadAll() immediately on creation, before the
    //   permission dialog is shown. On first install READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE
    //   isn't granted yet, so scanAndSync() returns an empty list and zero songs are cached.
    //   When the user grants permission in the dialog, nothing triggers a re-scan.
    //   Fix: call viewModel.rescan() from the launcher callback when the storage
    //   read permission has just been granted.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Check if the storage read permission was just granted
        val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        val storageGranted = results[storagePermission] == true
        if (storageGranted) {
            // Trigger a fresh scan now that we can actually read the MediaStore.
            // rescan() is a no-op if the library is already populated (e.g. on
            // subsequent launches where permission was granted previously).
            viewModel.rescan()
        }
    }

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
        // BUG 1 FIX: Only request runtime permissions.
        //   FOREGROUND_SERVICE and FOREGROUND_SERVICE_MEDIA_PLAYBACK must NOT appear
        //   here — they are install-time permissions that belong only in the manifest.
        //   Including them in this list causes the launcher to fail silently on
        //   Android 14 (API 34), which may skip showing READ_MEDIA_AUDIO entirely.
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+: granular media permission replaces READ_EXTERNAL_STORAGE
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            // WRITE needed to bake lyrics into audio files on API 28 and below.
            // On API 29+ MediaStore write-with-URI is used; no manifest permission needed.
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            // DO NOT add FOREGROUND_SERVICE or FOREGROUND_SERVICE_MEDIA_PLAYBACK here.
            // Those are declared in AndroidManifest.xml and granted automatically at install.
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
    val uiState     by viewModel.uiState.collectAsState()
    val libState    by viewModel.libraryState.collectAsState()
    val playerState by viewModel.playerState.collectAsState()

    val navController = rememberNavController()

    LaunchedEffect(openNowPlaying) {
        if (openNowPlaying) navController.navigate(NowPlayingRoute) { launchSingleTop = true }
    }

    val dominantColor   = Color(playerState.dominantColor)
    val accentTextColor = Color(playerState.accentTextColor)

    DreamerTheme(
        dominantColor   = dominantColor,
        accentTextColor = accentTextColor
    ) {
        when (uiState) {
            is MusicUiState.Loading -> LoadingScreen()
            is MusicUiState.Error   -> ErrorScreen((uiState as MusicUiState.Error).message) { }
            is MusicUiState.Ready   -> {
                val ps                = playerState.playbackState
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
                            val scanProgress by viewModel.scanProgress.collectAsState()

                            val onSongClickRem = remember(viewModel, libState.filteredSongs) {
                                { song: Song ->
                                    viewModel.playSong(song, libState.filteredSongs)
                                    navController.navigate(NowPlayingRoute)
                                }
                            }
                            val onPlaylistClickRem  = remember {
                                { pl: Playlist -> navController.navigate(PlaylistDetailRoute(pl.id)) }
                            }
                            val onToggleFavoriteRem = remember { viewModel::toggleFavorite }
                            val onEditLyricsRem     = remember {
                                { song: Song -> navController.navigate(LyricEditorRoute(song.id)) }
                            }
                            val onMiniPlayerClickRem = remember {
                                { navController.navigate(NowPlayingRoute) }
                            }
                            val onPlayFavoritesRem = remember(viewModel, libState.songs) {
                                {
                                    val favs = libState.songs.filter { it.isFavorite }
                                    if (favs.isNotEmpty()) {
                                        viewModel.playSong(favs.first(), favs)
                                        navController.navigate(NowPlayingRoute)
                                    }
                                }
                            }

                            LibraryScreen(
                                songs               = libState.songs,
                                filteredSongs       = libState.filteredSongs,
                                playlists           = libState.playlists,
                                searchQuery         = libState.searchQuery,
                                sortOrder           = libState.sortOrder,
                                currentSong         = ps.currentSong,
                                isPlaying           = ps.isPlaying,
                                playbackProgress    = ps.progress,
                                dominantColor       = playerState.dominantColor,
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
                                onMiniPrevious      = viewModel::previous,
                                onRescan            = viewModel::rescan
                            )
                        }

                        composable<NowPlayingRoute> {
                            NowPlayingScreenWithPredictiveBack(
                                playbackState      = ps,
                                currentLyricLine   = playerState.currentLyricLine,
                                showLyrics         = playerState.showLyrics,
                                showQueue          = playerState.showQueue,
                                dominantColor      = playerState.dominantColor,
                                accentTextColor    = playerState.accentTextColor,
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
                                onBack             = { navController.popBackStack() },
                                eqState            = playerState.eqState,
                                onToggleEq         = viewModel::setEqEnabled,
                                onEqPresetChange   = viewModel::setEqPreset,
                                onEqBassChange     = viewModel::setEqBassBoost,
                                pitchSemitones     = playerState.pitchSemitones,
                                onSetPitch         = viewModel::setPitch
                            )
                        }

                        composable<SettingsRoute> {
                            SettingsScreen(
                                playbackSpeed      = ps.playbackSpeed,
                                sortOrder          = libState.sortOrder,
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
                            val playlist = libState.playlists.find { it.id == route.playlistId }
                            if (playlist != null) {
                                val songs = playlist.songIds.mapNotNull { id ->
                                    libState.songs.find { it.id == id }
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
                                    onReorderSongs = { newIds ->
                                        viewModel.reorderPlaylistSongs(playlist.id, newIds)
                                    },
                                    onBack = { navController.popBackStack() }
                                )
                            } else {
                                navController.popBackStack()
                            }
                        }

                        composable<LyricEditorRoute> { entry ->
                            val route = entry.toRoute<LyricEditorRoute>()
                            val song = libState.songs.find { it.id == route.songId }

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
                                song              = song,
                                currentPositionMs = ps.positionMs,
                                isPlaying         = ps.isPlaying,
                                onPlayPause       = viewModel::togglePlayPause,
                                onSeek            = viewModel::seekTo,
                                isSaving          = isSaving,
                                saveMessage       = saveMessage,
                                onSaveAndBake     = { lyricText, format ->
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