package com.enigma.dreamer.ui.screens

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.enigma.dreamer.core.EqState
import com.enigma.dreamer.core.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * 11.4 — Predictive Back wrapper for [NowPlayingScreen].
 *
 * Wraps the existing composable in a [PredictiveBackHandler] that animates the
 * screen shrinking and sliding down toward the MiniPlayer as the user performs
 * the system back gesture. The animation follows the gesture progress in
 * real-time; if the user releases before crossing the threshold the screen
 * springs back.
 *
 * Requirements:
 *  - `android:enableOnBackInvokedCallback="true"` in AndroidManifest.xml
 *    `<application>` element (needed on API 33; default on API 34+).
 *  - `androidx.activity:activity-compose:1.9+` (should already be transitive).
 *
 * In [MainActivity], replace the `composable<NowPlayingRoute>` block's
 * [NowPlayingScreen] call with [NowPlayingScreenWithPredictiveBack].
 */
@Composable
fun NowPlayingScreenWithPredictiveBack(
    playbackState: PlaybackState,
    currentLyricLine: Int,
    showLyrics: Boolean,
    showQueue: Boolean,
    dominantColor: Int,
    accentTextColor: Int,
    pitchSemitones: Int = 0,
    eqState: EqState = EqState(),
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleLyrics: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleQueue: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onStartSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onOpenSettings: () -> Unit,
    onSkipToQueue: (Int) -> Unit,
    onToggleEq: (Boolean) -> Unit,
    onEqPresetChange: (Short) -> Unit,
    onEqBassChange: (Short) -> Unit,
    onSetPitch: (Int) -> Unit,
    onBack: () -> Unit
) {
    // ── Predictive Back animation state ───────────────────────────────────────
    val backProgress  = remember { Animatable(0f) }
    var swipeEdge     by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }

    PredictiveBackHandler(enabled = true) { backEvents: Flow<BackEventCompat> ->
        try {
            backEvents.collect { event ->
                backProgress.snapTo(event.progress)
                swipeEdge = event.swipeEdge
            }
            // Gesture completed: snap to full progress then navigate
            backProgress.animateTo(1f, tween(120))
            onBack()
            backProgress.snapTo(0f)
        } catch (_: CancellationException) {
            // Gesture cancelled: spring back to full size
            backProgress.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    // ── Animation values ──────────────────────────────────────────────────────
    val p             = backProgress.value
    val scale         = 1f - p * 0.14f
    val translateX    = p * (if (swipeEdge == BackEventCompat.EDGE_LEFT) -48f else 48f)
    val translateY    = p * 100f        // screen slides toward bottom (MiniPlayer area)
    val alpha         = 1f - p * 0.45f
    val cornerRadius  = (p * 36f).dp

    NowPlayingScreen(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX       = scale
                scaleY       = scale
                translationX = translateX.dp.toPx()
                translationY = translateY.dp.toPx()
                this.alpha   = alpha
            }
            .clip(RoundedCornerShape(cornerRadius)),
        playbackState      = playbackState,
        currentLyricLine   = currentLyricLine,
        showLyrics         = showLyrics,
        showQueue          = showQueue,
        dominantColor      = dominantColor,
        accentTextColor    = accentTextColor,
        pitchSemitones     = pitchSemitones,
        eqState            = eqState,
        onPlayPause        = onPlayPause,
        onNext             = onNext,
        onPrevious         = onPrevious,
        onSeek             = onSeek,
        onToggleRepeat     = onToggleRepeat,
        onToggleShuffle    = onToggleShuffle,
        onToggleLyrics     = onToggleLyrics,
        onToggleFavorite   = onToggleFavorite,
        onToggleQueue      = onToggleQueue,
        onSpeedChange      = onSpeedChange,
        onStartSleepTimer  = onStartSleepTimer,
        onCancelSleepTimer = onCancelSleepTimer,
        onOpenSettings     = onOpenSettings,
        onSkipToQueue      = onSkipToQueue,
        onToggleEq         = onToggleEq,
        onEqPresetChange   = onEqPresetChange,
        onEqBassChange     = onEqBassChange,
        onSetPitch         = onSetPitch,
        onBack             = onBack
    )
}
