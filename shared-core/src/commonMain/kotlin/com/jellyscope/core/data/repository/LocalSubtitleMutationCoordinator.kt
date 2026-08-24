// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.local.SubtitleSelectionStore
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one application-lifetime owner for local subtitle file, metadata, and
 * selection mutations. The lock only reserves logical order and FIFO ingress;
 * every persistence operation runs in the actor.
 */
class LocalSubtitleMutationCoordinator(
    private val assetStore: LocalSubtitleAssetStore?,
    private val fileStore: LocalSubtitleFileStore?,
    private val selectionStore: SubtitleSelectionStore,
    scope: CoroutineScope,
) {
    private val submissionLock = ReentrantLock()
    private var nextOrder = 0L
    private val latestIntentOrders = mutableMapOf<SubtitleSelectionKey, Long>()
    private val keyBarrierOrders = mutableMapOf<SubtitleSelectionKey, Long>()
    private val accountBarrierOrders = mutableMapOf<AccountIdentity, Long>()
    private val serverBarrierOrders = mutableMapOf<String, Long>()
    private var globalBarrierOrder = 0L
    private val assetOrders = mutableMapOf<String, Long>()
    private val syncClaims = mutableMapOf<String, Long>()

    private val requests =
        Channel<MutationRequest<*>>(
            capacity = Channel.UNLIMITED,
            onUndeliveredElement = { request ->
                request.terminate(CancellationException(WRITER_UNAVAILABLE))
            },
        )

    private val requestSupervisor = SupervisorJob(scope.coroutineContext[Job])
    private val requestScope = CoroutineScope(scope.coroutineContext + requestSupervisor)
    private val writerJob =
        scope.launch {
            try {
                for (request in requests) {
                    request.run()
                }
            } finally {
                requests.cancel(CancellationException(WRITER_UNAVAILABLE))
            }
        }

    init {
        writerJob.invokeOnCompletion {
            val cause = CancellationException(WRITER_UNAVAILABLE)
            requests.cancel(cause)
            requestSupervisor.cancel(cause)
        }
    }

    internal fun submitSelection(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    ): Deferred<Unit> {
        val request =
            submitImmediate(
                reserve = { order -> latestIntentOrders[key] = order },
            ) { transaction, order ->
                val previous = selectionStore.get(key)
                var selectionMayHaveChanged = false
                transaction.compensateWith {
                    if (selectionMayHaveChanged) restoreSelectionIfMatching(key, selection, previous)
                }
                transaction.ensureMutationActive()
                if (selection is SubtitleSelectionIntent.LocalAsset) {
                    requireValidScopedAsset(key, selection.assetId)
                    checkNoNewerBarrier(key, order)
                    check(latestIntentOrder(key) == order) { "Subtitle selection intent was superseded." }
                }
                transaction.ensureMutationActive()
                selectionMayHaveChanged = true
                selectionStore.persist(key, selection)
                transaction.ensureMutationActive()
            }
        return requestScope.async(start = CoroutineStart.UNDISPATCHED) {
            submitAndAwait(request)
        }
    }

    internal suspend fun currentSelection(key: SubtitleSelectionKey): SubtitleSelectionIntent? =
        submitAndAwait(submitImmediate { _, _ -> selectionStore.get(key) })

    internal suspend fun selectionsForItems(
        serverId: String,
        userId: String,
        itemIds: List<String>,
    ): Map<Pair<String, String>, SubtitleSelectionIntent> =
        submitAndAwait(
            submitImmediate { _, _ -> selectionStore.getForItems(serverId, userId, itemIds) },
        )

    internal fun reserveInstall(context: LocalSubtitleContext): LocalSubtitleInstallReservation {
        val key = context.selectionKey()
        return submissionLock.withLock {
            val order = nextLogicalOrder()
            latestIntentOrders[key] = order
            LocalSubtitleInstallReservation(key = key, order = order)
        }
    }

    internal suspend fun commitInstall(
        reservation: LocalSubtitleInstallReservation,
        candidate: LocalSubtitleAsset,
        webVttBytes: ByteArray,
    ): LocalSubtitleInstallResult {
        require(candidate.context() == reservation.key.context()) { "Install reservation does not match the subtitle asset." }
        val request =
            enqueueDelayed(reservation.order) { transaction, _ ->
                checkNoNewerBarrier(reservation.key, reservation.order)
                val assets = requireAssetStore()
                val files = requireFileStore()
                val existing =
                    assets.findByProviderFile(
                        context = candidate.context(),
                        provider = candidate.provider,
                        providerFileId = candidate.providerFileId,
                    )
                val asset = existing ?: candidate
                val fileWasPresent = existing?.let { files.exists(it.fileId) } == true
                var fileMayExist = false
                var assetMayExist = false
                var selectionMayHaveChanged = false
                val previousSelection = selectionStore.get(reservation.key)
                transaction.compensateWith {
                    if (selectionMayHaveChanged) {
                        attemptCleanup(transaction) {
                            if (selectionStore.get(reservation.key) == SubtitleSelectionIntent.LocalAsset(asset.id)) {
                                restoreSelection(reservation.key, previousSelection)
                            }
                        }
                    }
                    if (assetMayExist) attemptCleanup(transaction) { assets.delete(asset.id) }
                    if (fileMayExist) attemptCleanup(transaction) { files.delete(asset.fileId) }
                    submissionLock.withLock {
                        if (assetOrders[asset.id] == reservation.order) assetOrders.remove(asset.id)
                    }
                }

                if (!fileWasPresent) {
                    transaction.ensureMutationActive()
                    checkNoNewerBarrier(reservation.key, reservation.order)
                    fileMayExist = true
                    files.writeAtomically(asset.fileId, webVttBytes)
                }
                if (existing == null) {
                    transaction.ensureMutationActive()
                    checkNoNewerBarrier(reservation.key, reservation.order)
                    assetMayExist = true
                    assets.upsert(asset)
                }
                if (existing == null || !fileWasPresent) {
                    recordAssetOrder(asset.id, reservation.order)
                }

                val selectionApplied =
                    latestIntentOrder(reservation.key) == reservation.order &&
                        !hasNewerBarrier(reservation.key, reservation.order)
                if (selectionApplied) {
                    transaction.ensureMutationActive()
                    requireValidScopedAsset(reservation.key, asset.id)
                    selectionMayHaveChanged = true
                    selectionStore.save(reservation.key, SubtitleSelectionIntent.LocalAsset(asset.id))
                }
                transaction.ensureMutationActive()
                if (hasNewerBarrier(reservation.key, reservation.order)) {
                    throw LocalSubtitleMutationSupersededException()
                }
                LocalSubtitleInstallResult(asset = asset, selectionApplied = selectionApplied)
            }
        return submitAndAwait(request)
    }

    internal suspend fun deleteAsset(assetId: String) {
        val request =
            submitImmediate { transaction, order ->
                val assets = requireAssetStore()
                val files = requireFileStore()
                val asset = assets.get(assetId) ?: return@submitImmediate Unit
                val key = asset.context().selectionKey()
                val cleanupTarget = AssetCleanupTarget(asset, key)
                establishKeyBarrier(key, order)
                transaction.compensateWith {
                    compensateAssetCleanup(transaction, cleanupTarget)
                }
                transaction.ensureMutationActive()
                deleteMatchingSelection(key, asset.id)
                transaction.ensureMutationActive()
                assets.delete(asset.id)
                transaction.ensureMutationActive()
                files.delete(asset.fileId)
                submissionLock.withLock {
                    assetOrders.remove(asset.id)
                    syncClaims.remove(asset.id)
                }
                Unit
            }
        submitAndAwait(request)
    }

    internal suspend fun clearAllLocalSubtitles() {
        val request =
            submitBarrier(BarrierScope.Global) { transaction, _ ->
                val files = requireFileStore()
                val assets = requireAssetStore()
                val fileIds = (assets.all().map(LocalSubtitleAsset::fileId) + files.listFileIds()).toSet()
                transaction.compensateWith {
                    completeLocalClear(transaction, fileIds, checkCancellation = false)
                }
                completeLocalClear(transaction, fileIds, checkCancellation = true)
            }
        submitAndAwait(request)
    }

    internal suspend fun reconcileStorage() {
        val request =
            submitBarrier(BarrierScope.Global) { transaction, order ->
                val snapshot = captureReconciliationSnapshot(transaction)
                transaction.compensateWith { compensateReconciliation(transaction, order, snapshot) }
                val failures = reconcileStorageNow(transaction, order, snapshot, checkCancellation = true)
                failures.throwFirstWithSuppressed()
            }
        submitAndAwait(request)
    }

    internal suspend fun validAsset(
        assetId: String,
        expectedContext: LocalSubtitleContext?,
    ): LocalSubtitleAsset? =
        submitAndAwait(
            submitImmediate { transaction, order ->
                val asset = requireAssetStore().get(assetId) ?: return@submitImmediate null
                if (expectedContext != null && asset.context() != expectedContext) return@submitImmediate null
                if (!requireFileStore().exists(asset.fileId)) {
                    val key = asset.context().selectionKey()
                    val cleanupTarget = AssetCleanupTarget(asset, key)
                    establishKeyBarrier(key, order)
                    transaction.compensateWith { compensateAssetCleanup(transaction, cleanupTarget) }
                    val failures = mutableListOf<Throwable>()
                    collectAssetCleanupFailures(cleanupTarget, failures)
                    failures.throwFirstWithSuppressed()
                    null
                } else {
                    asset
                }
            },
        )

    internal suspend fun beginSync(expected: LocalSubtitleAsset): LocalSubtitleSyncLease? {
        val request =
            submitImmediate { transaction, order ->
                val assets = requireAssetStore()
                val files = requireFileStore()
                val current = assets.get(expected.id) ?: return@submitImmediate null
                if (!current.sameGeneration(expected)) return@submitImmediate null
                val claimed =
                    submissionLock.withLock {
                        if (syncClaims.containsKey(current.id)) {
                            false
                        } else {
                            assetOrders[current.id] = order
                            syncClaims[current.id] = order
                            true
                        }
                    }
                if (!claimed) return@submitImmediate null
                transaction.compensateWith { releaseSyncClaim(current.id, order) }
                val bytes = files.read(current.fileId)
                if (bytes == null) {
                    val key = current.context().selectionKey()
                    val cleanupTarget = AssetCleanupTarget(current, key)
                    establishKeyBarrier(key, order)
                    transaction.compensateWith { compensateAssetCleanup(transaction, cleanupTarget) }
                    val failures = mutableListOf<Throwable>()
                    collectAssetCleanupFailures(cleanupTarget, failures)
                    failures.throwFirstWithSuppressed()
                    return@submitImmediate null
                }
                LocalSubtitleSyncLease(asset = current, bytes = bytes, order = order)
            }
        return awaitSyncLeaseOwnershipHandoff(request)
    }

    private suspend fun awaitSyncLeaseOwnershipHandoff(request: MutationRequest<LocalSubtitleSyncLease?>): LocalSubtitleSyncLease? =
        try {
            request.completion.await()
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                request.cancelAndAwait(cancellation)
                val committedLease =
                    try {
                        request.completion.await()
                    } catch (_: Throwable) {
                        null
                    }
                if (committedLease != null) {
                    try {
                        releaseSync(committedLease)
                    } catch (releaseFailure: Throwable) {
                        cancellation.addSuppressedDistinct(releaseFailure)
                    }
                }
            }
            throw cancellation
        }

    internal suspend fun applySyncUpdate(
        lease: LocalSubtitleSyncLease,
        updated: LocalSubtitleAsset,
        terminal: Boolean = false,
    ): Boolean =
        submitAndAwait(
            submitImmediate { transaction, _ ->
                val assets = requireAssetStore()
                var previous: LocalSubtitleAsset? = null
                var rowMayHaveChanged = false
                transaction.compensateWith {
                    try {
                        val captured = previous
                        if (rowMayHaveChanged && captured != null) {
                            restoreSyncUpdateIfOwned(assets, lease, captured)
                        }
                    } finally {
                        releaseSyncClaim(lease)
                    }
                }
                val current = assets.get(lease.asset.id)
                previous = current
                val applies =
                    current != null &&
                        current.sameGeneration(lease.asset) &&
                        updated.sameGeneration(lease.asset) &&
                        isSyncLeaseCurrent(lease)
                if (terminal || !applies) {
                    transaction.afterCommit { releaseSyncClaim(lease) }
                }
                if (applies) {
                    transaction.ensureMutationActive()
                    rowMayHaveChanged = true
                    assets.upsert(updated)
                    transaction.ensureMutationActive()
                }
                applies
            },
        )

    internal suspend fun isSyncCurrent(lease: LocalSubtitleSyncLease): Boolean =
        submitAndAwait(
            submitImmediate { _, _ ->
                val current = requireAssetStore().get(lease.asset.id)
                current != null && current.sameGeneration(lease.asset) && isSyncLeaseCurrent(lease)
            },
        )

    internal suspend fun releaseSync(lease: LocalSubtitleSyncLease) {
        submitAndAwait(
            submitImmediate { _, _ ->
                releaseSyncClaim(lease)
            },
        )
    }

    internal suspend fun advanceAccountBarrier(accountIdentity: AccountIdentity) {
        submitAndAwait(
            submitBarrier(BarrierScope.Account(accountIdentity)) { _, _ -> Unit },
        )
    }

    internal suspend fun clearNonLocalSelectionsForAccount(accountIdentity: AccountIdentity) {
        submitAndAwait(
            submitBarrier(BarrierScope.Account(accountIdentity)) { transaction, _ ->
                transaction.compensateWith {
                    selectionStore.clearNonLocalAccountSelections(accountIdentity.serverId, accountIdentity.userId)
                }
                selectionStore.clearNonLocalAccountSelections(accountIdentity.serverId, accountIdentity.userId)
            },
        )
    }

    internal suspend fun clearNonLocalSelectionsForServer(serverId: String) {
        submitAndAwait(
            submitBarrier(BarrierScope.Server(serverId)) { transaction, _ ->
                transaction.compensateWith { selectionStore.clearServerScoped(serverId) }
                selectionStore.clearServerScoped(serverId)
            },
        )
    }

    internal suspend fun clearAllNonLocalSelections() {
        submitAndAwait(
            submitBarrier(BarrierScope.Global) { transaction, _ ->
                transaction.compensateWith { selectionStore.clearServerScoped() }
                selectionStore.clearServerScoped()
            },
        )
    }

    internal suspend fun clearLocalAssetSelectionsOnly() {
        submitAndAwait(
            submitBarrier(BarrierScope.Global) { transaction, _ ->
                transaction.compensateWith { selectionStore.clearLocalAssetSelections() }
                selectionStore.clearLocalAssetSelections()
            },
        )
    }

    private suspend fun completeLocalClear(
        transaction: MutationRequest<*>,
        fileIds: Set<String>,
        checkCancellation: Boolean,
    ) {
        val failures = mutableListOf<Throwable>()
        attemptAll(failures) { selectionStore.clearLocalAssetSelections() }
        if (checkCancellation) transaction.ensureMutationActive()
        attemptAll(failures) { requireAssetStore().clearAll() }
        if (checkCancellation) transaction.ensureMutationActive()
        fileIds.forEach { fileId ->
            attemptAll(failures) { requireFileStore().delete(fileId) }
            if (checkCancellation) transaction.ensureMutationActive()
        }
        submissionLock.withLock {
            assetOrders.clear()
            syncClaims.clear()
        }
        failures.throwFirstWithSuppressed()
    }

    private suspend fun captureReconciliationSnapshot(transaction: MutationRequest<*>): ReconciliationCleanupSnapshot {
        val assets = requireAssetStore()
        val files = requireFileStore()
        val missingAssets = mutableListOf<AssetCleanupTarget>()
        val referencedFiles = mutableSetOf<String>()
        transaction.ensureMutationActive()
        assets.all().forEach { asset ->
            transaction.ensureMutationActive()
            if (files.exists(asset.fileId)) {
                referencedFiles += asset.fileId
            } else {
                missingAssets += AssetCleanupTarget(asset, asset.context().selectionKey())
            }
        }
        transaction.ensureMutationActive()
        val orphanFileIds = files.listFileIds() - referencedFiles
        transaction.ensureMutationActive()
        return ReconciliationCleanupSnapshot(
            missingAssets = missingAssets.toList(),
            orphanFileIds = orphanFileIds.toSet(),
        )
    }

    private suspend fun reconcileStorageNow(
        transaction: MutationRequest<*>,
        order: Long,
        snapshot: ReconciliationCleanupSnapshot,
        checkCancellation: Boolean,
    ): List<Throwable> {
        val failures = mutableListOf<Throwable>()
        snapshot.missingAssets.forEach { target ->
            if (checkCancellation) transaction.ensureMutationActive()
            establishKeyBarrier(target.key, order)
            collectAssetCleanupFailures(target, failures)
            if (checkCancellation) transaction.ensureMutationActive()
        }
        snapshot.orphanFileIds.forEach { fileId ->
            if (checkCancellation) transaction.ensureMutationActive()
            attemptAll(failures) { requireFileStore().delete(fileId) }
            if (checkCancellation) transaction.ensureMutationActive()
        }
        return failures
    }

    private suspend fun compensateReconciliation(
        transaction: MutationRequest<*>,
        order: Long,
        snapshot: ReconciliationCleanupSnapshot,
    ) {
        reconcileStorageNow(transaction, order, snapshot, checkCancellation = false)
            .forEach(transaction::recordCleanupFailure)
    }

    private suspend fun compensateAssetCleanup(
        transaction: MutationRequest<*>,
        target: AssetCleanupTarget,
    ) {
        val failures = mutableListOf<Throwable>()
        collectAssetCleanupFailures(target, failures)
        failures.forEach(transaction::recordCleanupFailure)
    }

    private suspend fun collectAssetCleanupFailures(
        target: AssetCleanupTarget,
        failures: MutableList<Throwable>,
    ) {
        attemptAll(failures) { deleteMatchingSelection(target.key, target.asset.id) }
        attemptAll(failures) { requireAssetStore().delete(target.asset.id) }
        attemptAll(failures) { requireFileStore().delete(target.asset.fileId) }
        submissionLock.withLock {
            assetOrders.remove(target.asset.id)
            syncClaims.remove(target.asset.id)
        }
    }

    private suspend fun deleteMatchingSelection(
        key: SubtitleSelectionKey,
        assetId: String,
    ) {
        if (selectionStore.get(key) == SubtitleSelectionIntent.LocalAsset(assetId)) {
            selectionStore.delete(key)
        }
    }

    private suspend fun restoreSelection(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent?,
    ) {
        if (selection == null || selection == SubtitleSelectionIntent.Unspecified) {
            selectionStore.delete(key)
        } else {
            selectionStore.save(key, selection)
        }
    }

    private suspend fun restoreSelectionIfMatching(
        key: SubtitleSelectionKey,
        attempted: SubtitleSelectionIntent,
        previous: SubtitleSelectionIntent?,
    ) {
        val expected = attempted.takeUnless { it == SubtitleSelectionIntent.Unspecified }
        val current = selectionStore.get(key)
        if (current == expected && current != previous) restoreSelection(key, previous)
    }

    private suspend fun requireValidScopedAsset(
        key: SubtitleSelectionKey,
        assetId: String,
    ) {
        val asset = requireAssetStore().get(assetId)
        require(asset != null && asset.context() == key.context()) {
            "Local subtitle selection does not reference an asset in this playback scope."
        }
        require(requireFileStore().exists(asset.fileId)) {
            "Local subtitle selection references a missing file."
        }
    }

    private fun establishKeyBarrier(
        key: SubtitleSelectionKey,
        order: Long,
    ) {
        submissionLock.withLock {
            if ((keyBarrierOrders[key] ?: 0L) < order) keyBarrierOrders[key] = order
            if ((latestIntentOrders[key] ?: 0L) < order) latestIntentOrders[key] = order
        }
    }

    private fun checkNoNewerBarrier(
        key: SubtitleSelectionKey,
        order: Long,
    ) {
        if (hasNewerBarrier(key, order)) throw LocalSubtitleMutationSupersededException()
    }

    private fun hasNewerBarrier(
        key: SubtitleSelectionKey,
        order: Long,
    ): Boolean =
        submissionLock.withLock {
            globalBarrierOrder > order ||
                (serverBarrierOrders[key.serverId] ?: 0L) > order ||
                (accountBarrierOrders[AccountIdentity(key.serverId, key.userId)] ?: 0L) > order ||
                (keyBarrierOrders[key] ?: 0L) > order
        }

    private fun latestIntentOrder(key: SubtitleSelectionKey): Long? = submissionLock.withLock { latestIntentOrders[key] }

    private fun isSyncLeaseCurrent(lease: LocalSubtitleSyncLease): Boolean =
        submissionLock.withLock {
            syncClaims[lease.asset.id] == lease.order &&
                assetOrders[lease.asset.id] == lease.order &&
                !hasNewerBarrierLocked(lease.asset.context().selectionKey(), lease.order)
        }

    private suspend fun restoreSyncUpdateIfOwned(
        assets: LocalSubtitleAssetStore,
        lease: LocalSubtitleSyncLease,
        previous: LocalSubtitleAsset,
    ) {
        val current = assets.get(lease.asset.id)
        if (
            previous.sameGeneration(lease.asset) &&
            current?.sameGeneration(lease.asset) == true &&
            isSyncLeaseCurrent(lease)
        ) {
            assets.upsert(previous)
        }
    }

    private fun releaseSyncClaim(lease: LocalSubtitleSyncLease) {
        releaseSyncClaim(lease.asset.id, lease.order)
    }

    private fun releaseSyncClaim(
        assetId: String,
        order: Long,
    ) {
        submissionLock.withLock {
            if (syncClaims[assetId] == order) {
                syncClaims.remove(assetId)
                if (assetOrders[assetId] == order) assetOrders.remove(assetId)
            }
        }
    }

    private fun recordAssetOrder(
        assetId: String,
        order: Long,
    ) {
        submissionLock.withLock {
            if ((assetOrders[assetId] ?: 0L) < order) assetOrders[assetId] = order
        }
    }

    private fun hasNewerBarrierLocked(
        key: SubtitleSelectionKey,
        order: Long,
    ): Boolean =
        globalBarrierOrder > order ||
            (serverBarrierOrders[key.serverId] ?: 0L) > order ||
            (accountBarrierOrders[AccountIdentity(key.serverId, key.userId)] ?: 0L) > order ||
            (keyBarrierOrders[key] ?: 0L) > order

    private fun <T> submitImmediate(
        reserve: (Long) -> Unit = {},
        block: suspend (MutationRequest<T>, Long) -> T,
    ): MutationRequest<T> =
        submissionLock.withLock {
            val order = nextLogicalOrder()
            reserve(order)
            enqueueLocked(MutationRequest(order, block))
        }

    private fun <T> submitBarrier(
        scope: BarrierScope,
        block: suspend (MutationRequest<T>, Long) -> T,
    ): MutationRequest<T> =
        submissionLock.withLock {
            val order = nextLogicalOrder()
            when (scope) {
                BarrierScope.Global -> globalBarrierOrder = order
                is BarrierScope.Account -> accountBarrierOrders[scope.identity] = order
                is BarrierScope.Server -> serverBarrierOrders[scope.serverId] = order
            }
            enqueueLocked(MutationRequest(order, block))
        }

    private fun <T> enqueueDelayed(
        order: Long,
        block: suspend (MutationRequest<T>, Long) -> T,
    ): MutationRequest<T> =
        submissionLock.withLock {
            enqueueLocked(MutationRequest(order, block))
        }

    private fun <T> enqueueLocked(request: MutationRequest<T>): MutationRequest<T> {
        if (requests.trySend(request).isFailure) {
            request.terminate(CancellationException(WRITER_UNAVAILABLE))
        }
        return request
    }

    private fun nextLogicalOrder(): Long {
        check(nextOrder < Long.MAX_VALUE) { "Local subtitle mutation order exhausted." }
        nextOrder += 1
        return nextOrder
    }

    private suspend fun <T> submitAndAwait(request: MutationRequest<T>): T =
        try {
            request.completion.await()
        } catch (cancellation: CancellationException) {
            request.cancelAndAwait(cancellation)
            throw cancellation
        } catch (throwable: Throwable) {
            throw throwable
        }

    private fun requireAssetStore(): LocalSubtitleAssetStore =
        checkNotNull(assetStore) { "Local subtitle asset persistence is unavailable." }

    private fun requireFileStore(): LocalSubtitleFileStore = checkNotNull(fileStore) { "Local subtitle file persistence is unavailable." }

    private sealed interface BarrierScope {
        data object Global : BarrierScope

        data class Account(
            val identity: AccountIdentity,
        ) : BarrierScope

        data class Server(
            val serverId: String,
        ) : BarrierScope
    }

    private data class AssetCleanupTarget(
        val asset: LocalSubtitleAsset,
        val key: SubtitleSelectionKey,
    )

    private data class ReconciliationCleanupSnapshot(
        val missingAssets: List<AssetCleanupTarget>,
        val orphanFileIds: Set<String>,
    )

    internal class MutationRequest<T>(
        val order: Long,
        private val operation: suspend (MutationRequest<T>, Long) -> T,
    ) {
        val completion = CompletableDeferred<T>()
        private val settled = CompletableDeferred<Unit>()
        private val state = atomic<MutationState>(MutationState.Queued)
        private var compensation: (suspend () -> Unit)? = null
        private var compensationFailureTarget: Throwable? = null
        private var commitAction: (() -> Unit)? = null

        fun compensateWith(block: suspend () -> Unit) {
            compensation = block
        }

        fun afterCommit(block: () -> Unit) {
            check(commitAction == null) { "Local subtitle mutation already has a commit action." }
            commitAction = block
        }

        fun ensureMutationActive() {
            (state.value as? MutationState.CancellationRequested)?.let { cancellation ->
                throw cancellation.cause
            }
        }

        suspend fun run(beforeSuccessCompletion: suspend () -> Unit = {}) {
            if (!state.compareAndSet(MutationState.Queued, MutationState.Started)) {
                settled.complete(Unit)
                return
            }
            try {
                val value = operation(this, order)
                ensureMutationActive()
                beforeSuccessCompletion()
                if (!state.compareAndSet(MutationState.Started, MutationState.Completed)) {
                    throw cancellationCause() ?: CancellationException(MUTATION_CANCELLED)
                }
                commitAction?.invoke()
                check(completion.complete(value)) { "Local subtitle mutation result was already settled." }
            } catch (throwable: Throwable) {
                val original = claimFailure(throwable)
                compensationFailureTarget = original
                withContext(NonCancellable) {
                    try {
                        compensation?.invoke()
                    } catch (cleanupFailure: Throwable) {
                        original.addSuppressedDistinct(cleanupFailure)
                    }
                }
                finishFailure(original)
                if (throwable is CancellationException && !currentCoroutineContext().isActive) {
                    throw throwable
                }
            } finally {
                settled.complete(Unit)
            }
        }

        fun terminate(cause: CancellationException) {
            requestCancellation(cause)
        }

        suspend fun cancelAndAwait(cause: CancellationException) {
            requestCancellation(cause)
            withContext(NonCancellable) { settled.await() }
        }

        fun recordCleanupFailure(throwable: Throwable) {
            compensationFailureTarget?.let { original ->
                original.addSuppressedDistinct(throwable)
            }
        }

        private fun requestCancellation(cause: CancellationException): Boolean {
            while (true) {
                when (val current = state.value) {
                    MutationState.Queued -> {
                        if (state.compareAndSet(current, MutationState.Cancelled)) {
                            completion.cancel(cause)
                            settled.complete(Unit)
                            return true
                        }
                    }
                    MutationState.Started -> {
                        if (state.compareAndSet(current, MutationState.CancellationRequested(cause))) {
                            return true
                        }
                    }
                    is MutationState.CancellationRequested,
                    MutationState.Cancelled,
                    -> return true
                    MutationState.Completed,
                    MutationState.Failed,
                    -> return false
                }
            }
        }

        private fun cancellationCause(): CancellationException? = (state.value as? MutationState.CancellationRequested)?.cause

        private fun claimFailure(throwable: Throwable): Throwable {
            while (true) {
                when (val current = state.value) {
                    is MutationState.CancellationRequested -> return current.cause
                    MutationState.Started -> {
                        val failureState =
                            if (throwable is CancellationException) {
                                MutationState.CancellationRequested(throwable)
                            } else {
                                MutationState.Failed
                            }
                        if (state.compareAndSet(current, failureState)) return throwable
                    }
                    MutationState.Cancelled -> return throwable
                    MutationState.Completed,
                    MutationState.Failed,
                    MutationState.Queued,
                    -> return throwable
                }
            }
        }

        private fun finishFailure(throwable: Throwable) {
            if (throwable is CancellationException) {
                state.value = MutationState.Cancelled
                completion.cancel(throwable)
            } else {
                state.value = MutationState.Failed
                completion.completeExceptionally(throwable)
            }
        }
    }

    private sealed interface MutationState {
        data object Queued : MutationState

        data object Started : MutationState

        data class CancellationRequested(
            val cause: CancellationException,
        ) : MutationState

        data object Cancelled : MutationState

        data object Completed : MutationState

        data object Failed : MutationState
    }
}

internal class CoordinatedSubtitleSelectionStore(
    private val coordinator: LocalSubtitleMutationCoordinator,
) : SubtitleSelectionStore {
    override suspend fun get(key: SubtitleSelectionKey): SubtitleSelectionIntent? = coordinator.currentSelection(key)

    override suspend fun getForItems(
        serverId: String,
        userId: String,
        itemIds: List<String>,
    ): Map<Pair<String, String>, SubtitleSelectionIntent> = coordinator.selectionsForItems(serverId, userId, itemIds)

    override suspend fun save(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    ) {
        coordinator.submitSelection(key, selection).awaitMutationCompletion()
    }

    override suspend fun delete(key: SubtitleSelectionKey) {
        coordinator.submitSelection(key, SubtitleSelectionIntent.Unspecified).awaitMutationCompletion()
    }

    override suspend fun clearLocalAssetSelections() = coordinator.clearLocalAssetSelectionsOnly()

    override suspend fun clearAccount(accountIdentity: AccountIdentity) = coordinator.clearNonLocalSelectionsForAccount(accountIdentity)

    override suspend fun clearNonLocalAccountSelections(
        serverId: String,
        userId: String,
    ) = coordinator.clearNonLocalSelectionsForAccount(AccountIdentity(serverId, userId))

    override suspend fun clearServerScoped(serverId: String) = coordinator.clearNonLocalSelectionsForServer(serverId)

    override suspend fun clearServerScoped() = coordinator.clearAllNonLocalSelections()
}

internal data class LocalSubtitleInstallReservation(
    val key: SubtitleSelectionKey,
    val order: Long,
)

internal data class LocalSubtitleInstallResult(
    val asset: LocalSubtitleAsset,
    val selectionApplied: Boolean,
)

internal data class LocalSubtitleSyncLease(
    val asset: LocalSubtitleAsset,
    val bytes: ByteArray,
    val order: Long,
)

internal class LocalSubtitleMutationSupersededException :
    IllegalStateException("Local subtitle mutation was superseded by newer state.")

private suspend fun SubtitleSelectionStore.persist(
    key: SubtitleSelectionKey,
    selection: SubtitleSelectionIntent,
) {
    if (selection == SubtitleSelectionIntent.Unspecified) {
        delete(key)
    } else {
        save(key, selection)
    }
}

private fun LocalSubtitleAsset.sameGeneration(other: LocalSubtitleAsset): Boolean =
    id == other.id && createdAtEpochMs == other.createdAtEpochMs

private fun LocalSubtitleAsset.context(): LocalSubtitleContext = LocalSubtitleContext(serverId, userId, itemId, mediaSourceId)

private fun LocalSubtitleContext.selectionKey(): SubtitleSelectionKey = SubtitleSelectionKey(serverId, userId, itemId, mediaSourceId)

private fun SubtitleSelectionKey.context(): LocalSubtitleContext = LocalSubtitleContext(serverId, userId, itemId, mediaSourceId)

private suspend fun attemptCleanup(
    request: LocalSubtitleMutationCoordinator.MutationRequest<*>,
    cleanup: suspend () -> Unit,
) {
    try {
        cleanup()
    } catch (throwable: Throwable) {
        request.recordCleanupFailure(throwable)
    }
}

private suspend fun Deferred<Unit>.awaitMutationCompletion() {
    try {
        await()
    } catch (cancellation: CancellationException) {
        cancel(cancellation)
        withContext(NonCancellable) { join() }
        throw cancellation
    }
}

private suspend fun attemptAll(
    failures: MutableList<Throwable>,
    operation: suspend () -> Unit,
) {
    try {
        operation()
    } catch (throwable: Throwable) {
        failures += throwable
    }
}

private fun List<Throwable>.throwFirstWithSuppressed() {
    val first = firstOrNull() ?: return
    drop(1).forEach(first::addSuppressedDistinct)
    throw first
}

private fun Throwable.addSuppressedDistinct(throwable: Throwable) {
    if (throwable !== this && suppressedExceptions.none { suppressed -> suppressed === throwable }) {
        addSuppressed(throwable)
    }
}

private const val WRITER_UNAVAILABLE = "Local subtitle mutation owner is unavailable."
private const val MUTATION_CANCELLED = "Local subtitle mutation was cancelled before completion."
