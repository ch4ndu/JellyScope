// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleSyncState
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class LocalSubtitleSyncCoordinator(
    sessionRepository: SessionRepository,
    assetStore: LocalSubtitleAssetStore,
    storageReconciler: LocalSubtitleStorageReconciler,
    private val syncRepository: LocalSubtitleSyncRepository,
    scope: CoroutineScope,
) {
    init {
        scope.launch {
            try {
                storageReconciler.reconcile()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                localSubtitleCoordinatorLogger.w {
                    formatSafeFailureDiagnostic(
                        stage = "storage-reconcile",
                        event = "failed",
                        operation = DiagnosticOperation.LocalSubtitleRefresh,
                        throwable = throwable,
                    )
                }
            }
            sessionRepository.sessionState.collectLatest { state ->
                val session = (state as? SessionState.LoggedIn)?.session ?: return@collectLatest
                assetStore
                    .all()
                    .filter { asset ->
                        asset.serverId == session.serverId &&
                            asset.userId == session.userId &&
                            asset.syncState == LocalSubtitleSyncState.UploadedUnconfirmed
                    }.forEach { asset -> syncSafely(session, asset) }
                assetStore.observePendingSync().collect { assets ->
                    assets
                        .filter { it.serverId == session.serverId && it.userId == session.userId }
                        .forEach { asset -> syncSafely(session, asset) }
                }
            }
        }
    }

    private suspend fun syncSafely(
        session: Session,
        asset: LocalSubtitleAsset,
    ) {
        try {
            syncRepository.sync(session, asset)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            localSubtitleCoordinatorLogger.w {
                formatSafeFailureDiagnostic(
                    stage = "sync",
                    event = "failed",
                    operation = DiagnosticOperation.LocalSubtitleRefresh,
                    throwable = throwable,
                )
            }
        }
    }
}

private val localSubtitleCoordinatorLogger = diagnosticLogger(DiagnosticTag.LocalSubtitleSync)
