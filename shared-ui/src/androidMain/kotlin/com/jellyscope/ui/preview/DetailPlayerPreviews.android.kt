// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jellyscope.core.domain.playback.PlaybackAction
import com.jellyscope.core.domain.playback.PlaybackActionNotice
import com.jellyscope.core.domain.playback.PlaybackActionNoticeReason
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.screen.detail.AdaptiveDetailContent
import com.jellyscope.ui.screen.detail.AdaptiveSeriesContent
import com.jellyscope.ui.screen.detail.AmbientColorExtractor
import com.jellyscope.ui.screen.detail.DetailContent
import com.jellyscope.ui.screen.detail.DetailUiState
import com.jellyscope.ui.screen.detail.SeriesBodyCompact
import com.jellyscope.ui.screen.player.BufferingIndicator
import com.jellyscope.ui.screen.player.PlaybackActionNoticeBanner
import com.jellyscope.ui.screen.player.PlayerContent
import com.jellyscope.ui.screen.player.PlayerDebugOverlay
import com.jellyscope.ui.screen.player.SubtitleUnavailableBanner
import kotlinx.coroutines.flow.MutableStateFlow

@JellyScopeCompactPreviews
@Composable
private fun DetailCompactPreview() {
    JellyScopePreviewSurface(windowWidthTier = WindowWidthTier.Compact) {
        DetailContent(
            session = PreviewFixtures.session,
            state = DetailUiState.Content(PreviewFixtures.detail),
            onBack = {},
            onRetry = {},
            onPlayClick = { _, _, _, _, _ -> },
            onItemSelected = {},
            onPersonSelected = {},
            onToggleWatched = {},
            onToggleFavorite = {},
            ambientColorExtractor = AmbientColorExtractor { _, _ -> null },
        )
    }
}

@JellyScopeExpandedPreviews
@Composable
private fun DetailAdaptivePreview() {
    JellyScopePreviewSurface(safeArea = false) {
        AdaptiveDetailContent(
            session = PreviewFixtures.session,
            detail = PreviewFixtures.detail,
            onBack = {},
            onPlay = { _, _, _, _, _ -> },
            onToggleWatched = {},
            onToggleFavorite = {},
            onRelatedItemSelected = {},
            onPersonSelected = {},
            ambientColorExtractor = AmbientColorExtractor { _, _ -> null },
        )
    }
}

@JellyScopeCompactPreviews
@Composable
private fun SeriesCompactPreview() {
    JellyScopePreviewSurface(windowWidthTier = WindowWidthTier.Compact) {
        SeriesBodyCompact(
            session = PreviewFixtures.session,
            content = PreviewFixtures.seriesContent,
            onRetryEpisodes = {},
            onSeasonSelected = {},
            onEpisodeFocused = {},
            onToggleSeriesFavorite = {},
            onToggleEpisodeWatched = {},
            onToggleEpisodeFavorite = {},
            onSelectEpisodeMediaVersion = { _, _ -> },
            onPlayClick = { _, _, _, _, _ -> },
            onItemSelected = {},
            onPersonSelected = {},
            onMediaInfoClick = {},
            onBackdropLoaded = {},
        )
    }
}

@JellyScopeExpandedPreviews
@Composable
private fun SeriesAdaptivePreview() {
    JellyScopePreviewSurface(safeArea = false) {
        AdaptiveSeriesContent(
            session = PreviewFixtures.session,
            content = PreviewFixtures.seriesContent,
            onBack = {},
            onPlay = { _, _, _, _, _, _ -> },
            onSeasonSelected = {},
            onToggleFavorite = {},
            onRelatedItemSelected = {},
            onPersonSelected = {},
            ambientColorExtractor = AmbientColorExtractor { _, _ -> null },
        )
    }
}

@JellyScopeScreenPreviews
@Composable
private fun PlayerContentPreview() {
    JellyScopePreviewSurface(safeArea = false) {
        val playbackStateFlow = remember { MutableStateFlow(PreviewFixtures.playbackState) }
        PlayerContent(
            state = PreviewFixtures.playerState,
            session = PreviewFixtures.session,
            controller = PreviewFixtures.playerController(),
            playbackStateFlow = playbackStateFlow,
            playbackItemId = { null },
            onBack = {},
            onPlay = {},
            onPause = {},
            onSeekTo = {},
            onRetry = {},
            onShowPicker = {},
            onHidePicker = {},
            onSelectAudio = {},
            onSelectSubtitle = {},
            onSelectQuality = {},
            onSetPlaybackSpeed = {},
            onSetSubtitleStyle = {},
            onSetResizeMode = {},
            onPlayQueueItem = {},
            onShuffleQueue = {},
        )
    }
}

@JellyScopeScreenPreviews
@Composable
private fun PlayerDebugOverlayPreview() {
    JellyScopePreviewSurface(safeArea = false) {
        val playbackStateFlow = remember { MutableStateFlow(PreviewFixtures.playerState.playbackState) }
        val runtimeDiagnosticsFlow = remember { MutableStateFlow(PlaybackRuntimeDiagnostics.EMPTY) }
        PlayerDebugOverlay(
            debugInfo = PreviewFixtures.playerState.debugInfo ?: return@JellyScopePreviewSurface,
            playbackStateFlow = playbackStateFlow,
            runtimeDiagnosticsFlow = runtimeDiagnosticsFlow,
        )
    }
}

// The compact variant is the phone presentation: full width with wrapping rows
// rather than the 320dp card, so long capability/recovery values stay readable.
@JellyScopeScreenPreviews
@Composable
private fun PlayerDebugOverlayCompactPreview() {
    JellyScopePreviewSurface(safeArea = false) {
        val playbackStateFlow = remember { MutableStateFlow(PreviewFixtures.playerState.playbackState) }
        val runtimeDiagnosticsFlow = remember { MutableStateFlow(PlaybackRuntimeDiagnostics.EMPTY) }
        PlayerDebugOverlay(
            debugInfo = PreviewFixtures.playerState.debugInfo ?: return@JellyScopePreviewSurface,
            playbackStateFlow = playbackStateFlow,
            runtimeDiagnosticsFlow = runtimeDiagnosticsFlow,
            compact = true,
        )
    }
}

@JellyScopeScreenPreviews
@Composable
private fun PlaybackActionNoticePreview() {
    JellyScopePreviewSurface {
        PlaybackActionNoticeBanner(
            notice =
                PlaybackActionNotice(
                    reason = PlaybackActionNoticeReason.QualityRecoveryApplied,
                    actions =
                        setOf(
                            PlaybackAction.KeepCurrentQuality,
                            PlaybackAction.TryHigherQuality,
                            PlaybackAction.ChooseLowerQuality,
                            PlaybackAction.Dismiss,
                        ),
                ),
            onAction = {},
        )
    }
}

@JellyScopeScreenPreviews
@Composable
private fun BufferingIndicatorCompactPreview() {
    JellyScopePreviewSurface {
        BufferingIndicator(compact = true)
    }
}

@JellyScopeScreenPreviews
@Composable
private fun SubtitleUnavailableBannerPreview() {
    JellyScopePreviewSurface {
        SubtitleUnavailableBanner()
    }
}
