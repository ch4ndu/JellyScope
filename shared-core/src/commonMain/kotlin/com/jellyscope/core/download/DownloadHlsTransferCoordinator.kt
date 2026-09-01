// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.data.local.AccountWorkLease
import com.jellyscope.core.data.local.DOWNLOAD_ARTIFACT_MAX_WRITE_CHUNK_BYTES
import com.jellyscope.core.data.local.DownloadArtifactArea
import com.jellyscope.core.data.local.DownloadArtifactCheckpoint
import com.jellyscope.core.data.local.DownloadArtifactInspection
import com.jellyscope.core.data.local.DownloadArtifactPartCheckpoint
import com.jellyscope.core.data.local.DownloadArtifactPartKey
import com.jellyscope.core.data.local.DownloadArtifactStore
import com.jellyscope.core.data.local.DownloadArtifactWriteMode
import com.jellyscope.core.data.local.DownloadArtifactWriter
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.remote.AuthenticatedRequestContext
import com.jellyscope.core.data.remote.FixedDownloadFailure
import com.jellyscope.core.data.remote.FixedDownloadPreflightResult
import com.jellyscope.core.data.remote.FixedDownloadRequest
import com.jellyscope.core.data.remote.FixedDownloadRequestKind
import com.jellyscope.core.data.remote.FixedDownloadResource
import com.jellyscope.core.data.remote.FixedDownloadResourceRejectReason
import com.jellyscope.core.data.remote.FixedDownloadResourceResult
import com.jellyscope.core.data.remote.FixedDownloadSource
import com.jellyscope.core.data.remote.JellyfinApi
import com.jellyscope.core.data.repository.DownloadActiveAttemptRegistration
import com.jellyscope.core.data.repository.DownloadCheckpointFacts
import com.jellyscope.core.domain.model.DOWNLOAD_DEVICE_SAFETY_RESERVE_BYTES
import com.jellyscope.core.domain.model.DownloadFailure
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadReservationExtensionResult
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.jellyscope.core.util.safeDiagnosticType
import io.ktor.utils.io.errors.IOException
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Fixed-quality VOD HLS transfer owner.  It shares the application-wide queue and reservation
 * rules with Original downloads but keeps the converted package contract separate: fresh HLS
 * identity is authenticated on every run, only complete local parts are resumed, and a package
 * cannot enter Finalizing until its checkpoint and all segment bytes prove completeness.
 */
internal class DownloadHlsTransferCoordinator(
    private val serverScopedStoreRegistry: ServerScopedStoreRegistry,
    private val jellyfinApi: JellyfinApi,
    private val queueCoordinator: DownloadQueueCoordinator,
    private val artifactStore: DownloadArtifactStore,
) {
    /** Runs one already-claimed fixed-quality row under the shared transfer coordinator lease. */
    suspend fun runClaimed(
        record: DownloadRecord,
        attempt: DownloadAttemptIdentity,
        accountIdentity: com.jellyscope.core.domain.model.AccountIdentity,
        lease: AccountWorkLease,
        context: AuthenticatedRequestContext,
    ): DownloadTransferResult =
        try {
            executeClaim(
                record = record,
                attempt = attempt,
                accountIdentity = accountIdentity,
                lease = lease,
                context = context,
            )
        } catch (cancellation: CancellationException) {
            checkpointForCancellation(attempt, lease, cancellation)
            throw cancellation
        } catch (outcome: HlsTransferOutcomeException) {
            when (val result = outcome.outcome) {
                is DownloadTransferResult.Failed -> settleClaimedOrRegisteredFailure(attempt, lease, result.failure)
                DownloadTransferResult.BlockedByQuota ->
                    settleClaimedOrRegisteredState(
                        attempt,
                        lease,
                        DownloadState.BlockedByQuota,
                        result,
                    )
                DownloadTransferResult.BoundaryChanged -> {
                    queueCoordinator.invalidateRegisteredAttemptAfterBoundary(attempt)
                    result
                }
                else -> result
            }
        } catch (failure: Throwable) {
            fixedDownloadTransferLogger.w {
                "stage=fixed-download event=transfer-rejected reason=UnhandledFailure " +
                    "requestKind=Transfer result=ServerUnavailable exceptionType=${failure.safeDiagnosticType()}"
            }
            failUnregisteredOrRegistered(attempt, DownloadFailure.ServerUnavailable, lease)
        }

    private suspend fun executeClaim(
        record: DownloadRecord,
        attempt: DownloadAttemptIdentity,
        accountIdentity: com.jellyscope.core.domain.model.AccountIdentity,
        lease: AccountWorkLease,
        context: AuthenticatedRequestContext,
    ): DownloadTransferResult {
        val quality =
            record.request.quality as? DownloadQuality.Fixed
                ?: return rejectTransfer(
                    attempt = attempt,
                    failure = DownloadFailure.UnsupportedArtifact,
                    reason = FixedDownloadTransferDiagnosticReason.QualityContractMismatch,
                    lease = lease,
                )
        val fixedRequest =
            FixedDownloadRequest(
                itemId = record.businessKey.itemId,
                mediaSourceId = record.businessKey.mediaSourceId,
                quality = quality.rung,
                audioStreamIndex = record.request.selectedAudioStreamIndex,
                subtitleSelection = record.request.subtitleSelection,
                requestKind = FixedDownloadRequestKind.Transfer,
            )
        val source =
            when (val preflight = jellyfinApi.preflightFixedDownload(context, fixedRequest)) {
                is FixedDownloadPreflightResult.Ready -> preflight.source
                is FixedDownloadPreflightResult.Rejected ->
                    return rejectTransfer(
                        attempt = attempt,
                        failure = preflight.failure.toDownloadFailure(),
                        reason = FixedDownloadTransferDiagnosticReason.PreflightRejected,
                        lease = lease,
                    )
            }
        fixedDownloadTransferLogger.i {
            "stage=fixed-download event=transfer-preflight-ready requestKind=Transfer result=Ready"
        }
        return withFixedDownloadEncodingCleanup(
            cleanup = { jellyfinApi.stopFixedDownloadEncoding(context, source) },
        ) {
            if (
                source.itemId != record.businessKey.itemId ||
                source.mediaSourceId != record.businessKey.mediaSourceId ||
                source.quality.maxBitrateBps != quality.maxBitrateBps ||
                record.request.selectedAudioStreamIndex?.let { index -> index != source.audioStreamIndex } == true ||
                record.request.subtitleSelection.fixedSubtitleIndex() != source.subtitleStreamIndex
            ) {
                return@withFixedDownloadEncodingCleanup rejectTransfer(
                    attempt = attempt,
                    failure = DownloadFailure.SourceChanged,
                    reason = FixedDownloadTransferDiagnosticReason.SourceContractMismatch,
                    lease = lease,
                )
            }

            val packageValue =
                when (val fetched = fetchPackage(context, source)) {
                    is HlsFetchResult.Ready -> fetched.value
                    is HlsFetchResult.ReadyText ->
                        return@withFixedDownloadEncodingCleanup rejectTransfer(
                            attempt = attempt,
                            failure = DownloadFailure.UnsupportedArtifact,
                            reason = FixedDownloadTransferDiagnosticReason.PackageProjectionMismatch,
                            lease = lease,
                        )
                    is HlsFetchResult.Failed ->
                        return@withFixedDownloadEncodingCleanup rejectTransfer(
                            attempt = attempt,
                            failure = fetched.failure,
                            reason = FixedDownloadTransferDiagnosticReason.PlaylistRejected,
                            lease = lease,
                        )
                }
            if (!hlsDurationMatchesSource(packageValue, source.durationMs)) {
                return@withFixedDownloadEncodingCleanup rejectTransfer(
                    attempt = attempt,
                    failure = DownloadFailure.SourceChanged,
                    reason = FixedDownloadTransferDiagnosticReason.DurationMismatch,
                    lease = lease,
                )
            }
            fixedDownloadTransferLogger.i {
                "stage=fixed-download event=package-ready requestKind=Transfer result=Ready"
            }
            val prepared =
                when (
                    val preparation =
                        serverScopedStoreRegistry.withGuardedLease(lease) {
                            prepareAttempt(record, attempt, accountIdentity, lease, packageValue)
                        } ?: HlsPreparation.BoundaryChanged
                ) {
                    is HlsPreparation.Ready -> preparation.value
                    is HlsPreparation.Failed ->
                        return@withFixedDownloadEncodingCleanup rejectTransfer(
                            attempt = attempt,
                            failure = preparation.failure,
                            reason = FixedDownloadTransferDiagnosticReason.PreparationRejected,
                            lease = lease,
                        )
                    HlsPreparation.BoundaryChanged ->
                        return@withFixedDownloadEncodingCleanup DownloadTransferResult.BoundaryChanged
                }
            fixedDownloadTransferLogger.i {
                "stage=fixed-download event=transfer-started requestKind=Transfer result=Ready"
            }
            transferPackage(prepared, context, source, packageValue)
        }
    }

    private suspend fun fetchPackage(
        context: AuthenticatedRequestContext,
        source: FixedDownloadSource,
    ): HlsFetchResult {
        val masterText =
            when (
                val result =
                    readPlaylist(
                        context = context,
                        source = source,
                        url = source.transcodingUrl,
                        maxBytes = MAX_HLS_MEDIA_PLAYLIST_BYTES,
                    )
            ) {
                is HlsFetchResult.Failed -> {
                    logPlaylistRejection(
                        requestKind = FixedDownloadPlaylistKind.SourcePlaylist,
                        reason = result.fetchReason ?: FixedDownloadPlaylistFetchRejectReason.UnexpectedTransportFailure,
                        failure = result.failure,
                        fixedFailure = result.fixedFailure,
                    )
                    return result
                }
                is HlsFetchResult.ReadyText -> result.text
                is HlsFetchResult.Ready -> {
                    logPlaylistRejection(
                        requestKind = FixedDownloadPlaylistKind.MasterPlaylist,
                        reason = FixedDownloadTransferDiagnosticReason.PackageProjectionMismatch,
                        failure = DownloadFailure.UnsupportedArtifact,
                    )
                    return HlsFetchResult.Failed(DownloadFailure.UnsupportedArtifact)
                }
            }
        val master =
            when (val parsed = DownloadHlsPackage.parseMasterOnly(masterText)) {
                is DownloadHlsParseResult.Master -> parsed.value
                is DownloadHlsParseResult.Failure -> {
                    when (val directMedia = DownloadHlsPackage.parseMediaOnly(masterText)) {
                        is DownloadHlsParseResult.Media -> {
                            fixedDownloadTransferLogger.i {
                                "stage=fixed-download event=direct-media-playlist-ready " +
                                    "requestKind=Transfer result=Ready"
                            }
                            return HlsFetchResult.Ready(
                                DownloadHlsPackage.fromDirectMedia(
                                    media = directMedia.value,
                                    maxBitrateBps = source.quality.maxBitrateBps,
                                ),
                            )
                        }
                        is DownloadHlsParseResult.Failure -> {
                            val masterFailure = parsed.reason.toDownloadFailure()
                            logPlaylistRejection(
                                requestKind = FixedDownloadPlaylistKind.MasterPlaylist,
                                reason = parsed.reason,
                                failure = masterFailure,
                            )
                            val failure = directMedia.reason.toDownloadFailure()
                            logPlaylistRejection(
                                requestKind = FixedDownloadPlaylistKind.DirectMediaPlaylist,
                                reason = directMedia.reason,
                                failure = failure,
                            )
                            return HlsFetchResult.Failed(failure)
                        }
                        else -> {
                            val failure = parsed.reason.toDownloadFailure()
                            logPlaylistRejection(
                                requestKind = FixedDownloadPlaylistKind.MasterPlaylist,
                                reason = parsed.reason,
                                failure = failure,
                            )
                            return HlsFetchResult.Failed(failure)
                        }
                    }
                }
                else -> {
                    logPlaylistRejection(
                        requestKind = FixedDownloadPlaylistKind.MasterPlaylist,
                        reason = FixedDownloadTransferDiagnosticReason.PackageProjectionMismatch,
                        failure = DownloadFailure.UnsupportedArtifact,
                    )
                    return HlsFetchResult.Failed(DownloadFailure.UnsupportedArtifact)
                }
            }
        val mediaUrl =
            resolveHlsResourceUrl(source.transcodingUrl, master.childPlaylistUri)
                ?: run {
                    logPlaylistRejection(
                        requestKind = FixedDownloadPlaylistKind.MediaPlaylist,
                        reason = FixedDownloadTransferDiagnosticReason.PlaylistUrlRejected,
                        failure = DownloadFailure.UnsupportedArtifact,
                    )
                    return HlsFetchResult.Failed(DownloadFailure.UnsupportedArtifact)
                }
        val mediaText =
            when (
                val result =
                    readPlaylist(
                        context = context,
                        source = source,
                        url = mediaUrl,
                        maxBytes = MAX_HLS_MEDIA_PLAYLIST_BYTES,
                    )
            ) {
                is HlsFetchResult.Failed -> {
                    logPlaylistRejection(
                        requestKind = FixedDownloadPlaylistKind.MediaPlaylist,
                        reason = result.fetchReason ?: FixedDownloadPlaylistFetchRejectReason.UnexpectedTransportFailure,
                        failure = result.failure,
                        fixedFailure = result.fixedFailure,
                    )
                    return result
                }
                is HlsFetchResult.ReadyText -> result.text
                is HlsFetchResult.Ready -> {
                    logPlaylistRejection(
                        requestKind = FixedDownloadPlaylistKind.MediaPlaylist,
                        reason = FixedDownloadTransferDiagnosticReason.PackageProjectionMismatch,
                        failure = DownloadFailure.UnsupportedArtifact,
                    )
                    return HlsFetchResult.Failed(DownloadFailure.UnsupportedArtifact)
                }
            }
        val media =
            when (val parsed = DownloadHlsPackage.parseMediaOnly(mediaText)) {
                is DownloadHlsParseResult.Media -> parsed.value
                is DownloadHlsParseResult.Failure -> {
                    val failure = parsed.reason.toDownloadFailure()
                    logPlaylistRejection(
                        requestKind = FixedDownloadPlaylistKind.MediaPlaylist,
                        reason = parsed.reason,
                        failure = failure,
                    )
                    return HlsFetchResult.Failed(failure)
                }
                else -> {
                    logPlaylistRejection(
                        requestKind = FixedDownloadPlaylistKind.MediaPlaylist,
                        reason = FixedDownloadTransferDiagnosticReason.PackageProjectionMismatch,
                        failure = DownloadFailure.UnsupportedArtifact,
                    )
                    return HlsFetchResult.Failed(DownloadFailure.UnsupportedArtifact)
                }
            }
        fixedDownloadTransferLogger.i {
            "stage=fixed-download event=playlists-ready requestKind=Transfer result=Ready"
        }
        return HlsFetchResult.Ready(DownloadHlsPackage(master = master, media = media))
    }

    private fun <T : Enum<T>> logPlaylistRejection(
        requestKind: FixedDownloadPlaylistKind,
        reason: T,
        failure: DownloadFailure,
        fixedFailure: FixedDownloadFailure? = null,
    ) {
        fixedDownloadTransferLogger.w {
            buildString {
                append("stage=fixed-download event=playlist-rejected reason=${reason.name} ")
                append("requestKind=${requestKind.name}")
                fixedFailure?.let { value -> append(" failure=${value.name}") }
                append(" result=${failure.name}")
            }
        }
    }

    private suspend fun readPlaylist(
        context: AuthenticatedRequestContext,
        source: FixedDownloadSource,
        url: String,
        maxBytes: Int,
    ): HlsFetchResult {
        var outcome: HlsFetchResult? = null
        val streamed =
            jellyfinApi.streamFixedDownloadResource(
                context = context,
                source = source,
                resourceUrl = url,
                maxBytes = maxBytes.toLong(),
            ) { resource ->
                outcome = readBoundedText(resource, maxBytes)
                Unit
            }
        outcome?.let { value -> return value }
        return when (streamed) {
            is FixedDownloadResourceResult.Rejected ->
                HlsFetchResult.Failed(
                    failure = streamed.failure.toDownloadFailure(),
                    fetchReason = streamed.reason.toPlaylistFetchRejectReason(),
                    fixedFailure = streamed.failure,
                )
            is FixedDownloadResourceResult.Success ->
                HlsFetchResult.Failed(
                    failure = DownloadFailure.ServerUnavailable,
                    fetchReason = FixedDownloadPlaylistFetchRejectReason.UnexpectedTransportFailure,
                    fixedFailure = FixedDownloadFailure.ServerUnavailable,
                )
        }
    }

    private suspend fun readBoundedText(
        resource: FixedDownloadResource,
        maxBytes: Int,
    ): HlsFetchResult {
        val bytes = ByteArray(maxBytes)
        var count = 0
        val buffer = ByteArray(DOWNLOAD_ARTIFACT_MAX_WRITE_CHUNK_BYTES)
        try {
            while (true) {
                val read = resource.body.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                if (read > maxBytes - count) {
                    return HlsFetchResult.Failed(
                        failure = DownloadFailure.UnsupportedArtifact,
                        fetchReason = FixedDownloadPlaylistFetchRejectReason.StreamedBodyTooLarge,
                        fixedFailure = FixedDownloadFailure.PayloadTooLarge,
                    )
                }
                buffer.copyInto(bytes, destinationOffset = count, startIndex = 0, endIndex = read)
                count += read
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            return HlsFetchResult.Failed(
                failure = DownloadFailure.Network,
                fetchReason = FixedDownloadPlaylistFetchRejectReason.NetworkFailure,
                fixedFailure = FixedDownloadFailure.Network,
            )
        }
        if (resource.contentLength?.let { length -> length != count.toLong() } == true) {
            return HlsFetchResult.Failed(
                failure = DownloadFailure.SourceChanged,
                fetchReason = FixedDownloadPlaylistFetchRejectReason.ContentLengthMismatch,
                fixedFailure = FixedDownloadFailure.SourceChanged,
            )
        }
        return HlsFetchResult.ReadyText(bytes.copyOf(count).decodeToString())
    }

    private suspend fun prepareAttempt(
        record: DownloadRecord,
        attempt: DownloadAttemptIdentity,
        accountIdentity: com.jellyscope.core.domain.model.AccountIdentity,
        lease: AccountWorkLease,
        packageValue: DownloadHlsPackage,
    ): HlsPreparation {
        val stagingBefore = artifactStore.inspect(record.request.artifactKey, DownloadArtifactArea.Staging)
        val persistedBytes =
            artifactStore.readPart(
                artifactKey = record.request.artifactKey,
                area = DownloadArtifactArea.Staging,
                partKey = DownloadHlsPartNames.CHECKPOINT,
                maxBytes = MAX_HLS_CHECKPOINT_BYTES,
            )
        val persistedLength = persistedBytes?.size?.toLong() ?: 0L
        val persisted =
            when {
                persistedLength == 0L &&
                    stagingBefore?.parts?.any { part ->
                        part.partKey == DownloadHlsPartNames.CHECKPOINT && part.lengthBytes > 0L
                    } == true ->
                    return HlsPreparation.Failed(DownloadFailure.UnsupportedArtifact)
                persistedLength == 0L -> null
                else ->
                    DownloadHlsCheckpoint.decode(persistedBytes?.decodeToString().orEmpty())
                        ?: return HlsPreparation.Failed(DownloadFailure.UnsupportedArtifact)
            }
        if (persisted != null && !persisted.isCompatible(packageValue)) {
            return HlsPreparation.Failed(DownloadFailure.SourceChanged)
        }
        val checkpoint = persisted ?: packageValue.initialCheckpoint()
        val checkpointLength = persistedLength.takeIf { persisted != null } ?: 0L
        val expectedForNormalization =
            DownloadArtifactCheckpoint(
                parts =
                    checkpoint.artifactCheckpoint() +
                        DownloadArtifactPartCheckpoint(DownloadHlsPartNames.CHECKPOINT, checkpointLength),
            )
        if (!artifactStore.normalizeStagingCheckpoint(record.request.artifactKey, expectedForNormalization)) {
            return HlsPreparation.Failed(DownloadFailure.UnsupportedArtifact)
        }
        val staging = artifactStore.inspect(record.request.artifactKey, DownloadArtifactArea.Staging)
        val actualTotal = staging?.totalBytes ?: 0L
        if (actualTotal != record.checkpointBytes || record.physicalBytes != record.checkpointBytes) {
            return HlsPreparation.Failed(DownloadFailure.UnsupportedArtifact)
        }

        val partLengths =
            buildMap {
                staging?.parts?.forEach { part -> put(part.partKey, part.lengthBytes) }
            }.toMutableMap()
        val active =
            HlsActiveAttempt(
                record = record,
                attempt = attempt,
                lease = lease,
                packageValue = packageValue,
                checkpoint = checkpoint,
                writers = linkedMapOf(),
                partLengths = partLengths,
                completedParts =
                    checkpoint
                        .allParts()
                        .filter { part -> part.complete }
                        .map { part ->
                            DownloadArtifactPartKey.from(part.localName)
                        }.toMutableSet(),
                durableCompletedParts =
                    checkpoint
                        .allParts()
                        .filter { part -> part.complete }
                        .map { part ->
                            DownloadArtifactPartKey.from(part.localName)
                        }.toMutableSet(),
                reservationBytes = record.reservationBytes,
                physicalBytes = actualTotal,
            )
        val registration =
            DownloadActiveAttemptRegistration(
                accountIdentity = accountIdentity,
                lease = lease,
                attempt = attempt,
                initialFacts = DownloadCheckpointFacts(actualTotal, actualTotal),
                checkpointAndCloseWriter = { currentFacts ->
                    checkpointAndClose(active, currentFacts)
                },
            )
        if (!queueCoordinator.registerActiveAttempt(registration)) {
            active.writers.values.forEach { writer -> writer.close() }
            return HlsPreparation.BoundaryChanged
        }
        return HlsPreparation.Ready(active)
    }

    private suspend fun transferPackage(
        active: HlsActiveAttempt,
        context: AuthenticatedRequestContext,
        source: FixedDownloadSource,
        packageValue: DownloadHlsPackage,
    ): DownloadTransferResult {
        val master = DownloadHlsPartNames.MASTER
        val media = DownloadHlsPartNames.MEDIA
        if (master !in active.completedParts) {
            try {
                openWriter(active, master)
                writeBytesPart(active, master, packageValue.masterText().encodeToByteArray())
            } catch (outcome: HlsTransferOutcomeException) {
                return settleRegisteredOutcome(active, outcome.outcome)
            }
            active.completedParts += master
            closeCompletedPartWriter(active, master)
            persistProgressIfDue(active, active.partLengths[master] ?: 0L)?.let { return it }
        }
        if (media !in active.completedParts) {
            try {
                openWriter(active, media)
                writeBytesPart(active, media, packageValue.mediaText().encodeToByteArray())
            } catch (outcome: HlsTransferOutcomeException) {
                return settleRegisteredOutcome(active, outcome.outcome)
            }
            active.completedParts += media
            closeCompletedPartWriter(active, media)
            persistProgressIfDue(active, active.partLengths[media] ?: 0L)?.let { return it }
        }
        packageValue.media.segments.forEach { segment ->
            val partKey = segment.localPartKey
            if (partKey in active.completedParts) return@forEach
            openWriter(active, partKey)
            val mediaBaseUrl =
                if (packageValue.mediaPlaylistIsSource) {
                    source.transcodingUrl
                } else {
                    resolveHlsResourceUrl(source.transcodingUrl, packageValue.master.childPlaylistUri)
                        ?: return settleFailure(active, DownloadFailure.UnsupportedArtifact)
                }
            val resourceUrl =
                resolveHlsResourceUrl(mediaBaseUrl, segment.remoteUri)
                    ?: return settleFailure(active, DownloadFailure.UnsupportedArtifact)
            val result =
                try {
                    jellyfinApi.streamFixedDownloadResource(
                        context = context,
                        source = source,
                        resourceUrl = resourceUrl,
                        maxBytes = MAX_HLS_SEGMENT_BYTES,
                    ) { resource ->
                        streamPartIntoWriter(active, partKey, resource)
                    }
                } catch (outcome: HlsTransferOutcomeException) {
                    return settleRegisteredOutcome(active, outcome.outcome)
                }
            when (result) {
                is FixedDownloadResourceResult.Rejected -> return settleFailure(active, result.failure.toDownloadFailure())
                is FixedDownloadResourceResult.Success -> Unit
            }
            if (active.partLengths[partKey]?.takeIf { length -> length > 0L } == null) {
                return settleFailure(active, DownloadFailure.SourceChanged)
            }
            active.completedParts += partKey
            closeCompletedPartWriter(active, partKey)
            persistProgressIfDue(active, active.partLengths[partKey] ?: 0L)?.let { return it }
        }
        return finalizeAndPromote(active)
    }

    private suspend fun settleRegisteredOutcome(
        active: HlsActiveAttempt,
        outcome: DownloadTransferResult,
    ): DownloadTransferResult =
        when (outcome) {
            is DownloadTransferResult.Failed -> settleFailure(active, outcome.failure)
            DownloadTransferResult.BlockedByQuota ->
                settleRegisteredState(active, DownloadState.BlockedByQuota, outcome)
            DownloadTransferResult.BoundaryChanged -> {
                queueCoordinator.invalidateRegisteredAttemptAfterBoundary(active.attempt)
                outcome
            }
            else -> outcome
        }

    private suspend fun writeBytesPart(
        active: HlsActiveAttempt,
        partKey: DownloadArtifactPartKey,
        bytes: ByteArray,
    ) {
        require(bytes.isNotEmpty())
        val writer = active.writers.getValue(partKey)
        if (writer.lengthBytes > 0L) {
            if (writer.lengthBytes != bytes.size.toLong()) throw IllegalStateException("HLS playlist checkpoint length changed.")
            return
        }
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(DOWNLOAD_ARTIFACT_MAX_WRITE_CHUNK_BYTES, bytes.size - offset)
            ensureReservation(active, length.toLong())?.let { throw HlsTransferOutcomeException(it) }
            val before = writer.lengthBytes
            writer.write(bytes, offset, length)
            updatePartLength(active, partKey, before, writer.lengthBytes)
            offset += length
        }
        check(writer.lengthBytes == bytes.size.toLong())
    }

    private suspend fun openWriter(
        active: HlsActiveAttempt,
        partKey: DownloadArtifactPartKey,
    ): DownloadArtifactWriter {
        active.writers[partKey]?.let { return it }
        val length = active.partLengths[partKey] ?: 0L
        val mode =
            if (active.partLengths.containsKey(partKey)) {
                DownloadArtifactWriteMode.Resume(length)
            } else {
                DownloadArtifactWriteMode.Create
            }
        val writer =
            try {
                artifactStore.openStagingWriter(active.record.request.artifactKey, partKey, mode)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                throw HlsTransferOutcomeException(
                    DownloadTransferResult.Failed(DownloadFailure.DeviceStorageLow),
                )
            }
        active.writers[partKey] = writer
        if (!active.partLengths.containsKey(partKey)) {
            active.partLengths[partKey] = writer.lengthBytes
        }
        return writer
    }

    private fun updatePartLength(
        active: HlsActiveAttempt,
        partKey: DownloadArtifactPartKey,
        before: Long,
        after: Long,
    ) {
        check(after >= before)
        active.physicalBytes = checkedAdd(active.physicalBytes, after - before)
        active.partLengths[partKey] = after
    }

    private fun replacePartLength(
        active: HlsActiveAttempt,
        partKey: DownloadArtifactPartKey,
        before: Long,
        after: Long,
    ) {
        check(before >= 0L && after >= 0L)
        active.physicalBytes =
            if (after >= before) {
                checkedAdd(active.physicalBytes, after - before)
            } else {
                active.physicalBytes - (before - after)
            }
        active.partLengths[partKey] = after
    }

    private suspend fun streamPartIntoWriter(
        active: HlsActiveAttempt,
        partKey: DownloadArtifactPartKey,
        resource: FixedDownloadResource,
    ) {
        val writer = active.writers.getValue(partKey)
        if (writer.lengthBytes != 0L) throw IllegalStateException("Incomplete HLS segment must be restarted.")
        val buffer = ByteArray(DOWNLOAD_ARTIFACT_MAX_WRITE_CHUNK_BYTES)
        var total = 0L
        while (true) {
            val read = resource.body.readAvailable(buffer, 0, buffer.size)
            if (read < 0) break
            if (read == 0) continue
            total += read.toLong()
            if (total >
                MAX_HLS_SEGMENT_BYTES
            ) {
                throw HlsTransferOutcomeException(DownloadTransferResult.Failed(DownloadFailure.UnsupportedArtifact))
            }
            ensureReservation(active, read.toLong())?.let { throw HlsTransferOutcomeException(it) }
            writer.write(buffer, 0, read)
            if (resource.contentLength?.let { length -> total > length } == true) {
                throw HlsTransferOutcomeException(DownloadTransferResult.Failed(DownloadFailure.SourceChanged))
            }
            updatePartLength(active, partKey, active.partLengths[partKey] ?: 0L, writer.lengthBytes)
        }
        if (resource.contentLength?.let { length -> length != total } == true) {
            throw HlsTransferOutcomeException(DownloadTransferResult.Failed(DownloadFailure.SourceChanged))
        }
    }

    private suspend fun closeCompletedPartWriter(
        active: HlsActiveAttempt,
        partKey: DownloadArtifactPartKey,
    ) {
        val writer = active.writers.remove(partKey) ?: return
        writer.checkpoint()
        writer.close()
    }

    private suspend fun persistProgressIfDue(
        active: HlsActiveAttempt,
        completedPartBytes: Long,
    ): DownloadTransferResult? {
        active.segmentsSincePersist += 1
        active.bytesSincePersist = checkedAdd(active.bytesSincePersist, completedPartBytes)
        val segmentThreshold =
            scaledCheckpointThreshold(
                value =
                    active.packageValue.media.segments.size
                        .toLong(),
                minimum = MIN_HLS_SEGMENTS_PER_CHECKPOINT.toLong(),
            ).toInt()
        val byteThreshold =
            scaledCheckpointThreshold(
                value = active.reservationBytes,
                minimum = MIN_HLS_BYTES_PER_CHECKPOINT,
            )
        if (active.segmentsSincePersist < segmentThreshold && active.bytesSincePersist < byteThreshold) return null
        return persistProgress(active)
    }

    private suspend fun persistProgress(active: HlsActiveAttempt): DownloadTransferResult? {
        val result =
            try {
                persistCheckpoint(active, closeWriters = false, allowReservationExtension = true)
            } catch (outcome: HlsTransferOutcomeException) {
                return settleRegisteredOutcome(active, outcome.outcome)
            }
        val durable =
            serverScopedStoreRegistry.withGuardedLease(active.lease) {
                queueCoordinator.updateRegisteredAttemptFacts(
                    attempt = active.attempt,
                    physicalBytes = result.physicalBytes,
                    checkpointBytes = result.checkpointBytes,
                )
            } ?: return DownloadTransferResult.BoundaryChanged
        if (durable) {
            active.segmentsSincePersist = 0
            active.bytesSincePersist = 0L
            return null
        }
        return DownloadTransferResult.BoundaryChanged
    }

    private suspend fun finalizeAndPromote(active: HlsActiveAttempt): DownloadTransferResult {
        persistProgress(active)?.let { return it }
        active.writers.values.forEach { writer -> writer.close() }
        active.writers.clear()
        val finalization =
            serverScopedStoreRegistry.withGuardedLease(active.lease) {
                queueCoordinator.finalizeRegisteredAttempt(
                    attempt = active.attempt,
                    validationFailure = DownloadFailure.UnsupportedArtifact,
                ) {
                    val inspection = artifactStore.inspect(active.record.request.artifactKey, DownloadArtifactArea.Staging)
                    val bytes =
                        artifactStore.readPart(
                            active.record.request.artifactKey,
                            DownloadArtifactArea.Staging,
                            DownloadHlsPartNames.CHECKPOINT,
                            MAX_HLS_CHECKPOINT_BYTES,
                        ) ?: return@finalizeRegisteredAttempt false
                    val checkpoint =
                        DownloadHlsCheckpoint.decode(bytes.decodeToString())
                            ?: return@finalizeRegisteredAttempt false
                    checkpoint.isCompatible(active.packageValue) &&
                        checkpoint.isComplete() &&
                        inspection.matchesHlsCheckpoint(checkpoint, bytes.size.toLong()) &&
                        artifactStore.validateStagingCheckpoint(
                            active.record.request.artifactKey,
                            DownloadArtifactCheckpoint(
                                checkpoint.artifactCheckpoint() +
                                    DownloadArtifactPartCheckpoint(DownloadHlsPartNames.CHECKPOINT, bytes.size.toLong()),
                            ),
                        )
                }
            } ?: return DownloadTransferResult.BoundaryChanged
        if (finalization is RegisteredAttemptFinalizationResult.InvalidArtifact) {
            return DownloadTransferResult.Failed(DownloadFailure.UnsupportedArtifact)
        }
        if (finalization is RegisteredAttemptFinalizationResult.StaleAttempt) {
            return DownloadTransferResult.BoundaryChanged
        }
        val promoted =
            try {
                serverScopedStoreRegistry.withGuardedLease(active.lease) {
                    queueCoordinator.withRunnerLock { artifactStore.promote(active.record.request.artifactKey) }
                } ?: return DownloadTransferResult.BoundaryChanged
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DownloadTransferResult.FinalizingPending
            }
        val canonicalPromotedArtifact =
            try {
                serverScopedStoreRegistry.withGuardedLease(active.lease) {
                    artifactStore.isCanonicalLocalHlsArtifact(
                        record =
                            active.record.copy(
                                state = DownloadState.Finalizing,
                                reservationBytes = active.reservationBytes,
                                physicalBytes = active.physicalBytes,
                                checkpointBytes = active.physicalBytes,
                            ),
                        inspection = promoted,
                        area = DownloadArtifactArea.Completed,
                    )
                } ?: return DownloadTransferResult.BoundaryChanged
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DownloadTransferResult.FinalizingPending
            }
        if (!canonicalPromotedArtifact) return DownloadTransferResult.FinalizingPending
        val completed =
            serverScopedStoreRegistry.withGuardedLease(active.lease) {
                queueCoordinator.completeFinalizing(active.attempt)
            } ?: return DownloadTransferResult.BoundaryChanged
        return if (completed) {
            DownloadTransferResult.Completed
        } else {
            DownloadTransferResult.FinalizingPending
        }
    }

    private suspend fun checkpointAndClose(
        active: HlsActiveAttempt,
        currentFacts: DownloadCheckpointFacts,
    ): DownloadCheckpointFacts =
        withContext(NonCancellable) {
            val durableFacts =
                try {
                    val facts = persistCheckpoint(active, closeWriters = true, allowReservationExtension = false)
                    DownloadCheckpointFacts(facts.physicalBytes, facts.checkpointBytes)
                } catch (failure: Throwable) {
                    fixedDownloadTransferLogger.w {
                        formatSafeFailureDiagnostic(
                            stage = "fixed-download",
                            event = "checkpoint-failed",
                            throwable = failure,
                        )
                    }
                    currentFacts
                }
            active.writers.values.toList().forEach { writer ->
                try {
                    writer.close()
                } catch (failure: Throwable) {
                    fixedDownloadTransferLogger.w {
                        formatSafeFailureDiagnostic(
                            stage = "fixed-download",
                            event = "writer-close-failed",
                            throwable = failure,
                        )
                    }
                }
            }
            active.writers.clear()
            durableFacts
        }

    private suspend fun persistCheckpoint(
        active: HlsActiveAttempt,
        closeWriters: Boolean,
        allowReservationExtension: Boolean,
    ): HlsByteFacts {
        if (closeWriters) {
            active.writers
                .filterKeys { key -> key != DownloadHlsPartNames.CHECKPOINT && key !in active.completedParts }
                .forEach { (key, writer) ->
                    val before = writer.lengthBytes
                    if (before > 0L) {
                        writer.rewrite(ByteArray(0))
                        replacePartLength(active, key, before, writer.lengthBytes)
                    }
                }
        }
        // Sync newly completed data parts before replacing the manifest. The manifest is the
        // durable claim that these exact lengths are safe to resume from; already-manifested
        // parts do not need another fsync on every subsequent segment. Completed media writers
        // are closed immediately after this durable boundary so one transfer never holds one
        // native/file handle per segment.
        active.completedParts
            .filter { partKey -> partKey !in active.durableCompletedParts }
            .forEach { partKey ->
                val writer = active.writers[partKey]
                if (writer != null) {
                    check(writer.lengthBytes > 0L)
                    writer.checkpoint()
                    if (partKey != DownloadHlsPartNames.CHECKPOINT) {
                        writer.close()
                        active.writers.remove(partKey)
                    }
                }
                check(active.partLengths[partKey]?.let { length -> length > 0L } == true)
            }
        val nextCheckpoint =
            active.checkpoint.copy(
                master =
                    active.checkpoint.master.updated(
                        active.partLengths[DownloadHlsPartNames.MASTER] ?: 0L,
                        DownloadHlsPartNames.MASTER in active.completedParts,
                    ),
                media =
                    active.checkpoint.media.updated(
                        active.partLengths[DownloadHlsPartNames.MEDIA] ?: 0L,
                        DownloadHlsPartNames.MEDIA in active.completedParts,
                    ),
                segments =
                    active.checkpoint.segments.mapIndexed { index, part ->
                        val key = DownloadHlsPartNames.segment(index)
                        part.updated(active.partLengths[key] ?: 0L, key in active.completedParts)
                    },
            )
        val encoded = nextCheckpoint.encode().encodeToByteArray()
        if (encoded.size > MAX_HLS_CHECKPOINT_BYTES) {
            throw HlsTransferOutcomeException(
                DownloadTransferResult.Failed(DownloadFailure.UnsupportedArtifact),
            )
        }
        val checkpointBefore = active.partLengths[DownloadHlsPartNames.CHECKPOINT] ?: 0L
        val checkpointGrowth = (encoded.size.toLong() - checkpointBefore).coerceAtLeast(0L)
        if (checkpointGrowth > 0L) {
            if (allowReservationExtension) {
                ensureReservation(active, checkpointGrowth)?.let { throw HlsTransferOutcomeException(it) }
            } else {
                if (checkedAdd(active.physicalBytes, checkpointGrowth) > active.reservationBytes) {
                    throw HlsTransferOutcomeException(DownloadTransferResult.BlockedByQuota)
                }
            }
        }
        val checkpoint =
            artifactStore.replaceStagingMetadata(
                artifactKey = active.record.request.artifactKey,
                partKey = DownloadHlsPartNames.CHECKPOINT,
                buffer = encoded,
            )
        check(checkpoint.partKey == DownloadHlsPartNames.CHECKPOINT)
        replacePartLength(active, DownloadHlsPartNames.CHECKPOINT, checkpointBefore, checkpoint.lengthBytes)
        active.checkpoint = nextCheckpoint
        active.durableCompletedParts += active.completedParts
        if (closeWriters) {
            active.writers.values.forEach { writer -> writer.close() }
            active.writers.clear()
        }
        return HlsByteFacts(active.physicalBytes, active.physicalBytes)
    }

    private suspend fun ensureReservation(
        active: HlsActiveAttempt,
        additionalBytes: Long,
    ): DownloadTransferResult? {
        val required = checkedAdd(active.physicalBytes, additionalBytes)
        if (required > active.reservationBytes) {
            val extension =
                serverScopedStoreRegistry.withGuardedLease(active.lease) {
                    queueCoordinator.extendReservation(active.attempt, required)
                } ?: return DownloadTransferResult.BoundaryChanged
            active.reservationBytes =
                when (extension) {
                    is DownloadReservationExtensionResult.Extended -> extension.reservationBytes
                    is DownloadReservationExtensionResult.Unchanged -> extension.reservationBytes
                    is DownloadReservationExtensionResult.Rejected -> return DownloadTransferResult.BlockedByQuota
                    DownloadReservationExtensionResult.StaleAttempt,
                    DownloadReservationExtensionResult.RemovalInProgress,
                    -> return DownloadTransferResult.BoundaryChanged
                }
        }
        if (required > active.reservationBytes) return DownloadTransferResult.BlockedByQuota
        val capacity = artifactStore.capacity()
        if (additionalBytes > (capacity.availableBytes - DOWNLOAD_DEVICE_SAFETY_RESERVE_BYTES).coerceAtLeast(0L)) {
            return DownloadTransferResult.BlockedByQuota
        }
        return null
    }

    private suspend fun settleFailure(
        active: HlsActiveAttempt,
        failure: DownloadFailure,
    ): DownloadTransferResult {
        val settled =
            serverScopedStoreRegistry.withGuardedLease(active.lease) {
                queueCoordinator.finishRegisteredAttempt(active.attempt, DownloadState.Failed, failure)
            } ?: return DownloadTransferResult.BoundaryChanged
        return if (settled) DownloadTransferResult.Failed(failure) else DownloadTransferResult.BoundaryChanged
    }

    private suspend fun settleRegisteredState(
        active: HlsActiveAttempt,
        state: DownloadState,
        result: DownloadTransferResult,
    ): DownloadTransferResult {
        val settled =
            serverScopedStoreRegistry.withGuardedLease(active.lease) {
                queueCoordinator.finishRegisteredAttempt(active.attempt, state)
            } ?: return DownloadTransferResult.BoundaryChanged
        return if (settled) result else DownloadTransferResult.BoundaryChanged
    }

    private suspend fun settleClaimedFailure(
        attempt: DownloadAttemptIdentity,
        failure: DownloadFailure,
        lease: AccountWorkLease,
    ): DownloadTransferResult {
        val settled =
            serverScopedStoreRegistry.withGuardedLease(lease) {
                queueCoordinator.failClaimedAttempt(attempt, failure)
            } ?: return DownloadTransferResult.BoundaryChanged
        return if (settled) DownloadTransferResult.Failed(failure) else DownloadTransferResult.BoundaryChanged
    }

    private suspend fun rejectTransfer(
        attempt: DownloadAttemptIdentity,
        failure: DownloadFailure,
        reason: FixedDownloadTransferDiagnosticReason,
        lease: AccountWorkLease,
    ): DownloadTransferResult {
        fixedDownloadTransferLogger.w {
            "stage=fixed-download event=transfer-rejected reason=${reason.name} " +
                "requestKind=Transfer result=${failure.name}"
        }
        return settleClaimedFailure(attempt, failure, lease)
    }

    private suspend fun settleClaimedOrRegisteredFailure(
        attempt: DownloadAttemptIdentity,
        lease: AccountWorkLease,
        failure: DownloadFailure,
    ): DownloadTransferResult {
        val registered =
            serverScopedStoreRegistry.withGuardedLease(lease) {
                queueCoordinator.finishRegisteredAttempt(attempt, DownloadState.Failed, failure)
            }
        if (registered == true) return DownloadTransferResult.Failed(failure)
        return settleClaimedFailure(attempt, failure, lease)
    }

    private suspend fun settleClaimedOrRegisteredState(
        attempt: DownloadAttemptIdentity,
        lease: AccountWorkLease,
        state: DownloadState,
        result: DownloadTransferResult,
    ): DownloadTransferResult {
        val registered =
            serverScopedStoreRegistry.withGuardedLease(lease) {
                queueCoordinator.finishRegisteredAttempt(attempt, state)
            }
        if (registered == true) return result
        return DownloadTransferResult.BoundaryChanged
    }

    private suspend fun failUnregisteredOrRegistered(
        attempt: DownloadAttemptIdentity,
        failure: DownloadFailure,
        lease: AccountWorkLease,
    ): DownloadTransferResult {
        val result =
            serverScopedStoreRegistry.withGuardedLease(lease) {
                queueCoordinator.finishRegisteredAttempt(attempt, DownloadState.Failed, failure)
            }
        if (result == true) return DownloadTransferResult.Failed(failure)
        val claimed =
            serverScopedStoreRegistry.withGuardedLease(lease) {
                queueCoordinator.failClaimedAttempt(attempt, failure)
            } ?: return DownloadTransferResult.BoundaryChanged
        return if (claimed) DownloadTransferResult.Failed(failure) else DownloadTransferResult.BoundaryChanged
    }

    private suspend fun checkpointForCancellation(
        attempt: DownloadAttemptIdentity,
        lease: AccountWorkLease,
        cancellation: CancellationException,
    ) {
        val nextState =
            if (cancellation.isLifecycleRequeueCancellation()) {
                DownloadState.Queued
            } else {
                DownloadState.Paused
            }
        withContext(NonCancellable) {
            try {
                serverScopedStoreRegistry.withGuardedLease(lease) {
                    queueCoordinator.finishRegisteredAttempt(attempt, nextState) ||
                        queueCoordinator.checkpointUnregisteredClaim(attempt, nextState).let { result ->
                            result is com.jellyscope.core.data.local.DownloadAttemptInvalidationResult.Invalidated
                        }
                }
            } catch (_: Throwable) {
                // Cancellation remains the caller's result even when durable close cannot finish.
            }
        }
    }

    private fun CancellationException.isLifecycleRequeueCancellation(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is DownloadLifecycleRequeueCancellation) return true
            current = current.cause
        }
        return false
    }

    private fun DownloadSubtitleSelection.fixedSubtitleIndex(): Int? = (this as? DownloadSubtitleSelection.Embedded)?.streamIndex

    private fun FixedDownloadFailure.toDownloadFailure(): DownloadFailure =
        when (this) {
            FixedDownloadFailure.AccountUnauthorized,
            FixedDownloadFailure.PermissionDenied,
            -> DownloadFailure.PermissionDenied
            FixedDownloadFailure.SourceUnavailable,
            FixedDownloadFailure.SourceChanged,
            -> DownloadFailure.SourceChanged
            FixedDownloadFailure.SizeUnavailable -> DownloadFailure.SizeUnavailable
            FixedDownloadFailure.UnsupportedArtifact -> DownloadFailure.UnsupportedArtifact
            FixedDownloadFailure.Network -> DownloadFailure.Network
            FixedDownloadFailure.ServerUnavailable -> DownloadFailure.ServerUnavailable
            FixedDownloadFailure.PayloadTooLarge -> DownloadFailure.UnsupportedArtifact
        }

    private fun DownloadHlsRejectReason.toDownloadFailure(): DownloadFailure =
        when (this) {
            DownloadHlsRejectReason.InvalidDuration,
            DownloadHlsRejectReason.InvalidSequence,
            DownloadHlsRejectReason.IncompletePlaylist,
            DownloadHlsRejectReason.MissingRequiredTag,
            -> DownloadFailure.SourceChanged
            else -> DownloadFailure.UnsupportedArtifact
        }

    private fun resolveHlsResourceUrl(
        baseUrl: String,
        relative: String,
    ): String? {
        if (relative.isBlank() || relative.startsWith('/') || relative.startsWith("//") || relative.contains('\\')) return null
        val baseWithoutFragment = baseUrl.substringBefore('#')
        val basePath = baseWithoutFragment.substringBefore('?')
        val baseQuery = baseWithoutFragment.substringAfter('?', missingDelimiterValue = "")
        val directory = basePath.substringBeforeLast('/', missingDelimiterValue = basePath)
        val resource = "$directory/${relative.trimStart('/')}"
        return if (relative.contains('?') || baseQuery.isBlank()) resource else "$resource?$baseQuery"
    }

    private data class HlsActiveAttempt(
        val record: DownloadRecord,
        val attempt: DownloadAttemptIdentity,
        val lease: AccountWorkLease,
        val packageValue: DownloadHlsPackage,
        var checkpoint: DownloadHlsCheckpoint,
        val writers: LinkedHashMap<DownloadArtifactPartKey, DownloadArtifactWriter>,
        val partLengths: MutableMap<DownloadArtifactPartKey, Long>,
        val completedParts: MutableSet<DownloadArtifactPartKey>,
        val durableCompletedParts: MutableSet<DownloadArtifactPartKey>,
        var reservationBytes: Long,
        var physicalBytes: Long,
        var segmentsSincePersist: Int = 0,
        var bytesSincePersist: Long = 0L,
    )

    private fun scaledCheckpointThreshold(
        value: Long,
        minimum: Long,
    ): Long {
        val scaled = value / TARGET_HLS_PERIODIC_CHECKPOINT_COUNT
        val roundedUp = scaled + if (value % TARGET_HLS_PERIODIC_CHECKPOINT_COUNT == 0L) 0L else 1L
        return roundedUp.coerceAtLeast(minimum)
    }

    private data class HlsByteFacts(
        val physicalBytes: Long,
        val checkpointBytes: Long,
    )

    private sealed interface HlsPreparation {
        data class Ready(
            val value: HlsActiveAttempt,
        ) : HlsPreparation

        data class Failed(
            val failure: DownloadFailure,
        ) : HlsPreparation

        data object BoundaryChanged : HlsPreparation
    }

    private sealed interface HlsFetchResult {
        data class Ready(
            val value: DownloadHlsPackage,
        ) : HlsFetchResult

        data class ReadyText(
            val text: String,
        ) : HlsFetchResult

        data class Failed(
            val failure: DownloadFailure,
            val fetchReason: FixedDownloadPlaylistFetchRejectReason? = null,
            val fixedFailure: FixedDownloadFailure? = null,
        ) : HlsFetchResult
    }

    private class HlsTransferOutcomeException(
        val outcome: DownloadTransferResult,
    ) : Exception()

    private fun DownloadHlsCheckpointPart.updated(
        lengthBytes: Long,
        complete: Boolean,
    ): DownloadHlsCheckpointPart = copy(lengthBytes = lengthBytes.takeIf { complete } ?: 0L, complete = complete)

    private fun DownloadArtifactInspection?.matchesHlsCheckpoint(
        checkpoint: DownloadHlsCheckpoint,
        checkpointLength: Long,
    ): Boolean {
        if (this == null || area != DownloadArtifactArea.Staging) return false
        val expected =
            checkpoint.artifactCheckpoint() +
                DownloadArtifactPartCheckpoint(DownloadHlsPartNames.CHECKPOINT, checkpointLength)
        return parts ==
            expected
                .map { part ->
                    com.jellyscope.core.data.local
                        .DownloadArtifactPartInspection(part.partKey, part.lengthBytes)
                }.sortedBy { part -> part.partKey.value }
    }

    private fun checkedAdd(
        left: Long,
        right: Long,
    ): Long {
        check(left >= 0L && right >= 0L && left <= Long.MAX_VALUE - right) { "HLS artifact size overflow." }
        return left + right
    }

    companion object {
        private const val MAX_HLS_SEGMENT_BYTES = 512L * 1024L * 1024L
        private const val TARGET_HLS_PERIODIC_CHECKPOINT_COUNT = 128L
        private const val MIN_HLS_SEGMENTS_PER_CHECKPOINT = 4
        private const val MIN_HLS_BYTES_PER_CHECKPOINT = 1L * 1024L * 1024L
    }
}

private enum class FixedDownloadPlaylistKind {
    SourcePlaylist,
    MasterPlaylist,
    MediaPlaylist,
    DirectMediaPlaylist,
}

private enum class FixedDownloadPlaylistFetchRejectReason {
    InvalidRequest,
    UntrustedResourceUrl,
    HttpStatusRejected,
    InvalidDeclaredLength,
    DeclaredLengthTooLarge,
    NetworkFailure,
    UnexpectedTransportFailure,
    StreamedBodyTooLarge,
    ContentLengthMismatch,
}

private fun FixedDownloadResourceRejectReason.toPlaylistFetchRejectReason(): FixedDownloadPlaylistFetchRejectReason =
    when (this) {
        FixedDownloadResourceRejectReason.InvalidRequest -> FixedDownloadPlaylistFetchRejectReason.InvalidRequest
        FixedDownloadResourceRejectReason.UntrustedResourceUrl -> FixedDownloadPlaylistFetchRejectReason.UntrustedResourceUrl
        FixedDownloadResourceRejectReason.HttpStatusRejected -> FixedDownloadPlaylistFetchRejectReason.HttpStatusRejected
        FixedDownloadResourceRejectReason.InvalidDeclaredLength -> FixedDownloadPlaylistFetchRejectReason.InvalidDeclaredLength
        FixedDownloadResourceRejectReason.DeclaredLengthTooLarge -> FixedDownloadPlaylistFetchRejectReason.DeclaredLengthTooLarge
        FixedDownloadResourceRejectReason.NetworkFailure -> FixedDownloadPlaylistFetchRejectReason.NetworkFailure
        FixedDownloadResourceRejectReason.UnexpectedTransportFailure ->
            FixedDownloadPlaylistFetchRejectReason.UnexpectedTransportFailure
    }

private enum class FixedDownloadTransferDiagnosticReason {
    QualityContractMismatch,
    PreflightRejected,
    SourceContractMismatch,
    PlaylistUrlRejected,
    PlaylistRejected,
    PackageProjectionMismatch,
    DurationMismatch,
    PreparationRejected,
}

private val fixedDownloadTransferLogger = diagnosticLogger(DiagnosticTag.FixedDownload)

/** Small bounded tolerance for decimal EXTINF rounding across a finite VOD playlist. */
private const val MIN_HLS_DURATION_ROUNDING_TOLERANCE_MS = 1L
private const val MAX_HLS_DURATION_ROUNDING_TOLERANCE_MS = 5_000L

internal fun hlsDurationMatchesSource(
    packageValue: DownloadHlsPackage,
    sourceDurationMs: Long,
): Boolean {
    val packageDurationMs =
        packageValue.media.segments.fold(0L) { total, segment ->
            check(total <= Long.MAX_VALUE - segment.durationMillis) { "HLS duration exceeds the supported range." }
            total + segment.durationMillis
        }
    val roundingToleranceMs =
        minOf(
            MAX_HLS_DURATION_ROUNDING_TOLERANCE_MS,
            maxOf(
                MIN_HLS_DURATION_ROUNDING_TOLERANCE_MS,
                packageValue.media.segments.size
                    .toLong(),
            ),
        )
    if (sourceDurationMs <= 0L) return false
    val durationDifferenceMs =
        if (packageDurationMs >= sourceDurationMs) {
            packageDurationMs - sourceDurationMs
        } else {
            sourceDurationMs - packageDurationMs
        }
    return durationDifferenceMs <= roundingToleranceMs
}
