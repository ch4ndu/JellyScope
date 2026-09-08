// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.OfflineMediaSnapshot
import com.jellyscope.core.domain.model.OfflineTrackKind
import com.jellyscope.core.domain.model.OfflineTrackSnapshot
import com.jellyscope.core.domain.playback.Chapter
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.buildOfflinePlaybackPlan

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
    val offlinePlan: PlaybackPlan,
)

internal fun projectOfflinePlayback(input: OfflinePlaybackProjectionInput): OfflinePlaybackProjection {
    val snapshot = input.snapshot
    val chapters = snapshot.chapters.map { chapter -> Chapter(chapter.name, chapter.startTicks) }
    val selectedSubtitleStreamIndex = snapshot.selectedSubtitleTrack?.streamIndex
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
        offlinePlan =
            buildOfflinePlaybackPlan(
                snapshot = snapshot,
                itemId = input.itemId,
                mediaSourceId = input.mediaSourceId,
                downloadId = input.downloadId,
                attemptGeneration = input.attemptGeneration,
                artifactKind = input.artifactKind,
                accountIdentity = input.accountIdentity,
                startPositionTicks = input.startPositionTicks,
                localResumePositionMs = input.localResumePositionMs,
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
