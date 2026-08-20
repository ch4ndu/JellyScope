// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadBusinessKey
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.FixedDownloadDraft
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.toFixedDownloadSnapshot
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.QualityRung
import com.jellyscope.core.domain.playback.SubtitleKind
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.isExternalSubtitle
import com.jellyscope.core.domain.playback.qualityRungs
import com.jellyscope.core.domain.playback.subtitleKind

/** A fixed rung that is safe to display before the server admission estimate exists. */
internal data class FixedDownloadQualityChoice(
    val rung: QualityRung,
    val sourceBitrateKnown: Boolean,
)

internal data class FixedSubtitleChoice(
    val streamIndex: Int,
    val stream: PlaybackMediaStream,
)

/**
 * Projects the canonical conversion ladder from safe source metadata.
 *
 * The source is filtered only when both its video bitrate and dimensions are trustworthy. If
 * either fact is absent, every canonical rung remains visible and is labeled "up to" by the
 * chooser because the server may produce less than the cap.
 */
internal fun fixedDownloadQualityChoices(version: MediaVersionUi?): List<FixedDownloadQualityChoice> {
    val video = version?.videoStream() ?: return qualityRungs.map { rung -> FixedDownloadQualityChoice(rung, false) }
    val sourceBitrate = video.bitRate?.takeIf { value -> value > 0L }
    val sourceHeight = video.height?.takeIf { value -> value > 0 }
    val reliable = sourceBitrate != null && sourceHeight != null
    val rungs =
        if (!reliable) {
            qualityRungs
        } else {
            qualityRungs.filter { rung ->
                rung.maxBitrateBps < sourceBitrate || rung.height < sourceHeight
            }
        }
    return rungs.map { rung -> FixedDownloadQualityChoice(rung, reliable) }
}

internal fun MediaVersionUi.videoStream(): PlaybackMediaStream? =
    mediaStreams.firstOrNull { stream -> stream.type.equals("Video", ignoreCase = true) }

internal fun MediaVersionUi.fixedSupportedSubtitle(streamIndex: Int): PlaybackMediaStream? =
    fixedSubtitleOptions().firstOrNull { choice -> choice.streamIndex == streamIndex }?.stream

internal fun MediaVersionUi.hasUnsupportedFixedSubtitle(): Boolean =
    mediaStreams.any { stream ->
        stream.type.equals("Subtitle", ignoreCase = true) &&
            (
                stream.isExternalSubtitle() ||
                    stream.codec.normalizedFixedSubtitleCodec() == null ||
                    subtitleKind(stream.codec) == SubtitleKind.Bitmap
            )
    }

internal fun MediaVersionUi.fixedSubtitleOptions(): List<FixedSubtitleChoice> =
    mediaStreams
        .filter { stream -> stream.type.equals("Subtitle", ignoreCase = true) }
        .withIndex()
        .mapNotNull { (ordinal, stream) ->
            if (stream.isExternalSubtitle() ||
                stream.codec.normalizedFixedSubtitleCodec() == null ||
                subtitleKind(stream.codec) != SubtitleKind.Text
            ) {
                null
            } else {
                FixedSubtitleChoice(stream.index ?: ordinal, stream)
            }
        }

internal fun buildFixedDownloadDraft(
    detail: DetailUi,
    quality: DownloadQuality.Fixed,
    selectedAudioStreamIndex: Int?,
    subtitleSelection: SubtitleSelectionIntent,
    accountIdentity: AccountIdentity,
    downloadId: DownloadId,
    artifactKey: DownloadArtifactKey,
    createdAtEpochMs: Long,
    burnInConfirmed: Boolean,
): FixedDownloadDraftBuildResult {
    if (detail.itemKind != MediaKind.Movie && detail.itemKind != MediaKind.Episode) {
        return FixedDownloadDraftBuildResult.Unsupported
    }
    val version =
        detail.versions.selectedMediaVersion(detail.selectedMediaSourceId)
            ?: return FixedDownloadDraftBuildResult.Unsupported
    val resolvedSubtitle =
        when (val effective = subtitleSelection.effectiveFixedSelection()) {
            DownloadSubtitleSelection.Off -> FixedSubtitleDraftResolution.Ready(DownloadSubtitleSelection.Off)
            is DownloadSubtitleSelection.Embedded -> {
                val stream =
                    version.fixedSupportedSubtitle(effective.streamIndex)
                        ?: return FixedDownloadDraftBuildResult.UnsupportedSubtitle
                if (!burnInConfirmed) {
                    return FixedDownloadDraftBuildResult.NeedsBurnIn(
                        streamIndex = effective.streamIndex,
                        label = stream.displayTitle ?: stream.title ?: stream.language ?: "Subtitle",
                    )
                }
                FixedSubtitleDraftResolution.Ready(
                    DownloadSubtitleSelection.Embedded(
                        streamIndex = effective.streamIndex,
                        burnInConfirmed = true,
                    ),
                )
            }
            is DownloadSubtitleSelection.ExternalTextSidecar,
            is DownloadSubtitleSelection.ExternalServerTextSidecar,
            -> return FixedDownloadDraftBuildResult.UnsupportedSubtitle
        }

    val snapshotDraft =
        buildOriginalDownloadDraft(
            detail = detail,
            selectedAudioStreamIndex = selectedAudioStreamIndex,
            subtitleSelection = resolvedSubtitle.selection.toSubtitleIntent(),
            accountIdentity = accountIdentity,
            downloadId = downloadId,
            artifactKey = artifactKey,
            createdAtEpochMs = createdAtEpochMs,
        ) as? OriginalDownloadDraftBuildResult.Ready
            ?: return FixedDownloadDraftBuildResult.Unsupported
    val sourceSnapshot = snapshotDraft.draft.snapshot
    val effectiveAudioStreamIndex =
        sourceSnapshot.selectedAudioTrack?.streamIndex
            ?: return FixedDownloadDraftBuildResult.Unsupported
    val fixedSnapshot =
        sourceSnapshot.toFixedDownloadSnapshot(effectiveAudioStreamIndex)
            ?: return FixedDownloadDraftBuildResult.Unsupported
    return FixedDownloadDraftBuildResult.Ready(
        FixedDownloadDraft(
            downloadId = downloadId,
            businessKey =
                DownloadBusinessKey(
                    accountIdentity = accountIdentity,
                    itemId = detail.itemId,
                    mediaSourceId = version.id,
                ),
            quality = quality,
            selectedAudioStreamIndex = fixedSnapshot.selectedAudioTrack?.streamIndex,
            subtitleSelection = resolvedSubtitle.selection,
            artifactKey = artifactKey,
            snapshot = fixedSnapshot,
            createdAtEpochMs = createdAtEpochMs,
        ),
    )
}

internal sealed interface FixedDownloadDraftBuildResult {
    data class Ready(
        val draft: FixedDownloadDraft,
    ) : FixedDownloadDraftBuildResult

    data class NeedsBurnIn(
        val streamIndex: Int,
        val label: String,
    ) : FixedDownloadDraftBuildResult

    data object UnsupportedSubtitle : FixedDownloadDraftBuildResult

    data object Unsupported : FixedDownloadDraftBuildResult
}

private sealed interface FixedSubtitleDraftResolution {
    val selection: DownloadSubtitleSelection

    data class Ready(
        override val selection: DownloadSubtitleSelection,
    ) : FixedSubtitleDraftResolution
}

private fun SubtitleSelectionIntent.effectiveFixedSelection(): DownloadSubtitleSelection =
    when (this) {
        SubtitleSelectionIntent.Off,
        SubtitleSelectionIntent.Unspecified,
        -> DownloadSubtitleSelection.Off
        is SubtitleSelectionIntent.Track -> DownloadSubtitleSelection.Embedded(streamIndex, false)
        is SubtitleSelectionIntent.LocalAsset -> DownloadSubtitleSelection.ExternalTextSidecar(assetId)
    }

private fun DownloadSubtitleSelection.toSubtitleIntent(): SubtitleSelectionIntent =
    when (this) {
        DownloadSubtitleSelection.Off -> SubtitleSelectionIntent.Off
        is DownloadSubtitleSelection.Embedded -> SubtitleSelectionIntent.Track(streamIndex)
        is DownloadSubtitleSelection.ExternalTextSidecar -> SubtitleSelectionIntent.LocalAsset(localAssetId)
        is DownloadSubtitleSelection.ExternalServerTextSidecar -> SubtitleSelectionIntent.Track(streamIndex)
    }

private fun String?.normalizedFixedSubtitleCodec(): String? =
    this?.trim()?.lowercase()?.let { codec ->
        when (codec) {
            "srt", "subrip", "vtt", "webvtt", "ass", "ssa" -> codec
            else -> null
        }
    }
