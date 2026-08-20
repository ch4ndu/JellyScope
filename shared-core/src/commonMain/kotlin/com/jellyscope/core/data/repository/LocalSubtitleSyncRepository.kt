// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
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
import kotlinx.coroutines.delay
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
    private val assetStore: LocalSubtitleAssetStore,
    private val fileStore: LocalSubtitleFileStore,
    private val reconciliationAttempts: Int = RECONCILIATION_ATTEMPTS,
    private val reconciliationDelayMs: Long = RECONCILIATION_DELAY_MS,
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
        val context = AuthenticatedRequestContext(session.serverUrl, session.userId, session.accessToken)
        val bytes =
            fileStore.read(asset.fileId) ?: run {
                localSubtitleSyncLogger.w {
                    "stage=read-local-file event=missing operation=${DiagnosticOperation.LocalSubtitleRead.wireValue}"
                }
                return assetStore.delete(asset.id)
            }
        val localText = bytes.decodeToString().canonicalWebVtt()
        val detail = api.getItemDetail(context, asset.itemId)
        val sources = detail.mediaSources
        val primary = sources.firstOrNull()
        if (sources.size > 1 && primary?.id != asset.mediaSourceId) {
            assetStore.upsert(asset.copy(syncState = LocalSubtitleSyncState.LocalOnlyAlternateSource))
            return
        }
        val policy = api.getCurrentUser(context).policy
        if (policy?.enableSubtitleManagement != true) {
            assetStore.upsert(asset.copy(syncState = LocalSubtitleSyncState.PermissionDenied))
            return
        }
        val requiresReconciliation =
            asset.syncState == LocalSubtitleSyncState.Uploading ||
                asset.syncState == LocalSubtitleSyncState.UploadedUnconfirmed ||
                asset.syncState == LocalSubtitleSyncState.Reconciling
        when (val match = findExactMatch(context, asset, primary?.mediaStreams.orEmpty(), localText)) {
            is MatchProbe.Match -> {
                assetStore.upsert(asset.confirmed(match.streamIndex))
                return
            }
            MatchProbe.Inconclusive -> if (!requiresReconciliation) return
            MatchProbe.NoMatch -> Unit
        }
        if (requiresReconciliation) {
            val reconciling = asset.copy(syncState = LocalSubtitleSyncState.Reconciling)
            assetStore.upsert(reconciling)
            when (val match = reconcile(context, reconciling, localText)) {
                is MatchProbe.Match -> {
                    assetStore.upsert(reconciling.confirmed(match.streamIndex))
                    return
                }
                MatchProbe.Inconclusive,
                MatchProbe.NoMatch,
                ->
                    if (!manualRetry || match == MatchProbe.Inconclusive) {
                        assetStore.upsert(reconciling.copy(syncState = LocalSubtitleSyncState.UploadedUnconfirmed))
                        return
                    }
            }
        }
        val uploading =
            asset.copy(
                syncState = LocalSubtitleSyncState.Uploading,
                uploadBaseline = primary?.mediaStreams?.subtitleBaseline(),
            )
        assetStore.upsert(uploading)
        try {
            api.uploadSubtitle(
                context,
                asset.itemId,
                UploadSubtitleDto(asset.language, "vtt", asset.forced, asset.hearingImpaired, Base64.encode(bytes)),
            )
        } catch (exception: JellyfinApiException.Unauthorized) {
            assetStore.upsert(asset.copy(syncState = LocalSubtitleSyncState.PermissionDenied))
            return
        } catch (exception: CancellationException) {
            if (assetStore.get(asset.id) != null) {
                assetStore.upsert(uploading.copy(syncState = LocalSubtitleSyncState.Reconciling))
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
        if (assetStore.get(asset.id) == null) return
        assetStore.upsert(reconciling)
        when (val match = reconcile(context, reconciling, localText)) {
            is MatchProbe.Match -> assetStore.upsert(reconciling.confirmed(match.streamIndex))
            MatchProbe.Inconclusive,
            MatchProbe.NoMatch,
            -> assetStore.upsert(reconciling.copy(syncState = LocalSubtitleSyncState.UploadedUnconfirmed))
        }
    }

    private suspend fun reconcile(
        context: AuthenticatedRequestContext,
        asset: LocalSubtitleAsset,
        localText: String,
    ): MatchProbe {
        var conclusiveNoMatch = false
        repeat(reconciliationAttempts) { attempt ->
            if (assetStore.get(asset.id) == null) return MatchProbe.Inconclusive
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
            val remoteText =
                try {
                    api.getSubtitleText(context, asset.itemId, asset.mediaSourceId, index).canonicalWebVtt()
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
            if (remoteText == localText) {
                return MatchProbe.Match(index)
            }
        }
        return if (inconclusive) MatchProbe.Inconclusive else MatchProbe.NoMatch
    }
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
