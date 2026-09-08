// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.OfflineArtifactRef
import com.jellyscope.core.domain.model.OfflineMediaSnapshot
import com.jellyscope.core.domain.model.OfflineTrackKind
import com.jellyscope.core.domain.model.OfflineTrackSnapshot

fun buildOfflinePlaybackPlan(
    snapshot: OfflineMediaSnapshot,
    itemId: String,
    mediaSourceId: String,
    downloadId: DownloadId,
    attemptGeneration: Long,
    artifactKind: DownloadArtifactKind,
    accountIdentity: AccountIdentity,
    startPositionTicks: Long,
    localResumePositionMs: Long,
): PlaybackPlan {
    val chapters = snapshot.chapters.map { chapter -> Chapter(chapter.name, chapter.startTicks) }
    val selectedSubtitle = snapshot.selectedSubtitleTrack?.toPlannedEmbeddedTrack(0)
    val selectedSubtitleStreamIndex = snapshot.selectedSubtitleTrack?.streamIndex
    return PlaybackPlan(
        itemId = itemId,
        mediaSourceId = mediaSourceId,
        startPositionMs =
            if (startPositionTicks > 0L) {
                ticksToMilliseconds(startPositionTicks)
            } else {
                localResumePositionMs
            },
        streamMode = StreamMode.Offline,
        streamUrl = "",
        progressReportingPolicy =
            ProgressReportingPolicy(
                reportIntervalMs = DEFAULT_PLAYBACK_REPORT_INTERVAL_MS,
            ),
        selectedAudioStreamIndex = snapshot.selectedAudioTrack?.streamIndex,
        embeddedAudioTracks =
            snapshot.embeddedTracks
                .filter { track -> track.kind == OfflineTrackKind.Audio && !track.isExternal }
                .mapIndexed { ordinal, track -> track.toPlannedEmbeddedTrack(ordinal) },
        embeddedSubtitleTracks =
            snapshot.embeddedTracks
                .filter { track -> track.kind == OfflineTrackKind.Subtitle && !track.isExternal }
                .mapIndexed { ordinal, track -> track.toPlannedEmbeddedTrack(ordinal) },
        selectedSubtitleStreamIndex = selectedSubtitleStreamIndex,
        plannedSubtitle =
            selectedSubtitle?.let { descriptor ->
                PlannedSubtitle.Track(
                    streamIndex = descriptor.jellyfinStreamIndex,
                    embeddedTrack = descriptor.takeUnless { snapshot.selectedSubtitleTrack?.isExternal == true },
                    deliveryMethod =
                        if (snapshot.selectedSubtitleTrack?.isExternal == true) {
                            SubtitleDeliveryMethod.External
                        } else {
                            SubtitleDeliveryMethod.Embed
                        },
                    kind = SubtitleKind.Text,
                )
            } ?: PlannedSubtitle.Off,
        chapters = chapters,
        contentTimeline =
            snapshot.durationMs?.takeIf { duration -> duration > 0L }?.let { duration ->
                PlaybackContentTimeline.BoundedVod(
                    durationMs = duration,
                    source = PlaybackContentTimelineSource.SelectedMediaSource,
                )
            } ?: PlaybackContentTimeline.UnknownOrUnbounded,
        videoExpected = true,
        container = snapshot.backendSource.container,
        offlineArtifactRef = OfflineArtifactRef(downloadId, attemptGeneration),
        offlineArtifactKind = artifactKind,
        offlineAccountIdentity = accountIdentity,
    )
}

private fun OfflineTrackSnapshot.toPlannedEmbeddedTrack(ordinal: Int): PlannedEmbeddedTrack =
    PlannedEmbeddedTrack(
        jellyfinStreamIndex = streamIndex ?: ordinal,
        filteredContainerOrdinal = ordinal,
        codec = codec,
        normalizedLanguage = language,
        label = label,
        directPlayAdmissible = true,
        responseAuthoritativeCohortSize = null,
    )
