// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.playback.formattedResumePosition
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlin.time.Duration

internal fun Duration.formatted(): String = formattedResumePosition(this)

internal fun DetailUi.withWatched(played: Boolean): DetailUi {
    val updatedPlayAction =
        if (played) {
            playAction.copy(label = DetailPlayLabel.StartOver, startPositionTicks = 0L)
        } else {
            playAction.copy(label = DetailPlayLabel.Start, startPositionTicks = 0L)
        }
    return copy(
        playAction = updatedPlayAction,
        restartAction = if (played) null else restartAction,
        isWatched = played,
        progressFraction = if (played) null else progressFraction,
    )
}

internal fun DetailUi.withWatchedRollbackFrom(previous: DetailUi): DetailUi =
    copy(
        playAction =
            playAction.copy(
                label = previous.playAction.label,
                startPositionTicks = previous.playAction.startPositionTicks,
            ),
        restartAction = previous.restartAction?.copy(mediaSourceId = selectedMediaSourceId),
        isWatched = previous.isWatched,
        watchedToggleInFlight = false,
        progressFraction = previous.progressFraction,
    )

internal fun DetailUi.withSelectedMediaVersion(version: MediaVersionUi): DetailUi =
    copy(
        selectedMediaSourceId = version.id,
        selectedSourceReleaseBasename = version.releaseBasename,
        streamBadges = version.streamBadges,
        mediaInfo = version.mediaInfo,
        playAction = playAction.copy(mediaSourceId = version.id),
        restartAction = restartAction?.copy(mediaSourceId = version.id),
        trackSelection = version.trackSelection,
        versions = versions.map { candidate -> if (candidate.id == version.id) version else candidate },
    )

internal fun DetailUi.withLocalSubtitleAssets(
    mediaSourceId: String,
    assets: List<LocalSubtitleAsset>,
): DetailUi {
    if (selectedMediaSourceId != mediaSourceId || trackSelection.mediaSourceId != mediaSourceId) {
        return this
    }
    val updatedTrackSelection = trackSelection.copy(localSubtitleOptions = assets)
    return copy(
        trackSelection = updatedTrackSelection,
        versions =
            versions.map { version ->
                if (version.id == mediaSourceId) {
                    version.copy(trackSelection = updatedTrackSelection)
                } else {
                    version
                }
            },
    )
}

internal val detailViewModelLogger = diagnosticLogger(DiagnosticTag.DetailViewModel)
