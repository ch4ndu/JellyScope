// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.remote.AuthenticatedRequestContext
import com.jellyscope.core.data.remote.BaseItemDto
import com.jellyscope.core.data.remote.JellyfinApi
import com.jellyscope.core.data.remote.JellyfinApiException
import com.jellyscope.core.data.remote.MediaStreamDto
import com.jellyscope.core.data.remote.UploadSubtitleDto
import com.jellyscope.core.data.remote.UserDto
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleSyncState
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64

interface LocalSubtitleSyncRepository {
    suspend fun sync(
        session: Session,
        asset: LocalSubtitleAsset,
    )

    suspend fun retry(
        session: Session,
        asset: LocalSubtitleAsset,
    )
}

internal interface LocalSubtitleSyncApi {
    suspend fun getCurrentUser(context: AuthenticatedRequestContext): UserDto

    suspend fun getItemDetail(
        context: AuthenticatedRequestContext,
        itemId: String,
    ): BaseItemDto

    suspend fun uploadSubtitle(
        context: AuthenticatedRequestContext,
        itemId: String,
        subtitle: UploadSubtitleDto,
    )

    suspend fun getSubtitleText(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
        streamIndex: Int,
    ): String
}

internal class JellyfinLocalSubtitleSyncApi(
    private val api: JellyfinApi,
) : LocalSubtitleSyncApi {
    override suspend fun getCurrentUser(context: AuthenticatedRequestContext): UserDto = api.getCurrentUser(context)

    override suspend fun getItemDetail(
        context: AuthenticatedRequestContext,
        itemId: String,
    ): BaseItemDto = api.getItemDetail(context, itemId, includePlaybackFields = true)

    override suspend fun uploadSubtitle(
        context: AuthenticatedRequestContext,
        itemId: String,
        subtitle: UploadSubtitleDto,
    ) = api.uploadSubtitle(context, itemId, subtitle)

    override suspend fun getSubtitleText(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
        streamIndex: Int,
    ): String = api.getSubtitleText(context, itemId, mediaSourceId, streamIndex)
}

internal class DefaultLocalSubtitleSyncRepository(
    private val api: LocalSubtitleSyncApi,
    private val mutationCoordinator: LocalSubtitleMutationCoordinator,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val reconciliationAttempts: Int = RECONCILIATION_ATTEMPTS,
    private val reconciliationDelayMs: Long = RECONCILIATION_DELAY_MS,
    private val cpuOperations: LocalSubtitleSyncCpuOperations = DefaultLocalSubtitleSyncCpuOperations,
) : LocalSubtitleSyncRepository {
    override suspend fun sync(
        session: Session,
        asset: LocalSubtitleAsset,
    ) = sync(session, asset, manualRetry = false)

    override suspend fun retry(
        session: Session,
        asset: LocalSubtitleAsset,
    ) = sync(session, asset, manualRetry = true)

    private suspend fun sync(
        session: Session,
        asset: LocalSubtitleAsset,
        manualRetry: Boolean,
    ) {
        if (session.serverId != asset.serverId || session.userId != asset.userId) return
        val lease = mutationCoordinator.beginSync(asset) ?: return
        try {
            syncClaimed(session, lease, manualRetry)
        } finally {
            withContext(NonCancellable) { mutationCoordinator.releaseSync(lease) }
        }
    }

    private suspend fun syncClaimed(
        session: Session,
        lease: LocalSubtitleSyncLease,
        manualRetry: Boolean,
    ) {
        val asset = lease.asset
        val context = AuthenticatedRequestContext(session.serverUrl, session.userId, session.accessToken)
        val bytes = lease.bytes
        val localText =
            withContext(workerDispatcher) {
                cpuOperations.canonicalizeLocal(bytes)
            }
        val detail = api.getItemDetail(context, asset.itemId)
        val sources = detail.mediaSources
        val primary = sources.firstOrNull()
        if (sources.size > 1 && primary?.id != asset.mediaSourceId) {
            mutationCoordinator.applySyncUpdate(
                lease,
                asset.copy(syncState = LocalSubtitleSyncState.LocalOnlyAlternateSource),
                terminal = true,
            )
            return
        }
        val policy = api.getCurrentUser(context).policy
        if (policy?.enableSubtitleManagement != true) {
            mutationCoordinator.applySyncUpdate(
                lease,
                asset.copy(syncState = LocalSubtitleSyncState.PermissionDenied),
                terminal = true,
            )
            return
        }
        val requiresReconciliation =
            asset.syncState == LocalSubtitleSyncState.Uploading ||
                asset.syncState == LocalSubtitleSyncState.UploadedUnconfirmed ||
                asset.syncState == LocalSubtitleSyncState.Reconciling
        when (val match = findExactMatch(context, asset, primary?.mediaStreams.orEmpty(), localText)) {
            is MatchProbe.Match -> {
                mutationCoordinator.applySyncUpdate(lease, asset.confirmed(match.streamIndex), terminal = true)
                return
            }
            MatchProbe.Inconclusive -> if (!requiresReconciliation) return
            MatchProbe.NoMatch -> Unit
        }
        if (requiresReconciliation) {
            val reconciling = asset.copy(syncState = LocalSubtitleSyncState.Reconciling)
            if (!mutationCoordinator.applySyncUpdate(lease, reconciling)) return
            when (val match = reconcile(context, lease, reconciling, localText)) {
                is MatchProbe.Match -> {
                    mutationCoordinator.applySyncUpdate(lease, reconciling.confirmed(match.streamIndex), terminal = true)
                    return
                }
                MatchProbe.Inconclusive,
                MatchProbe.NoMatch,
                ->
                    if (!manualRetry || match == MatchProbe.Inconclusive) {
                        mutationCoordinator.applySyncUpdate(
                            lease,
                            reconciling.copy(syncState = LocalSubtitleSyncState.UploadedUnconfirmed),
                            terminal = true,
                        )
                        return
                    }
            }
        }
        val uploading =
            asset.copy(
                syncState = LocalSubtitleSyncState.Uploading,
                uploadBaseline = primary?.mediaStreams?.subtitleBaseline(),
            )
        val encodedUpload =
            withContext(workerDispatcher) {
                cpuOperations.encodeUpload(bytes)
            }
        if (!mutationCoordinator.applySyncUpdate(lease, uploading)) return
        try {
            api.uploadSubtitle(
                context,
                asset.itemId,
                UploadSubtitleDto(asset.language, "vtt", asset.forced, asset.hearingImpaired, encodedUpload),
            )
        } catch (exception: JellyfinApiException.Unauthorized) {
            mutationCoordinator.applySyncUpdate(
                lease,
                asset.copy(syncState = LocalSubtitleSyncState.PermissionDenied),
                terminal = true,
            )
            return
        } catch (exception: CancellationException) {
            withContext(NonCancellable) {
                mutationCoordinator.applySyncUpdate(
                    lease,
                    uploading.copy(syncState = LocalSubtitleSyncState.Reconciling),
                    terminal = true,
                )
            }
            throw exception
        } catch (exception: Throwable) {
            localSubtitleSyncLogger.w {
                formatSafeFailureDiagnostic(
                    stage = "upload",
                    event = "failed",
                    operation = DiagnosticOperation.LocalSubtitleUpload,
                    throwable = exception,
                )
            }
            // Dispatch may have reached Jellyfin. Reconcile and never repeat the POST automatically.
        }
        val reconciling = uploading.copy(syncState = LocalSubtitleSyncState.Reconciling)
        if (!mutationCoordinator.applySyncUpdate(lease, reconciling)) return
        when (val match = reconcile(context, lease, reconciling, localText)) {
            is MatchProbe.Match ->
                mutationCoordinator.applySyncUpdate(lease, reconciling.confirmed(match.streamIndex), terminal = true)
            MatchProbe.Inconclusive,
            MatchProbe.NoMatch,
            ->
                mutationCoordinator.applySyncUpdate(
                    lease,
                    reconciling.copy(syncState = LocalSubtitleSyncState.UploadedUnconfirmed),
                    terminal = true,
                )
        }
    }

    private suspend fun reconcile(
        context: AuthenticatedRequestContext,
        lease: LocalSubtitleSyncLease,
        asset: LocalSubtitleAsset,
        localText: String,
    ): MatchProbe {
        var conclusiveNoMatch = false
        repeat(reconciliationAttempts) { attempt ->
            if (!mutationCoordinator.isSyncCurrent(lease)) return MatchProbe.Inconclusive
            if (attempt > 0) delay(reconciliationDelayMs)
            val refreshed =
                try {
                    api.getItemDetail(context, asset.itemId)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    localSubtitleSyncLogger.w {
                        formatSafeFailureDiagnostic(
                            stage = "refresh-detail",
                            event = "failed",
                            operation = DiagnosticOperation.LocalSubtitleRefresh,
                            throwable = exception,
                        )
                    }
                    return@repeat
                }
            val source = refreshed.mediaSources.firstOrNull { it.id == asset.mediaSourceId } ?: refreshed.mediaSources.firstOrNull()
            when (val match = findExactMatch(context, asset, source?.mediaStreams.orEmpty(), localText)) {
                is MatchProbe.Match -> return match
                MatchProbe.NoMatch -> conclusiveNoMatch = true
                MatchProbe.Inconclusive -> Unit
            }
        }
        return if (conclusiveNoMatch) MatchProbe.NoMatch else MatchProbe.Inconclusive
    }

    private suspend fun findExactMatch(
        context: AuthenticatedRequestContext,
        asset: LocalSubtitleAsset,
        streams: List<MediaStreamDto>,
        localText: String,
    ): MatchProbe {
        val candidates =
            streams
                .asSequence()
                .filter { stream -> stream.type.equals("Subtitle", true) && stream.language.equals(asset.language, true) }
                .filter { stream -> (stream.isForced == true) == asset.forced }
                .filter { stream -> (stream.isHearingImpaired == true) == asset.hearingImpaired }
                .mapNotNull(MediaStreamDto::index)
                .toList()
        var inconclusive = false
        for (index in candidates) {
            val remoteBody =
                try {
                    api.getSubtitleText(context, asset.itemId, asset.mediaSourceId, index)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    localSubtitleSyncLogger.w {
                        formatSafeFailureDiagnostic(
                            stage = "fetch-server-subtitle",
                            event = "failed",
                            operation = DiagnosticOperation.LocalSubtitleFetch,
                            throwable = exception,
                        )
                    }
                    inconclusive = true
                    null
                }
            val remoteText =
                remoteBody?.let { body ->
                    withContext(workerDispatcher) {
                        cpuOperations.canonicalizeRemote(body)
                    }
                }
            if (remoteText == localText) {
                return MatchProbe.Match(index)
            }
        }
        return if (inconclusive) MatchProbe.Inconclusive else MatchProbe.NoMatch
    }
}

internal interface LocalSubtitleSyncCpuOperations {
    fun canonicalizeLocal(bytes: ByteArray): String

    fun canonicalizeRemote(body: String): String

    fun encodeUpload(bytes: ByteArray): String
}

internal object DefaultLocalSubtitleSyncCpuOperations : LocalSubtitleSyncCpuOperations {
    override fun canonicalizeLocal(bytes: ByteArray): String = bytes.decodeToString().canonicalWebVtt()

    override fun canonicalizeRemote(body: String): String = body.canonicalWebVtt()

    override fun encodeUpload(bytes: ByteArray): String = Base64.encode(bytes)
}

private sealed interface MatchProbe {
    data class Match(
        val streamIndex: Int,
    ) : MatchProbe

    data object NoMatch : MatchProbe

    data object Inconclusive : MatchProbe
}

private fun LocalSubtitleAsset.confirmed(streamIndex: Int): LocalSubtitleAsset =
    copy(syncState = LocalSubtitleSyncState.Confirmed(streamIndex), confirmedStreamIndex = streamIndex)

private fun String.canonicalWebVtt(): String = replace("\r\n", "\n").replace('\r', '\n').trim()

private fun List<MediaStreamDto>.subtitleBaseline(): String =
    filter {
        it.type.equals("Subtitle", true)
    }.mapNotNull(MediaStreamDto::index).sorted().joinToString(",")

private const val RECONCILIATION_ATTEMPTS = 4
private const val RECONCILIATION_DELAY_MS = 1_500L

private val localSubtitleSyncLogger = diagnosticLogger(DiagnosticTag.LocalSubtitleSync)
