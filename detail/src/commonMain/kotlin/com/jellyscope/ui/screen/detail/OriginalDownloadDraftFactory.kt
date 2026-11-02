// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadBusinessKey
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.OfflineChapterSnapshot
import com.jellyscope.core.domain.model.OfflineMediaSnapshot
import com.jellyscope.core.domain.model.OfflineTrackKind
import com.jellyscope.core.domain.model.OfflineTrackSnapshot
import com.jellyscope.core.domain.model.OriginalDownloadDraft
import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.SubtitleKind
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.isExternalSubtitle
import com.jellyscope.core.domain.playback.subtitleKind
import com.jellyscope.core.domain.playback.subtitleStreamIdentities
import com.jellyscope.core.domain.playback.unambiguousAudioStreamIndexes

internal sealed interface OriginalSubtitleDraftResolution {
    data class Ready(
        val selection: DownloadSubtitleSelection,
        val stream: PlaybackMediaStream? = null,
    ) : OriginalSubtitleDraftResolution

    data class ExternalBitmap(
        val streamIndex: Int,
    ) : OriginalSubtitleDraftResolution

    data object Invalid : OriginalSubtitleDraftResolution
}

internal sealed interface OriginalDownloadDraftBuildResult {
    data class Ready(
        val draft: OriginalDownloadDraft,
    ) : OriginalDownloadDraftBuildResult

    data class ExternalBitmap(
        val streamIndex: Int,
    ) : OriginalDownloadDraftBuildResult

    data object Unsupported : OriginalDownloadDraftBuildResult
}

/**
 * Converts the already-projected detail selection into the untrusted, credential-free Original
 * draft. Admission remains responsible for permission, source, size, validator, and reservation
 * facts; this helper only carries the exact source/track choices the user saw. Audio resolves
 * only to an unambiguous actual stream index: an invalid explicit choice is refused rather than
 * substituted, and unselectable embedded Audio slots are saved with a null identity.
 */
internal fun buildOriginalDownloadDraft(
    detail: DetailUi,
    selectedAudioStreamIndex: Int?,
    subtitleSelection: SubtitleSelectionIntent,
    accountIdentity: AccountIdentity,
    downloadId: DownloadId,
    artifactKey: DownloadArtifactKey,
    createdAtEpochMs: Long,
): OriginalDownloadDraftBuildResult {
    if (detail.itemKind != MediaKind.Movie && detail.itemKind != MediaKind.Episode) {
        return OriginalDownloadDraftBuildResult.Unsupported
    }
    val version =
        detail.versions.selectedMediaVersion(detail.selectedMediaSourceId)
            ?: return OriginalDownloadDraftBuildResult.Unsupported
    val subtitle = version.resolveOriginalSubtitleSelection(subtitleSelection)
    if (subtitle is OriginalSubtitleDraftResolution.ExternalBitmap) {
        return OriginalDownloadDraftBuildResult.ExternalBitmap(subtitle.streamIndex)
    }
    val resolvedSubtitle =
        (subtitle as? OriginalSubtitleDraftResolution.Ready)
            ?: return OriginalDownloadDraftBuildResult.Unsupported
    val audioStreams = version.mediaStreams.filter { stream -> stream.type.equals("Audio", ignoreCase = true) }
    val eligibleAudioIndexes = unambiguousAudioStreamIndexes(version.mediaStreams)
    val eligibleAudio = audioStreams.filter { stream -> stream.eligibleAudioIndex(eligibleAudioIndexes) != null }
    val audio =
        if (selectedAudioStreamIndex != null) {
            eligibleAudio.firstOrNull { stream -> stream.index == selectedAudioStreamIndex }
                ?: return OriginalDownloadDraftBuildResult.Unsupported
        } else {
            eligibleAudio.firstOrNull { stream -> stream.index == version.trackSelection.defaultAudioStreamIndex }
                ?: eligibleAudio.firstOrNull { stream -> stream.isDefault == true }
                ?: eligibleAudio.firstOrNull()
        }
    if (audio == null && audioStreams.isNotEmpty()) {
        return OriginalDownloadDraftBuildResult.Unsupported
    }
    val snapshot =
        version.toOfflineMediaSnapshot(
            detail = detail,
            audio = audio,
            eligibleAudioIndexes = eligibleAudioIndexes,
            subtitle = resolvedSubtitle,
        ) ?: return OriginalDownloadDraftBuildResult.Unsupported
    return OriginalDownloadDraftBuildResult.Ready(
        OriginalDownloadDraft(
            downloadId = downloadId,
            businessKey =
                DownloadBusinessKey(
                    accountIdentity = accountIdentity,
                    itemId = detail.itemId,
                    mediaSourceId = version.id,
                ),
            selectedAudioStreamIndex = audio?.index,
            subtitleSelection = resolvedSubtitle.selection,
            artifactKey = artifactKey,
            snapshot = snapshot,
            createdAtEpochMs = createdAtEpochMs,
        ),
    )
}

internal fun MediaVersionUi.resolveOriginalSubtitleSelection(requested: SubtitleSelectionIntent): OriginalSubtitleDraftResolution {
    val effective =
        when (requested) {
            SubtitleSelectionIntent.Unspecified ->
                trackSelection.initialSubtitleSelection.takeUnless { it == SubtitleSelectionIntent.Unspecified }
                    ?: trackSelection.defaultSubtitleStreamIndex?.let(SubtitleSelectionIntent::Track)
                    ?: SubtitleSelectionIntent.Off
            else -> requested
        }
    return when (effective) {
        SubtitleSelectionIntent.Unspecified ->
            OriginalSubtitleDraftResolution.Ready(DownloadSubtitleSelection.Off)
        SubtitleSelectionIntent.Off -> OriginalSubtitleDraftResolution.Ready(DownloadSubtitleSelection.Off)
        is SubtitleSelectionIntent.LocalAsset ->
            OriginalSubtitleDraftResolution.Ready(
                DownloadSubtitleSelection.ExternalTextSidecar(effective.assetId),
            )
        is SubtitleSelectionIntent.Track -> {
            // Only an exact selectable identity; an invalid choice never substitutes Off or another track.
            val identity =
                subtitleStreamIdentities(mediaStreams)
                    .firstOrNull { candidate -> candidate.selectableIndex == effective.streamIndex }
                    ?: return OriginalSubtitleDraftResolution.Invalid
            val stream = identity.stream
            val canonicalIndex = effective.streamIndex
            if (stream.isExternalSubtitle()) {
                if (subtitleKind(stream.codec) == SubtitleKind.Bitmap) {
                    OriginalSubtitleDraftResolution.ExternalBitmap(canonicalIndex)
                } else {
                    OriginalSubtitleDraftResolution.Ready(
                        DownloadSubtitleSelection.ExternalServerTextSidecar(canonicalIndex),
                        stream,
                    )
                }
            } else {
                OriginalSubtitleDraftResolution.Ready(
                    DownloadSubtitleSelection.Embedded(canonicalIndex),
                    stream,
                )
            }
        }
    }
}

private fun MediaVersionUi.toOfflineMediaSnapshot(
    detail: DetailUi,
    audio: PlaybackMediaStream?,
    eligibleAudioIndexes: Set<Int>,
    subtitle: OriginalSubtitleDraftResolution.Ready,
): OfflineMediaSnapshot? {
    val streams = mediaStreams
    val selectedSubtitle =
        when (val selection = subtitle.selection) {
            DownloadSubtitleSelection.Off -> null
            is DownloadSubtitleSelection.Embedded ->
                subtitle.stream?.let { stream ->
                    streamSnapshot(
                        stream = stream,
                        kind = OfflineTrackKind.Subtitle,
                        streamIndex = selection.streamIndex,
                    )
                }
            is DownloadSubtitleSelection.ExternalServerTextSidecar ->
                subtitle.stream?.let { stream ->
                    OfflineTrackSnapshot(
                        kind = OfflineTrackKind.Subtitle,
                        streamIndex = selection.streamIndex,
                        codec = "vtt",
                        language = stream.language,
                        label = stream.displayTitle ?: stream.title,
                        isDefault = stream.isDefault == true,
                        isExternal = true,
                    )
                }
            is DownloadSubtitleSelection.ExternalTextSidecar ->
                trackSelection.localSubtitleOptions
                    .firstOrNull { asset -> asset.id == selection.localAssetId }
                    ?.let { asset ->
                        OfflineTrackSnapshot(
                            kind = OfflineTrackKind.Subtitle,
                            streamIndex = null,
                            codec = "vtt",
                            language = asset.language,
                            label = asset.label,
                            isDefault = false,
                            isExternal = true,
                        )
                    }
        }
    if (subtitle.selection !is DownloadSubtitleSelection.Off && selectedSubtitle == null) {
        return null
    }
    return runCatching {
        OfflineMediaSnapshot(
            title = detail.title,
            itemKind = detail.itemKind,
            seriesName = detail.seriesName,
            seasonLabel = detail.seasonNumber?.let { season -> "Season $season" },
            episodeLabel =
                if (detail.seasonNumber != null || detail.episodeNumber != null) {
                    listOfNotNull(
                        detail.seasonNumber?.let { season -> "S$season" },
                        detail.episodeNumber?.let { episode -> "E$episode" },
                    ).joinToString()
                } else {
                    null
                },
            durationMs = (runtimeMs ?: detail.runtimeMs)?.takeIf { duration -> duration > 0L },
            chapters = detail.chapters.map { chapter -> OfflineChapterSnapshot(chapter.name, chapter.startTicks) },
            sourcePresentation = releaseBasename ?: name,
            embeddedTracks =
                streams
                    .filter { stream ->
                        stream.type.equals("Audio", ignoreCase = true) && !stream.isExternalSubtitle()
                    }.map { stream ->
                        streamSnapshot(stream, OfflineTrackKind.Audio, stream.eligibleAudioIndex(eligibleAudioIndexes))
                    } +
                    // Identities are numbered over the whole Subtitle cohort before external entries
                    // are excluded; every embedded slot stays physical, unselectable ones with a null index.
                    subtitleStreamIdentities(streams)
                        .filterNot { identity -> identity.stream.isExternalSubtitle() }
                        .map { identity ->
                            streamSnapshot(identity.stream, OfflineTrackKind.Subtitle, identity.selectableIndex)
                        },
            selectedAudioTrack = audio?.let { stream -> streamSnapshot(stream, OfflineTrackKind.Audio, stream.index) },
            selectedSubtitleTrack = selectedSubtitle,
            backendSource =
                BackendSourceDescriptor(
                    container = container,
                    videoCodec = streams.firstOrNull { stream -> stream.type.equals("Video", ignoreCase = true) }?.codec,
                    audioCodec = audio?.codec,
                    isHdrOrDolbyVision =
                        streams.any { stream ->
                            stream.type.equals("Video", ignoreCase = true) &&
                                listOfNotNull(stream.videoRangeType, stream.codec)
                                    .any { value ->
                                        value.contains("hdr", ignoreCase = true) ||
                                            value.contains("dolby", ignoreCase = true) ||
                                            value.contains("vision", ignoreCase = true)
                                    }
                        },
                    videoWidth = streams.firstOrNull { stream -> stream.type.equals("Video", ignoreCase = true) }?.width,
                    videoHeight = streams.firstOrNull { stream -> stream.type.equals("Video", ignoreCase = true) }?.height,
                    videoFrameRate =
                        streams.firstOrNull { stream -> stream.type.equals("Video", ignoreCase = true) }?.realFrameRate,
                ),
        )
    }.getOrNull()
}

private fun streamSnapshot(
    stream: PlaybackMediaStream,
    kind: OfflineTrackKind,
    streamIndex: Int?,
): OfflineTrackSnapshot =
    OfflineTrackSnapshot(
        kind = kind,
        streamIndex = streamIndex,
        codec = stream.codec,
        language = stream.language,
        label = stream.displayTitle ?: stream.title,
        isDefault = stream.isDefault == true,
        isExternal = stream.isExternalSubtitle(),
    )

/** The stream's own index when it is an unambiguous Audio identity; never an ordinal substitute. */
private fun PlaybackMediaStream.eligibleAudioIndex(eligibleIndexes: Set<Int>): Int? =
    index?.takeIf { candidate -> candidate in eligibleIndexes }
