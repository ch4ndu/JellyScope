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
    subtitleActivationRequestId: Long,
): PlaybackPlan {
    val chapters = snapshot.chapters.map { chapter -> Chapter(chapter.name, chapter.startTicks) }
    val artifactRef = OfflineArtifactRef(downloadId, attemptGeneration)
    val selectedSubtitleTrack = snapshot.selectedSubtitleTrack
    val embeddedAudioTracks =
        snapshot.embeddedTracks
            .filter { track -> track.kind == OfflineTrackKind.Audio && !track.isExternal }
            .mapIndexedNotNull { ordinal, track -> track.toPlannedEmbeddedTrack(ordinal) }
    val embeddedSubtitleTracks =
        snapshot.embeddedTracks
            .filter { track -> track.kind == OfflineTrackKind.Subtitle && !track.isExternal }
            .mapIndexedNotNull { ordinal, track -> track.toPlannedEmbeddedTrack(ordinal) }
    val selectedSidecar =
        selectedSubtitleTrack
            ?.takeIf { track ->
                artifactKind == DownloadArtifactKind.OriginalFile && track.isExternal
            }?.let { track ->
                val identity = SubtitleActivationIdentity.OfflineSidecar(artifactRef)
                val target =
                    SubtitleActivationTarget(
                        requestId = subtitleActivationRequestId,
                        itemId = itemId,
                        identity = identity,
                        kind = LocalSubtitleKind.ExternalText,
                    )
                PlannedSubtitle.OfflineSidecar(
                    identity = identity,
                    label = track.label,
                    language = track.language,
                    activationTarget = target,
                )
            }
    val selectedSubtitle =
        selectedSubtitleTrack
            ?.takeUnless(OfflineTrackSnapshot::isExternal)
            ?.streamIndex
            ?.let { streamIndex ->
                embeddedSubtitleTracks.firstOrNull { track -> track.jellyfinStreamIndex == streamIndex }
            }
    val selectedSubtitleStreamIndex = selectedSubtitle?.jellyfinStreamIndex
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
        embeddedAudioTracks = embeddedAudioTracks,
        embeddedSubtitleTracks = embeddedSubtitleTracks,
        selectedSubtitleStreamIndex = selectedSubtitleStreamIndex,
        plannedSubtitle =
            selectedSidecar ?: selectedSubtitle?.let { descriptor ->
                PlannedSubtitle.Track(
                    streamIndex = descriptor.jellyfinStreamIndex,
                    embeddedTrack = descriptor,
                    deliveryMethod = SubtitleDeliveryMethod.Embed,
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
        offlineArtifactRef = artifactRef,
        offlineArtifactKind = artifactKind,
        offlineAccountIdentity = accountIdentity,
    )
}

private fun OfflineTrackSnapshot.toPlannedEmbeddedTrack(ordinal: Int): PlannedEmbeddedTrack? {
    val jellyfinStreamIndex = streamIndex ?: return null
    return PlannedEmbeddedTrack(
        jellyfinStreamIndex = jellyfinStreamIndex,
        filteredContainerOrdinal = ordinal,
        codec = codec,
        normalizedLanguage = language,
        label = label,
        directPlayAdmissible = true,
        responseAuthoritativeCohortSize = null,
    )
}
