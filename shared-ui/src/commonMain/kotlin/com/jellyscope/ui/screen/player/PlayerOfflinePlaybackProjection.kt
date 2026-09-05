// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.OfflineArtifactRef
import com.jellyscope.core.domain.model.OfflineMediaSnapshot
import com.jellyscope.core.domain.model.OfflineTrackKind
import com.jellyscope.core.domain.model.OfflineTrackSnapshot
import com.jellyscope.core.domain.playback.Chapter
import com.jellyscope.core.domain.playback.DEFAULT_PLAYBACK_REPORT_INTERVAL_MS
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.PlannedEmbeddedTrack
import com.jellyscope.core.domain.playback.PlannedSubtitle
import com.jellyscope.core.domain.playback.PlaybackContentTimeline
import com.jellyscope.core.domain.playback.PlaybackContentTimelineSource
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleActivationIdentity
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import com.jellyscope.core.domain.playback.SubtitleDeliveryMethod
import com.jellyscope.core.domain.playback.SubtitleKind
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.ticksToMilliseconds

internal data class OfflinePlaybackProjectionInput(
    val snapshot: OfflineMediaSnapshot,
    val itemId: String,
    val mediaSourceId: String,
    val downloadId: DownloadId,
    val attemptGeneration: Long,
    val artifactKind: DownloadArtifactKind,
    val accountIdentity: AccountIdentity,
    val startPositionTicks: Long,
    val localResumePositionMs: Long,
    val launchGeneration: Long,
    val subtitleActivationRequestId: Long,
)

internal data class OfflinePlaybackProjection(
    val metadata: PlayerMediaMetadata,
    val timelineFacts: ActivePlaybackTimelineFacts,
    val chapters: List<Chapter>,
    val sourceContainer: String?,
    val mediaStreams: List<PlaybackMediaStream>,
    val selectedAudioStreamIndex: Int?,
    val selectedSubtitleStreamIndex: Int?,
    val selectedSubtitleSelection: SubtitleSelectionIntent,
    val offlineSidecarOption: OfflineSidecarOption?,
    val offlinePlan: PlaybackPlan,
)

internal fun projectOfflinePlayback(input: OfflinePlaybackProjectionInput): OfflinePlaybackProjection {
    val snapshot = input.snapshot
    val chapters = snapshot.chapters.map { chapter -> Chapter(chapter.name, chapter.startTicks) }
    val artifactRef = OfflineArtifactRef(input.downloadId, input.attemptGeneration)
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
                input.artifactKind == DownloadArtifactKind.OriginalFile && track.isExternal
            }?.let { track ->
                val identity = SubtitleActivationIdentity.OfflineSidecar(artifactRef)
                val target =
                    SubtitleActivationTarget(
                        requestId = input.subtitleActivationRequestId,
                        itemId = input.itemId,
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
    val offlineSidecarOption =
        selectedSidecar?.let { sidecar ->
            OfflineSidecarOption(
                identity = sidecar.identity,
                displayName = sidecar.label,
                language = sidecar.language,
            )
        }
    return OfflinePlaybackProjection(
        metadata =
            PlayerMediaMetadata(
                title = snapshot.title,
                seriesName = snapshot.seriesName,
                episodeLabel = snapshot.episodeLabel,
                runtimeMs = snapshot.durationMs,
                qualityBadge = snapshot.sourcePresentation?.takeIf { value -> value.isNotBlank() },
                imageUrl = null,
            ),
        timelineFacts =
            ActivePlaybackTimelineFacts(
                generation = input.launchGeneration,
                itemId = input.itemId,
                kind = snapshot.itemKind,
                isLive = false,
                itemRuntimeMs = snapshot.durationMs,
                sourcesById =
                    mapOf(
                        input.mediaSourceId to
                            ActivePlaybackTimelineSourceFacts(
                                runtimeMs = snapshot.durationMs,
                                isInfiniteStream = false,
                            ),
                    ),
            ),
        chapters = chapters,
        sourceContainer = snapshot.backendSource.container,
        mediaStreams = snapshot.embeddedTracks.map(OfflineTrackSnapshot::toPlaybackMediaStream),
        selectedAudioStreamIndex = snapshot.selectedAudioTrack?.streamIndex,
        selectedSubtitleStreamIndex = selectedSubtitleStreamIndex,
        selectedSubtitleSelection =
            selectedSubtitleStreamIndex?.let(SubtitleSelectionIntent::Track)
                ?: SubtitleSelectionIntent.Off,
        offlineSidecarOption = offlineSidecarOption,
        offlinePlan =
            PlaybackPlan(
                itemId = input.itemId,
                mediaSourceId = input.mediaSourceId,
                startPositionMs =
                    if (input.startPositionTicks > 0L) {
                        ticksToMilliseconds(input.startPositionTicks)
                    } else {
                        input.localResumePositionMs
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
                offlineArtifactKind = input.artifactKind,
                offlineAccountIdentity = input.accountIdentity,
            ),
    )
}

private fun OfflineTrackSnapshot.toPlaybackMediaStream(): PlaybackMediaStream =
    PlaybackMediaStream(
        index = streamIndex,
        type = if (kind == OfflineTrackKind.Audio) "Audio" else "Subtitle",
        displayTitle = label,
        title = label,
        language = language,
        codec = codec,
        channelLayout = null,
        bitRate = null,
        height = null,
        isDefault = isDefault,
        isExternal = isExternal,
        deliveryMethod = if (isExternal) "External" else "Embedded",
        deliveryUrl = null,
    )

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
