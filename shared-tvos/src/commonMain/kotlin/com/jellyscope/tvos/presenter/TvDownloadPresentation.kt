// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadBusinessKey
import com.jellyscope.core.domain.model.DownloadFailure
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadSettings
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.DownloadUsage
import com.jellyscope.core.domain.model.FixedDownloadDraft
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaVersion
import com.jellyscope.core.domain.model.OfflineChapterSnapshot
import com.jellyscope.core.domain.model.OfflineMediaSnapshot
import com.jellyscope.core.domain.model.OfflinePersonCreditType
import com.jellyscope.core.domain.model.OfflineTrackKind
import com.jellyscope.core.domain.model.OfflineTrackSnapshot
import com.jellyscope.core.domain.model.OriginalDownloadDraft
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.model.toFixedDownloadSnapshot
import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.QualityRung
import com.jellyscope.core.domain.playback.SubtitleKind
import com.jellyscope.core.domain.playback.isExternalSubtitle
import com.jellyscope.core.domain.playback.qualityRungs
import com.jellyscope.core.domain.playback.subtitleKind
import com.jellyscope.core.domain.playback.ticksToMilliseconds

internal fun tvDownloadSections(
    records: List<DownloadRecord>,
    playableDownloadIds: Set<String>,
    leasedDownloadIds: Set<String>,
): List<TvDownloadSection> {
    val rows = records.map { record -> record.toTvDownloadRow(playableDownloadIds, leasedDownloadIds) }
    return listOf(
        TvDownloadSectionKind.Completed to rows.filter { row -> row.state == TvDownloadRowState.Completed },
        TvDownloadSectionKind.Active to
            rows.filter { row -> row.state == TvDownloadRowState.Downloading || row.state == TvDownloadRowState.Finalizing },
        TvDownloadSectionKind.Queued to rows.filter { row -> row.state == TvDownloadRowState.Queued },
        TvDownloadSectionKind.Paused to
            rows.filter { row -> row.state == TvDownloadRowState.Paused || row.state == TvDownloadRowState.BlockedByQuota },
        TvDownloadSectionKind.Failed to rows.filter { row -> row.state == TvDownloadRowState.Failed },
    ).mapNotNull { (kind, sectionRows) ->
        sectionRows
            .takeIf { rowsInSection -> rowsInSection.isNotEmpty() }
            ?.let { nonEmpty -> TvDownloadSection(kind, nonEmpty) }
    }
}

internal fun DownloadUsage.toTvDownloadStorageState(settings: DownloadSettings): TvDownloadStorageState =
    TvDownloadStorageState(
        physicalBytes = physicalBytes,
        currentAccountPhysicalBytes = currentAccountPhysicalBytes,
        otherAccountsPhysicalBytes = otherAccountsPhysicalBytes,
        outstandingReservationBytes = outstandingReservationBytes,
        projectedCommittedBytes = projectedCommittedBytes,
        quotaBytes = settings.quotaBytes,
        remainingQuotaBytes = remainingQuotaBytes,
        deviceAvailableBytes = deviceAvailableBytes,
        safetyReserveBytes = safetyReserveBytes,
        maximumConfigurableQuotaBytes = maximumConfigurableQuotaBytes,
        overAllocation = overAllocation,
    )

private fun DownloadRecord.toTvDownloadRow(
    playableDownloadIds: Set<String>,
    leasedDownloadIds: Set<String>,
): TvDownloadRow {
    val id = downloadId.value
    val snapshot = request.snapshot
    val detail = snapshot.detail?.toTvDownloadDetail()
    val people = detail?.people.orEmpty()
    val leased = id in leasedDownloadIds
    val playable = state == DownloadState.Completed && id in playableDownloadIds
    val expectedBytes = maxOf(reservationBytes, request.admissionEstimateBytes, physicalBytes)
    val progressBytes = maxOf(checkpointBytes, physicalBytes)
    val progress = if (expectedBytes == 0L) 0.0 else (progressBytes.toDouble() / expectedBytes.toDouble()).coerceIn(0.0, 1.0)
    return TvDownloadRow(
        id = id,
        attemptGeneration = attemptGeneration,
        title = snapshot.title,
        itemKind = snapshot.itemKind,
        seriesName = snapshot.seriesName,
        seasonLabel = snapshot.seasonLabel,
        episodeLabel = snapshot.episodeLabel,
        secondaryTitle =
            listOfNotNull(snapshot.seriesName, snapshot.episodeLabel)
                .takeIf { labels -> labels.isNotEmpty() }
                ?.joinToString(" · "),
        sourceLabel = snapshot.sourcePresentation,
        durationMs = snapshot.durationMs,
        chapters = snapshot.chapters.map { chapter -> TvChapter(chapter.name, ticksToMilliseconds(chapter.startTicks)) },
        audioTracks = snapshot.offlineAudioChoices(snapshot.selectedAudioTrack?.streamIndex),
        subtitleTracks =
            snapshot.offlineSubtitleChoices(
                selectedChoiceKey = snapshot.selectedSubtitleTrack?.streamIndex,
                localSubtitleChoiceKey = null,
            ),
        backend = snapshot.backendSource.toTvDownloadBackendInfo(),
        detail = detail,
        cast = people.filter { person -> person.creditType == OfflinePersonCreditType.Cast },
        crew = people.filter { person -> person.creditType == OfflinePersonCreditType.Crew },
        otherPeople = people.filter { person -> person.creditType == OfflinePersonCreditType.Other },
        state = state.toTvDownloadRowState(),
        presentationBytes = presentationBytes,
        qualityBitrateBps = (request.quality as? DownloadQuality.Fixed)?.maxBitrateBps,
        physicalBytes = physicalBytes,
        expectedBytes = expectedBytes,
        progressFraction = if (state == DownloadState.Completed) 1.0 else progress,
        localResumePositionMs = localResumePositionMs,
        localWatched = localWatched,
        failure = failure?.toTvDownloadFailureKind(),
        canPlay = playable,
        canRestart = playable && localResumePositionMs > 0L,
        canPause = state == DownloadState.Downloading,
        canResume = state == DownloadState.Paused || state == DownloadState.BlockedByQuota,
        canRetry = state == DownloadState.Failed,
        canCancel = state != DownloadState.Completed,
        canDelete = state == DownloadState.Completed && !leased,
        isLeased = leased,
    )
}

private fun com.jellyscope.core.domain.model.OfflineDetailSnapshot.toTvDownloadDetail(): TvDownloadDetail =
    TvDownloadDetail(
        overview = overview,
        tagline = tagline,
        officialRating = officialRating,
        communityRating = communityRating,
        criticRating = criticRating,
        productionYear = productionYear,
        genres = genres,
        studios = studios,
        people =
            people.map { person ->
                TvDownloadPerson(
                    name = person.name,
                    role = person.role,
                    creditType = person.creditType,
                )
            },
        externalProviderIds =
            TvDownloadExternalProviderIds(
                imdbId = externalProviderIds.imdbId,
                tmdbId = externalProviderIds.tmdbId,
                tmdbItemType = externalProviderIds.tmdbItemType,
            ),
    )

private fun BackendSourceDescriptor.toTvDownloadBackendInfo(): TvDownloadBackendInfo =
    TvDownloadBackendInfo(
        container = container,
        videoCodec = videoCodec,
        audioCodec = audioCodec,
        isHdrOrDolbyVision = isHdrOrDolbyVision,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        videoFrameRate = videoFrameRate,
    )

private fun DownloadState.toTvDownloadRowState(): TvDownloadRowState =
    when (this) {
        DownloadState.Completed -> TvDownloadRowState.Completed
        DownloadState.Downloading -> TvDownloadRowState.Downloading
        DownloadState.Finalizing -> TvDownloadRowState.Finalizing
        DownloadState.Queued -> TvDownloadRowState.Queued
        DownloadState.Paused -> TvDownloadRowState.Paused
        DownloadState.BlockedByQuota -> TvDownloadRowState.BlockedByQuota
        DownloadState.Failed -> TvDownloadRowState.Failed
        DownloadState.NotDownloaded -> error("A persisted download row cannot be NotDownloaded.")
    }

private fun DownloadFailure.toTvDownloadFailureKind(): TvDownloadFailureKind =
    when (this) {
        DownloadFailure.PermissionDenied -> TvDownloadFailureKind.PermissionDenied
        DownloadFailure.SizeUnavailable -> TvDownloadFailureKind.SizeUnavailable
        DownloadFailure.Network -> TvDownloadFailureKind.Network
        DownloadFailure.ServerUnavailable -> TvDownloadFailureKind.ServerUnavailable
        DownloadFailure.SourceChanged -> TvDownloadFailureKind.SourceChanged
        DownloadFailure.UnsupportedArtifact -> TvDownloadFailureKind.UnsupportedArtifact
        DownloadFailure.QuotaExceeded -> TvDownloadFailureKind.QuotaExceeded
        DownloadFailure.DeviceStorageLow -> TvDownloadFailureKind.DeviceStorageLow
        DownloadFailure.MissingArtifact -> TvDownloadFailureKind.MissingArtifact
        DownloadFailure.ArtifactInUse -> TvDownloadFailureKind.ArtifactInUse
    }

internal data class TvLoadedDownloadRequest(
    val detail: MediaItemDetail,
    val version: MediaVersion,
    val audioStreamIndex: Int?,
    val localSubtitleAsset: LocalSubtitleAsset?,
)

internal fun TvLoadedDownloadRequest.summary(selection: TvDetailPlaybackSelection): TvDownloadRequestSummary {
    val audio = audioStreamIndex?.let(version::audioStreamAt)?.stream
    val subtitleLabel =
        when (selection.subtitleMode) {
            TvPlaybackSubtitleMode.Off,
            TvPlaybackSubtitleMode.Unspecified,
            -> null
            TvPlaybackSubtitleMode.LocalAsset -> localSubtitleAsset?.label
            TvPlaybackSubtitleMode.Track ->
                selection.subtitleStreamIndex
                    ?.let(version::subtitleStreamAt)
                    ?.stream
                    ?.trackLabel()
        }
    return TvDownloadRequestSummary(
        title = detail.item.name,
        sourceLabel = version.releaseBasename?.takeIf(String::isNotBlank) ?: version.name,
        audioLabel = audio?.trackLabel(),
        subtitleLabel = subtitleLabel,
    )
}

internal fun TvLoadedDownloadRequest.qualityChoices(hasFixedCapability: Boolean): List<TvDownloadQualityChoice> {
    if (!hasFixedCapability) {
        return listOf(TvDownloadQualityChoice(TV_DOWNLOAD_ORIGINAL_QUALITY_ID, TvDownloadQualityKind.Original))
    }
    val video = version.mediaStreams.firstOrNull { stream -> stream.type.equals("Video", ignoreCase = true) }
    val sourceBitrate = video?.bitRate?.takeIf { value -> value > 0L }
    val sourceHeight = video?.height?.takeIf { value -> value > 0 }
    val reliable = sourceBitrate != null && sourceHeight != null
    val fixedRungs =
        if (!reliable) {
            qualityRungs
        } else {
            qualityRungs.filter { rung -> rung.maxBitrateBps < sourceBitrate || rung.height < sourceHeight }
        }
    return listOf(TvDownloadQualityChoice(TV_DOWNLOAD_ORIGINAL_QUALITY_ID, TvDownloadQualityKind.Original)) +
        fixedRungs.map { rung -> rung.toTvDownloadQualityChoice(!reliable) }
}

internal fun TvLoadedDownloadRequest.fixedSubtitleRequirement(selection: TvDetailPlaybackSelection): TvFixedSubtitleRequirement =
    when (selection.subtitleMode) {
        TvPlaybackSubtitleMode.Off,
        TvPlaybackSubtitleMode.Unspecified,
        -> TvFixedSubtitleRequirement.None
        TvPlaybackSubtitleMode.LocalAsset -> TvFixedSubtitleRequirement.OffRequired
        TvPlaybackSubtitleMode.Track -> {
            val stream = selection.subtitleStreamIndex?.let(version::subtitleStreamAt)?.stream
            if (stream == null ||
                stream.isExternalSubtitle() ||
                stream.codec.fixedTextSubtitleCodec() == null ||
                subtitleKind(stream.codec) == SubtitleKind.Bitmap
            ) {
                TvFixedSubtitleRequirement.OffRequired
            } else {
                TvFixedSubtitleRequirement.BurnInConfirmation
            }
        }
    }

internal sealed interface TvDownloadDraftBuildResult {
    data class OriginalReady(
        val draft: OriginalDownloadDraft,
    ) : TvDownloadDraftBuildResult

    data class FixedReady(
        val draft: FixedDownloadDraft,
    ) : TvDownloadDraftBuildResult

    data class OriginalBitmapConfirmation(
        val subtitleLabel: String,
    ) : TvDownloadDraftBuildResult

    data class FixedBurnInConfirmation(
        val subtitleLabel: String,
    ) : TvDownloadDraftBuildResult

    data object SubtitleUnavailable : TvDownloadDraftBuildResult

    data object Unsupported : TvDownloadDraftBuildResult
}

internal fun TvLoadedDownloadRequest.buildDraft(
    session: Session,
    selection: TvDetailPlaybackSelection,
    quality: TvDownloadQualityChoice,
    fixedSubtitleOff: Boolean,
    originalBitmapConfirmed: Boolean,
    fixedBurnInConfirmed: Boolean,
    identity: String,
    createdAtEpochMs: Long,
): TvDownloadDraftBuildResult {
    val originalSubtitle = resolveOriginalSubtitle(selection)
    val subtitle =
        when (originalSubtitle) {
            is TvOriginalSubtitleResolution.Ready -> originalSubtitle
            is TvOriginalSubtitleResolution.ExternalBitmap -> {
                if (quality.kind == TvDownloadQualityKind.Original && !originalBitmapConfirmed) {
                    return TvDownloadDraftBuildResult.OriginalBitmapConfirmation(originalSubtitle.label)
                }
                TvOriginalSubtitleResolution.Ready(DownloadSubtitleSelection.Off, null)
            }
            TvOriginalSubtitleResolution.Invalid -> return TvDownloadDraftBuildResult.SubtitleUnavailable
        }
    val effectiveSubtitle =
        if (quality.kind == TvDownloadQualityKind.Fixed && fixedSubtitleOff) {
            TvOriginalSubtitleResolution.Ready(DownloadSubtitleSelection.Off, null)
        } else {
            subtitle
        }
    val downloadId = DownloadId(identity)
    val artifactKey = DownloadArtifactKey("artifact-$identity")
    val snapshot = buildSnapshot(effectiveSubtitle) ?: return TvDownloadDraftBuildResult.Unsupported
    val businessKey = DownloadBusinessKey(session.accountIdentity(), detail.item.id, version.id)
    if (quality.kind == TvDownloadQualityKind.Original) {
        return TvDownloadDraftBuildResult.OriginalReady(
            OriginalDownloadDraft(
                downloadId = downloadId,
                businessKey = businessKey,
                selectedAudioStreamIndex = audioStreamIndex,
                subtitleSelection = effectiveSubtitle.selection,
                artifactKey = artifactKey,
                snapshot = snapshot,
                createdAtEpochMs = createdAtEpochMs,
            ),
        )
    }

    val fixedQuality =
        quality.maxBitrateBps?.let { bitrate -> DownloadQuality.Fixed(bitrate) }
            ?: return TvDownloadDraftBuildResult.Unsupported
    val fixedSubtitle =
        when (val selected = effectiveSubtitle.selection) {
            DownloadSubtitleSelection.Off -> DownloadSubtitleSelection.Off
            is DownloadSubtitleSelection.Embedded -> {
                val stream = effectiveSubtitle.stream
                if (stream == null ||
                    stream.isExternalSubtitle() ||
                    stream.codec.fixedTextSubtitleCodec() == null ||
                    subtitleKind(stream.codec) != SubtitleKind.Text
                ) {
                    return TvDownloadDraftBuildResult.SubtitleUnavailable
                }
                if (!fixedBurnInConfirmed) {
                    return TvDownloadDraftBuildResult.FixedBurnInConfirmation(stream.trackLabel())
                }
                DownloadSubtitleSelection.Embedded(selected.streamIndex, burnInConfirmed = true)
            }
            is DownloadSubtitleSelection.ExternalServerTextSidecar,
            is DownloadSubtitleSelection.ExternalTextSidecar,
            -> return TvDownloadDraftBuildResult.SubtitleUnavailable
        }
    val effectiveAudioIndex = snapshot.selectedAudioTrack?.streamIndex ?: return TvDownloadDraftBuildResult.Unsupported
    val fixedSnapshot = snapshot.toFixedDownloadSnapshot(effectiveAudioIndex) ?: return TvDownloadDraftBuildResult.Unsupported
    return TvDownloadDraftBuildResult.FixedReady(
        FixedDownloadDraft(
            downloadId = downloadId,
            businessKey = businessKey,
            quality = fixedQuality,
            selectedAudioStreamIndex = fixedSnapshot.selectedAudioTrack?.streamIndex,
            subtitleSelection = fixedSubtitle,
            artifactKey = artifactKey,
            snapshot = fixedSnapshot,
            createdAtEpochMs = createdAtEpochMs,
        ),
    )
}

private fun TvLoadedDownloadRequest.resolveOriginalSubtitle(selection: TvDetailPlaybackSelection): TvOriginalSubtitleResolution {
    return when (selection.subtitleMode) {
        TvPlaybackSubtitleMode.Off,
        TvPlaybackSubtitleMode.Unspecified,
        -> TvOriginalSubtitleResolution.Ready(DownloadSubtitleSelection.Off, null)
        TvPlaybackSubtitleMode.LocalAsset -> {
            val asset = localSubtitleAsset ?: return TvOriginalSubtitleResolution.Invalid
            TvOriginalSubtitleResolution.Ready(DownloadSubtitleSelection.ExternalTextSidecar(asset.id), null)
        }
        TvPlaybackSubtitleMode.Track -> {
            val indexed = selection.subtitleStreamIndex?.let(version::subtitleStreamAt) ?: return TvOriginalSubtitleResolution.Invalid
            val stream = indexed.stream
            if (stream.isExternalSubtitle()) {
                if (subtitleKind(stream.codec) == SubtitleKind.Bitmap) {
                    TvOriginalSubtitleResolution.ExternalBitmap(stream.trackLabel())
                } else {
                    TvOriginalSubtitleResolution.Ready(
                        DownloadSubtitleSelection.ExternalServerTextSidecar(indexed.canonicalIndex),
                        stream,
                    )
                }
            } else {
                TvOriginalSubtitleResolution.Ready(DownloadSubtitleSelection.Embedded(indexed.canonicalIndex), stream)
            }
        }
    }
}

private fun TvLoadedDownloadRequest.buildSnapshot(subtitle: TvOriginalSubtitleResolution.Ready): OfflineMediaSnapshot? {
    val audio = audioStreamIndex?.let(version::audioStreamAt)
    val selectedSubtitle =
        when (val selection = subtitle.selection) {
            DownloadSubtitleSelection.Off -> null
            is DownloadSubtitleSelection.Embedded ->
                subtitle.stream?.toOfflineTrack(OfflineTrackKind.Subtitle, selection.streamIndex)
            is DownloadSubtitleSelection.ExternalServerTextSidecar ->
                subtitle.stream?.let { stream ->
                    OfflineTrackSnapshot(
                        kind = OfflineTrackKind.Subtitle,
                        streamIndex = selection.streamIndex,
                        codec = "vtt",
                        language = stream.language,
                        label = stream.trackLabel(),
                        isDefault = stream.isDefault == true,
                        isExternal = true,
                    )
                }
            is DownloadSubtitleSelection.ExternalTextSidecar ->
                localSubtitleAsset?.let { asset ->
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
    if (subtitle.selection !is DownloadSubtitleSelection.Off && selectedSubtitle == null) return null
    val video = version.mediaStreams.firstOrNull { stream -> stream.type.equals("Video", ignoreCase = true) }
    return runCatching {
        OfflineMediaSnapshot(
            title = detail.item.name,
            itemKind = detail.item.kind,
            seriesName = detail.item.seriesName,
            seasonLabel = detail.item.parentIndexNumber?.let { season -> "Season $season" },
            episodeLabel = detail.item.episodeLabel,
            durationMs = (version.runtime ?: detail.item.runtime)?.inWholeMilliseconds?.takeIf { duration -> duration > 0L },
            chapters = detail.chapters.map { chapter -> OfflineChapterSnapshot(chapter.name, chapter.startTicks) },
            sourcePresentation = version.releaseBasename?.takeIf(String::isNotBlank) ?: version.name,
            embeddedTracks =
                version.mediaStreams
                    .filter { stream -> stream.type.equals("Audio", ignoreCase = true) }
                    .mapIndexedNotNull { ordinal, stream ->
                        if (stream.isExternalSubtitle()) {
                            null
                        } else {
                            stream.toOfflineTrack(OfflineTrackKind.Audio, stream.index ?: ordinal)
                        }
                    } +
                    version.mediaStreams
                        .filter { stream -> stream.type.equals("Subtitle", ignoreCase = true) }
                        .mapIndexedNotNull { ordinal, stream ->
                            if (stream.isExternalSubtitle()) {
                                null
                            } else {
                                stream.toOfflineTrack(OfflineTrackKind.Subtitle, stream.index ?: ordinal)
                            }
                        },
            selectedAudioTrack = audio?.stream?.toOfflineTrack(OfflineTrackKind.Audio, audio.canonicalIndex),
            selectedSubtitleTrack = selectedSubtitle,
            backendSource =
                BackendSourceDescriptor(
                    container = version.container,
                    videoCodec = video?.codec,
                    audioCodec = audio?.stream?.codec,
                    isHdrOrDolbyVision =
                        listOfNotNull(video?.videoRangeType, video?.codec).any { value ->
                            value.contains("hdr", ignoreCase = true) ||
                                value.contains("dolby", ignoreCase = true) ||
                                value.contains("vision", ignoreCase = true)
                        },
                    videoWidth = video?.width,
                    videoHeight = video?.height,
                    videoFrameRate = video?.realFrameRate,
                ),
        )
    }.getOrNull()
}

private sealed interface TvOriginalSubtitleResolution {
    data class Ready(
        val selection: DownloadSubtitleSelection,
        val stream: PlaybackMediaStream?,
    ) : TvOriginalSubtitleResolution

    data class ExternalBitmap(
        val label: String,
    ) : TvOriginalSubtitleResolution

    data object Invalid : TvOriginalSubtitleResolution
}

private data class TvIndexedStream(
    val ordinal: Int,
    val stream: PlaybackMediaStream,
) {
    val canonicalIndex: Int
        get() = stream.index ?: ordinal
}

private fun MediaVersion.audioStreamAt(index: Int): TvIndexedStream? =
    mediaStreams
        .filter { stream -> stream.type.equals("Audio", ignoreCase = true) }
        .withIndex()
        .firstOrNull { (ordinal, stream) -> stream.index == index || (stream.index == null && ordinal == index) }
        ?.let { indexed -> TvIndexedStream(indexed.index, indexed.value) }

private fun MediaVersion.subtitleStreamAt(index: Int): TvIndexedStream? =
    mediaStreams
        .filter { stream -> stream.type.equals("Subtitle", ignoreCase = true) }
        .withIndex()
        .firstOrNull { (ordinal, stream) -> stream.index == index || (stream.index == null && ordinal == index) }
        ?.let { indexed -> TvIndexedStream(indexed.index, indexed.value) }

internal fun MediaVersion.resolvedAudioStreamIndex(requested: Int?): Int? =
    requested
        ?.let(::audioStreamAt)
        ?.canonicalIndex
        ?: mediaStreams
            .filter { stream -> stream.type.equals("Audio", ignoreCase = true) }
            .withIndex()
            .firstOrNull { (_, stream) -> stream.isDefault == true }
            ?.let { indexed -> indexed.value.index ?: indexed.index }
        ?: mediaStreams
            .filter { stream -> stream.type.equals("Audio", ignoreCase = true) }
            .withIndex()
            .firstOrNull()
            ?.let { indexed -> indexed.value.index ?: indexed.index }

private fun PlaybackMediaStream.toOfflineTrack(
    kind: OfflineTrackKind,
    streamIndex: Int,
): OfflineTrackSnapshot =
    OfflineTrackSnapshot(
        kind = kind,
        streamIndex = streamIndex,
        codec = codec,
        language = language,
        label = trackLabel(),
        isDefault = isDefault == true,
        isExternal = isExternalSubtitle(),
    )

private fun PlaybackMediaStream.trackLabel(): String =
    displayTitle?.takeIf(String::isNotBlank)
        ?: title?.takeIf(String::isNotBlank)
        ?: language?.takeIf(String::isNotBlank)
        ?: codec?.takeIf(String::isNotBlank)
        ?: "Track"

private fun String?.fixedTextSubtitleCodec(): String? =
    this?.trim()?.lowercase()?.takeIf { codec -> codec in setOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa") }

private fun QualityRung.toTvDownloadQualityChoice(upTo: Boolean): TvDownloadQualityChoice =
    TvDownloadQualityChoice(
        id = "fixed-$maxBitrateBps",
        kind = TvDownloadQualityKind.Fixed,
        maxBitrateBps = maxBitrateBps,
        height = height,
        usesUpToLabel = upTo,
    )
