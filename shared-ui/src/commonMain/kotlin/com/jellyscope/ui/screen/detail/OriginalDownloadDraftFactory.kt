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
 * facts; this helper only carries the exact source/track choices the user saw.
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
    val audioStreamIndex =
        selectedAudioStreamIndex
            ?: version.trackSelection.defaultAudioStreamIndex
            ?: version.audioStreamAt(0)?.canonicalIndex
    val snapshot =
        version.toOfflineMediaSnapshot(
            detail = detail,
            selectedAudioStreamIndex = audioStreamIndex,
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
            selectedAudioStreamIndex = audioStreamIndex,
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
            val indexed =
                mediaStreams
                    .filter { stream -> stream.type.equals("Subtitle", ignoreCase = true) }
                    .withIndex()
                    .firstOrNull { (ordinal, stream) ->
                        (
                            stream.index == effective.streamIndex ||
                                (stream.index == null && ordinal == effective.streamIndex)
                        )
                    } ?: return OriginalSubtitleDraftResolution.Invalid
            val stream = indexed.value
            val canonicalIndex = stream.index ?: indexed.index
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
    selectedAudioStreamIndex: Int?,
    subtitle: OriginalSubtitleDraftResolution.Ready,
): OfflineMediaSnapshot? {
    val streams = mediaStreams
    val audioIndexed =
        selectedAudioStreamIndex
            ?.let { index -> audioStreamAt(index) }
            ?: streams
                .filter { stream -> stream.type.equals("Audio", ignoreCase = true) }
                .withIndex()
                .firstOrNull { (_, stream) -> stream.isDefault == true }
                ?.let { indexed -> IndexedStream(indexed.index, indexed.value) }
            ?: streams
                .filter { stream -> stream.type.equals("Audio", ignoreCase = true) }
                .withIndex()
                .firstOrNull()
                ?.let { indexed -> IndexedStream(indexed.index, indexed.value) }
    val audio = audioIndexed?.stream
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
                    }.mapIndexed { ordinal, stream ->
                        streamSnapshot(stream, OfflineTrackKind.Audio, stream.index ?: ordinal)
                    } +
                    streams
                        .filter { stream ->
                            stream.type.equals("Subtitle", ignoreCase = true) && !stream.isExternalSubtitle()
                        }.mapIndexed { ordinal, stream ->
                            streamSnapshot(stream, OfflineTrackKind.Subtitle, stream.index ?: ordinal)
                        },
            selectedAudioTrack =
                audio?.let { stream ->
                    streamSnapshot(stream, OfflineTrackKind.Audio, audioIndexed?.canonicalIndex ?: 0)
                },
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
                ),
        )
    }.getOrNull()
}

private fun streamSnapshot(
    stream: PlaybackMediaStream,
    kind: OfflineTrackKind,
    streamIndex: Int,
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

private data class IndexedStream(
    val index: Int,
    val stream: PlaybackMediaStream,
) {
    val canonicalIndex: Int
        get() = stream.index ?: index
}

private fun MediaVersionUi.audioStreamAt(index: Int): IndexedStream? =
    mediaStreams
        .filter { stream -> stream.type.equals("Audio", ignoreCase = true) }
        .withIndex()
        .firstOrNull { (ordinal, stream) ->
            (stream.index == index || (stream.index == null && ordinal == index))
        }?.let { indexed -> IndexedStream(indexed.index, indexed.value) }
