// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.local.SubtitleSelectionStore
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.LocalSubtitleSyncState
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LocalSubtitleMutationCoordinatorTest {
    @Test
    fun queuedSelectionBeforeDeleteCannotLeaveADanglingSelection() =
        runTest {
            val events = mutableListOf<String>()
            val asset = localAsset()
            val assets = RecordingAssetStore(events, asset)
            val files = RecordingFileStore(events, asset.fileId to WEB_VTT_BYTES)
            val selections = RecordingSelectionStore(events)
            val saveStarted = CompletableDeferred<Unit>()
            val releaseSave = CompletableDeferred<Unit>()
            selections.afterSave = {
                saveStarted.complete(Unit)
                releaseSave.await()
            }
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)

            val selectionWrite = coordinator.submitSelection(asset.key(), SubtitleSelectionIntent.LocalAsset(asset.id))
            saveStarted.await()
            val deletion = async { coordinator.deleteAsset(asset.id) }
            runCurrent()

            assertFalse(deletion.isCompleted)
            releaseSave.complete(Unit)
            advanceUntilIdle()
            selectionWrite.await()
            deletion.await()

            assertNull(selections.get(asset.key()))
            assertNull(assets.get(asset.id))
            assertFalse(files.exists(asset.fileId))
            assertEquals(
                listOf("selection.save", "selection.delete", "asset.delete", "file.delete"),
                events.filter { it in MUTATION_EVENTS },
            )
        }

    @Test
    fun newerSelectionDuringDelayedInstallKeepsAssetWithoutAutoSelectingIt() =
        runTest {
            val events = mutableListOf<String>()
            val assets = RecordingAssetStore(events)
            val files = RecordingFileStore(events)
            val selections = RecordingSelectionStore(events)
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)
            val candidate = localAsset()
            val reservation = coordinator.reserveInstall(candidate.context())

            val newerSelection = coordinator.submitSelection(candidate.key(), SubtitleSelectionIntent.Track(4))
            advanceUntilIdle()
            newerSelection.await()
            val installed = coordinator.commitInstall(reservation, candidate, WEB_VTT_BYTES)

            assertFalse(installed.selectionApplied)
            assertEquals(candidate, assets.get(candidate.id))
            assertTrue(files.exists(candidate.fileId))
            assertEquals(SubtitleSelectionIntent.Track(4), selections.get(candidate.key()))
        }

    @Test
    fun clearBarrierSupersedesDelayedInstallBeforeAnyInstallWrite() =
        runTest {
            val events = mutableListOf<String>()
            val assets = RecordingAssetStore(events)
            val files = RecordingFileStore(events)
            val selections = RecordingSelectionStore(events)
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)
            val candidate = localAsset()
            val reservation = coordinator.reserveInstall(candidate.context())

            coordinator.clearAllLocalSubtitles()
            val afterClear = events.toList()

            assertFailsWith<LocalSubtitleMutationSupersededException> {
                coordinator.commitInstall(reservation, candidate, WEB_VTT_BYTES)
            }
            assertEquals(afterClear, events)
            assertNull(assets.get(candidate.id))
            assertFalse(files.exists(candidate.fileId))
            assertNull(selections.get(candidate.key()))
        }

    @Test
    fun reconciliationBarrierSupersedesDelayedInstallBeforeAnyInstallWrite() =
        runTest {
            val events = mutableListOf<String>()
            val assets = RecordingAssetStore(events)
            val files = RecordingFileStore(events)
            val selections = RecordingSelectionStore(events)
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)
            val candidate = localAsset()
            val reservation = coordinator.reserveInstall(candidate.context())

            coordinator.reconcileStorage()
            val afterReconciliation = events.toList()

            assertFailsWith<LocalSubtitleMutationSupersededException> {
                coordinator.commitInstall(reservation, candidate, WEB_VTT_BYTES)
            }
            assertEquals(afterReconciliation, events)
            assertNull(assets.get(candidate.id))
            assertFalse(files.exists(candidate.fileId))
        }

    @Test
    fun accountBarrierSupersedesDelayedInstallAndOldSyncWithoutDeletingRetainedLocalRows() =
        runTest {
            val events = mutableListOf<String>()
            val retained = localAsset(id = "retained")
            val assets = RecordingAssetStore(events, retained)
            val files = RecordingFileStore(events, retained.fileId to WEB_VTT_BYTES)
            val selections = RecordingSelectionStore(events)
            selections.save(retained.key(), SubtitleSelectionIntent.LocalAsset(retained.id))
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)
            val candidate = localAsset(id = "candidate")
            val reservation = coordinator.reserveInstall(candidate.context())
            val lease = coordinator.beginSync(retained) ?: error("Expected a sync lease")
            val beforeBarrier = events.toList()

            coordinator.advanceAccountBarrier(AccountIdentity("server", "user"))

            val applied =
                coordinator.applySyncUpdate(
                    lease = lease,
                    updated = retained.copy(syncState = LocalSubtitleSyncState.Confirmed(7)),
                    terminal = true,
                )

            assertFailsWith<LocalSubtitleMutationSupersededException> {
                coordinator.commitInstall(reservation, candidate, WEB_VTT_BYTES)
            }
            assertFalse(applied)
            assertEquals(beforeBarrier, events)
            assertEquals(retained, assets.get(retained.id))
            assertTrue(files.exists(retained.fileId))
            assertEquals(SubtitleSelectionIntent.LocalAsset(retained.id), selections.get(retained.key()))
            assertNull(assets.get(candidate.id))
        }

    @Test
    fun duplicateMetadataWithMissingFileRecreatesFileAndSelectsExistingAsset() =
        runTest {
            val events = mutableListOf<String>()
            val existing = localAsset(id = "existing", fileId = "existing.vtt")
            val assets = RecordingAssetStore(events, existing)
            val files = RecordingFileStore(events)
            val selections = RecordingSelectionStore(events)
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)
            val candidate = localAsset(id = "candidate", fileId = "candidate.vtt", createdAtEpochMs = 2)
            val reservation = coordinator.reserveInstall(candidate.context())

            val installed = coordinator.commitInstall(reservation, candidate, WEB_VTT_BYTES)

            assertEquals(existing, installed.asset)
            assertTrue(installed.selectionApplied)
            assertTrue(files.exists(existing.fileId))
            assertFalse(files.exists(candidate.fileId))
            assertEquals(SubtitleSelectionIntent.LocalAsset(existing.id), selections.get(existing.key()))
            assertEquals(0, assets.upsertCalls)
        }

    @Test
    fun cancellationBeforeDequeueSkipsTheQueuedMutation() =
        runTest {
            val events = mutableListOf<String>()
            val assets = RecordingAssetStore(events)
            val files = RecordingFileStore(events)
            val selections = RecordingSelectionStore(events)
            val firstReadStarted = CompletableDeferred<Unit>()
            val releaseFirstRead = CompletableDeferred<Unit>()
            var blockFirstRead = true
            selections.beforeGet = {
                if (blockFirstRead) {
                    blockFirstRead = false
                    firstReadStarted.complete(Unit)
                    releaseFirstRead.await()
                }
            }
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)
            val blocker = async { coordinator.currentSelection(testKey()) }
            firstReadStarted.await()
            val queued = coordinator.submitSelection(testKey(), SubtitleSelectionIntent.Track(3))

            queued.cancel()
            releaseFirstRead.complete(Unit)
            advanceUntilIdle()
            blocker.await()

            assertTrue(queued.isCancelled)
            assertNull(selections.get(testKey()))
            assertEquals(0, selections.saveCalls)
        }

    @Test
    fun cancellingStartedSelectionDeferredWaitsForSelectionRestoration() =
        runTest {
            val events = mutableListOf<String>()
            val assets = RecordingAssetStore(events)
            val files = RecordingFileStore(events)
            val selections = RecordingSelectionStore(events)
            selections.save(testKey(), SubtitleSelectionIntent.Track(2))
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            val restoreStarted = CompletableDeferred<Unit>()
            val releaseRestore = CompletableDeferred<Unit>()
            var saveStage = 0
            selections.afterSave = {
                saveStage += 1
                if (saveStage == 1) {
                    writeStarted.complete(Unit)
                    releaseWrite.await()
                } else {
                    restoreStarted.complete(Unit)
                    releaseRestore.await()
                }
            }
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)
            val write = coordinator.submitSelection(testKey(), SubtitleSelectionIntent.Track(3))

            writeStarted.await()
            write.cancel(CancellationException("selection cancelled"))
            assertTrue(write.isCancelled)
            assertFalse(write.isActive)
            assertFalse(write.isCompleted)

            releaseWrite.complete(Unit)
            restoreStarted.await()
            assertFalse(write.isCompleted)

            releaseRestore.complete(Unit)
            advanceUntilIdle()

            assertTrue(write.isCancelled)
            assertEquals(SubtitleSelectionIntent.Track(2), selections.get(testKey()))
        }

    @Test
    fun cancellingCoordinatedStoreCallerWaitsForSelectionRestoration() =
        runTest {
            val events = mutableListOf<String>()
            val selections = RecordingSelectionStore(events)
            selections.save(testKey(), SubtitleSelectionIntent.Track(2))
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            val restoreStarted = CompletableDeferred<Unit>()
            val releaseRestore = CompletableDeferred<Unit>()
            var saveStage = 0
            selections.afterSave = {
                saveStage += 1
                if (saveStage == 1) {
                    writeStarted.complete(Unit)
                    releaseWrite.await()
                } else {
                    restoreStarted.complete(Unit)
                    releaseRestore.await()
                }
            }
            val coordinator = LocalSubtitleMutationCoordinator(null, null, selections, backgroundScope)
            val store = CoordinatedSubtitleSelectionStore(coordinator)
            val caller = async { store.save(testKey(), SubtitleSelectionIntent.Track(3)) }

            writeStarted.await()
            caller.cancel()
            runCurrent()
            assertFalse(caller.isCompleted)

            releaseWrite.complete(Unit)
            restoreStarted.await()
            assertFalse(caller.isCompleted)

            releaseRestore.complete(Unit)
            caller.join()

            assertTrue(caller.isCancelled)
            assertEquals(SubtitleSelectionIntent.Track(2), selections.get(testKey()))
        }

    @Test
    fun cancellationAfterEachInstallStageWaitsForFullCompensation() =
        runTest {
            InstallPauseStage.entries.forEach { stage ->
                assertCancelledInstallCompensates(stage)
            }
        }

    @Test
    fun compensationFailureIsSuppressedWithoutReplacingTheOriginalFailure() =
        runTest {
            val events = mutableListOf<String>()
            val assets = RecordingAssetStore(events)
            val cleanupFailure = IllegalStateException("cleanup failed")
            val files = RecordingFileStore(events).apply { afterDelete = { throw cleanupFailure } }
            val originalFailure = IllegalArgumentException("selection failed")
            val selections = RecordingSelectionStore(events).apply { afterSave = { throw originalFailure } }
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)
            val candidate = localAsset()

            val thrown =
                assertFailsWith<IllegalArgumentException> {
                    coordinator.commitInstall(
                        coordinator.reserveInstall(candidate.context()),
                        candidate,
                        WEB_VTT_BYTES,
                    )
                }

            val preservedOriginal = thrown.cause ?: thrown
            assertSame(originalFailure, preservedOriginal)
            assertEquals(listOf(cleanupFailure), preservedOriginal.suppressedExceptions)
            assertNull(selections.get(candidate.key()))
            assertNull(assets.get(candidate.id))
            assertFalse(files.exists(candidate.fileId))
        }

    @Test
    fun selectionReadFailureDuringInstallCompensationDoesNotSkipAssetOrFileCleanup() =
        runTest {
            val events = mutableListOf<String>()
            val assets = RecordingAssetStore(events)
            val files = RecordingFileStore(events)
            val originalFailure = IllegalArgumentException("selection write failed")
            val readFailure = IllegalStateException("selection cleanup read failed")
            var getCalls = 0
            val selections =
                RecordingSelectionStore(events).apply {
                    beforeGet = {
                        getCalls += 1
                        if (getCalls == 2) throw readFailure
                    }
                    afterSave = { throw originalFailure }
                }
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)
            val candidate = localAsset()

            val thrown =
                assertFailsWith<IllegalArgumentException> {
                    coordinator.commitInstall(
                        coordinator.reserveInstall(candidate.context()),
                        candidate,
                        WEB_VTT_BYTES,
                    )
                }

            assertSame(originalFailure, thrown.cause ?: thrown)
            assertEquals(listOf(readFailure), (thrown.cause ?: thrown).suppressedExceptions)
            assertNull(assets.get(candidate.id))
            assertFalse(files.exists(candidate.fileId))
            assertTrue(events.contains("asset.delete"))
            assertTrue(events.contains("file.delete"))
        }

    @Test
    fun selectionRestoreFailureDuringInstallCompensationDoesNotSkipAssetOrFileCleanup() =
        runTest {
            val events = mutableListOf<String>()
            val assets = RecordingAssetStore(events)
            val files = RecordingFileStore(events)
            val selections = RecordingSelectionStore(events)
            selections.save(testKey(), SubtitleSelectionIntent.Track(2))
            val originalFailure = IllegalArgumentException("selection write failed")
            val restoreFailure = IllegalStateException("selection restore failed")
            var saveCalls = 0
            selections.afterSave = {
                saveCalls += 1
                if (saveCalls == 1) throw originalFailure
                if (saveCalls == 2) throw restoreFailure
            }
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)
            val candidate = localAsset()

            val thrown =
                assertFailsWith<IllegalArgumentException> {
                    coordinator.commitInstall(
                        coordinator.reserveInstall(candidate.context()),
                        candidate,
                        WEB_VTT_BYTES,
                    )
                }

            assertSame(originalFailure, thrown.cause ?: thrown)
            assertEquals(listOf(restoreFailure), (thrown.cause ?: thrown).suppressedExceptions)
            assertEquals(SubtitleSelectionIntent.Track(2), selections.get(candidate.key()))
            assertNull(assets.get(candidate.id))
            assertFalse(files.exists(candidate.fileId))
            assertTrue(events.contains("asset.delete"))
            assertTrue(events.contains("file.delete"))
        }

    @Test
    fun missingAssetRepairAttemptsEveryPartAndSurfacesAggregatedFailures() =
        runTest {
            val events = mutableListOf<String>()
            val asset = localAsset()
            val selectionFailure = IllegalStateException("selection delete failed")
            val assetFailure = IllegalArgumentException("asset delete failed")
            val fileFailure = UnsupportedOperationException("file delete failed")
            val assets =
                RecordingAssetStore(events, asset).apply {
                    beforeDelete = { throw assetFailure }
                }
            val files =
                RecordingFileStore(events).apply {
                    beforeDelete = { throw fileFailure }
                }
            val selections =
                RecordingSelectionStore(events).apply {
                    save(asset.key(), SubtitleSelectionIntent.LocalAsset(asset.id))
                    beforeDelete = { throw selectionFailure }
                }
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)

            val thrown =
                assertFailsWith<IllegalStateException> {
                    coordinator.validAsset(asset.id, asset.context())
                }

            assertSame(selectionFailure, thrown.cause ?: thrown)
            assertEquals(
                listOf(assetFailure, fileFailure),
                (thrown.cause ?: thrown).suppressedExceptions,
            )
            assertEquals(2, selections.deleteCalls)
            assertEquals(2, assets.deleteCalls)
            assertEquals(2, files.deleteCalls(asset.fileId))
            assertEquals(asset, assets.get(asset.id))
            assertEquals(SubtitleSelectionIntent.LocalAsset(asset.id), selections.get(asset.key()))
        }

    @Test
    fun reconciliationRetriesItsOriginalSnapshotAndAggregatesAcrossTargets() =
        runTest {
            val events = mutableListOf<String>()
            val asset = localAsset()
            val orphanFileId = "orphan.vtt"
            val selectionFailure = IllegalStateException("selection delete failed")
            val orphanFailure = IllegalArgumentException("orphan delete failed")
            val assets = RecordingAssetStore(events, asset)
            val files = RecordingFileStore(events, orphanFileId to WEB_VTT_BYTES)
            val selections = RecordingSelectionStore(events)
            selections.save(asset.key(), SubtitleSelectionIntent.LocalAsset(asset.id))
            var failSelection = true
            selections.beforeDelete = {
                if (failSelection) {
                    failSelection = false
                    throw selectionFailure
                }
            }
            var failOrphan = true
            files.beforeDelete = { fileId ->
                if (fileId == orphanFileId && failOrphan) {
                    failOrphan = false
                    throw orphanFailure
                }
            }
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)

            val thrown = assertFailsWith<IllegalStateException> { coordinator.reconcileStorage() }

            assertSame(selectionFailure, thrown.cause ?: thrown)
            assertEquals(listOf(orphanFailure), (thrown.cause ?: thrown).suppressedExceptions)
            assertEquals(1, assets.allCalls)
            assertEquals(1, files.listFileIdsCalls)
            assertEquals(2, selections.deleteCalls)
            assertNull(selections.get(asset.key()))
            assertNull(assets.get(asset.id))
            assertFalse(files.exists(orphanFileId))
            assertEquals(2, files.deleteCalls(orphanFileId))
        }

    @Test
    fun cancellationAfterReconciliationSnapshotRetriesEveryCapturedTargetBeforeReturning() =
        runTest {
            val events = mutableListOf<String>()
            val first = localAsset(id = "first", itemId = "item-1")
            val second =
                localAsset(
                    id = "second",
                    itemId = "item-2",
                    providerFileId = "provider-file-2",
                )
            val assets = RecordingAssetStore(events, first, second)
            val files = RecordingFileStore(events)
            val selections = RecordingSelectionStore(events)
            selections.save(first.key(), SubtitleSelectionIntent.LocalAsset(first.id))
            selections.save(second.key(), SubtitleSelectionIntent.LocalAsset(second.id))
            val firstDeleteStarted = CompletableDeferred<Unit>()
            val releaseFirstDelete = CompletableDeferred<Unit>()
            var pauseOnce = true
            selections.afterDelete = {
                if (pauseOnce) {
                    pauseOnce = false
                    firstDeleteStarted.complete(Unit)
                    releaseFirstDelete.await()
                }
            }
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)
            val reconciliation = async { coordinator.reconcileStorage() }

            firstDeleteStarted.await()
            reconciliation.cancel()
            runCurrent()
            assertFalse(reconciliation.isCompleted)

            releaseFirstDelete.complete(Unit)
            runCurrent()
            reconciliation.join()

            assertTrue(reconciliation.isCancelled)
            assertTrue(reconciliation.isCompleted)
            assertEquals(1, assets.allCalls)
            assertEquals(1, files.listFileIdsCalls)
            assertNull(selections.get(first.key()))
            assertNull(selections.get(second.key()))
            assertNull(assets.get(first.id))
            assertNull(assets.get(second.id))
        }

    @Test
    fun staleSyncResultCannotReplaceADeletedAndReinstalledGeneration() =
        runTest {
            val events = mutableListOf<String>()
            val original = localAsset(createdAtEpochMs = 1)
            val assets = RecordingAssetStore(events, original)
            val files = RecordingFileStore(events, original.fileId to WEB_VTT_BYTES)
            val selections = RecordingSelectionStore(events)
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)
            val lease = coordinator.beginSync(original) ?: error("Expected a sync lease")

            coordinator.deleteAsset(original.id)
            val replacement = original.copy(createdAtEpochMs = 2, lastUsedAtEpochMs = 2)
            val reservation = coordinator.reserveInstall(replacement.context())
            coordinator.commitInstall(reservation, replacement, WEB_VTT_BYTES)

            val applied =
                coordinator.applySyncUpdate(
                    lease = lease,
                    updated = original.copy(syncState = LocalSubtitleSyncState.Confirmed(99)),
                    terminal = true,
                )

            assertFalse(applied)
            assertEquals(replacement, assets.get(original.id))
        }

    @Test
    fun onlyOneSyncClaimMayOwnAnAssetGenerationAtATime() =
        runTest {
            val events = mutableListOf<String>()
            val asset = localAsset()
            val assets = RecordingAssetStore(events, asset)
            val files = RecordingFileStore(events, asset.fileId to WEB_VTT_BYTES)
            val selections = RecordingSelectionStore(events)
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)

            val first = coordinator.beginSync(asset) ?: error("Expected the first sync claim")
            assertNull(coordinator.beginSync(asset))

            coordinator.releaseSync(first)

            assertTrue(coordinator.beginSync(asset) != null)
        }

    @Test
    fun cancellationDuringSyncReadReleasesClaimBeforeCallerReturns() =
        runTest {
            val events = mutableListOf<String>()
            val asset = localAsset()
            val assets = RecordingAssetStore(events, asset)
            val readStarted = CompletableDeferred<Unit>()
            val releaseRead = CompletableDeferred<Unit>()
            val files =
                RecordingFileStore(events, asset.fileId to WEB_VTT_BYTES).apply {
                    beforeRead = {
                        readStarted.complete(Unit)
                        releaseRead.await()
                    }
                }
            val selections = RecordingSelectionStore(events)
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)
            val cancelled = async { coordinator.beginSync(asset) }

            readStarted.await()
            cancelled.cancel()
            runCurrent()
            assertFalse(cancelled.isCompleted)

            releaseRead.complete(Unit)
            advanceUntilIdle()
            assertTrue(cancelled.isCancelled)

            files.beforeRead = {}
            val retry = coordinator.beginSync(asset)
            assertTrue(retry != null)
            coordinator.releaseSync(retry)
        }

    @Test
    fun cancellationBeforeSyncLeaseDeliveryReleasesCommittedClaimBeforeCallerReturns() =
        runTest {
            val events = mutableListOf<String>()
            val asset = localAsset()
            val assets = RecordingAssetStore(events, asset)
            val files = RecordingFileStore(events, asset.fileId to WEB_VTT_BYTES)
            val selections = RecordingSelectionStore(events)
            val actorDispatcher = StandardTestDispatcher(testScheduler)
            val ownerJob = SupervisorJob()
            val coordinator =
                LocalSubtitleMutationCoordinator(
                    assetStore = assets,
                    fileStore = files,
                    selectionStore = selections,
                    scope = CoroutineScope(ownerJob + actorDispatcher),
                )
            val callerDispatcher = QueuedTestDispatcher()
            val firstCaller = async(callerDispatcher) { coordinator.beginSync(asset) }

            callerDispatcher.runNext()
            runCurrent()
            assertFalse(firstCaller.isCompleted)
            assertNull(coordinator.beginSync(asset))

            firstCaller.cancel()
            callerDispatcher.runAll()
            assertFalse(firstCaller.isCompleted)

            runCurrent()
            callerDispatcher.runAll()
            assertTrue(firstCaller.isCancelled)

            val second = coordinator.beginSync(asset)
            assertTrue(second != null)
            coordinator.releaseSync(second)
            ownerJob.cancel()
        }

    @Test
    fun cancellationAfterDurableSyncUpsertWaitsForPriorRowRestorationAndReleasesLease() =
        runTest {
            val events = mutableListOf<String>()
            val asset = localAsset()
            val assets = RecordingAssetStore(events, asset)
            val files = RecordingFileStore(events, asset.fileId to WEB_VTT_BYTES)
            val selections = RecordingSelectionStore(events)
            val updateCommitted = CompletableDeferred<Unit>()
            val releaseUpdate = CompletableDeferred<Unit>()
            val restoreCommitted = CompletableDeferred<Unit>()
            val releaseRestore = CompletableDeferred<Unit>()
            val restoreFailure = IllegalStateException("restore reported failure after durable commit")
            var upsertStage = 0
            assets.afterUpsert = {
                upsertStage += 1
                if (upsertStage == 1) {
                    updateCommitted.complete(Unit)
                    releaseUpdate.await()
                } else {
                    restoreCommitted.complete(Unit)
                    releaseRestore.await()
                    throw restoreFailure
                }
            }
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)
            val lease = coordinator.beginSync(asset) ?: error("Expected a sync lease")
            val uploading = asset.copy(syncState = LocalSubtitleSyncState.Uploading)
            val update = async { coordinator.applySyncUpdate(lease, uploading) }

            updateCommitted.await()
            assertEquals(uploading, assets.get(asset.id))
            update.cancel(CancellationException("sync update cancelled"))
            runCurrent()
            assertFalse(update.isCompleted)

            releaseUpdate.complete(Unit)
            restoreCommitted.await()
            assertEquals(asset, assets.get(asset.id))
            assertFalse(update.isCompleted)

            releaseRestore.complete(Unit)
            update.join()

            assertTrue(update.isCancelled)
            assertEquals(asset, assets.get(asset.id))
            val retry = coordinator.beginSync(asset) ?: error("Expected the compensated lease to be reusable")
            coordinator.releaseSync(retry)
        }

    @Test
    fun olderNoOpDuplicateInstallDoesNotInvalidateNewerSyncLease() =
        runTest {
            val events = mutableListOf<String>()
            val existing = localAsset(id = "existing")
            val assets = RecordingAssetStore(events, existing)
            val files = RecordingFileStore(events, existing.fileId to WEB_VTT_BYTES)
            val selections = RecordingSelectionStore(events)
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)
            val candidate = localAsset(id = "candidate")
            val olderReservation = coordinator.reserveInstall(candidate.context())
            val newerLease = coordinator.beginSync(existing) ?: error("Expected a sync lease")

            val duplicate = coordinator.commitInstall(olderReservation, candidate, WEB_VTT_BYTES)
            assertEquals(existing, duplicate.asset)
            assertEquals(0, assets.upsertCalls)
            assertFalse(events.contains("file.write"))

            val confirmed = existing.copy(syncState = LocalSubtitleSyncState.Confirmed(9))
            val applied = coordinator.applySyncUpdate(newerLease, confirmed, terminal = true)

            assertTrue(applied)
            assertEquals(1, assets.upsertCalls)
            assertEquals(confirmed, assets.get(existing.id))
        }

    @Test
    fun successfulClearCompensationRemovesStaleSyncClaim() =
        runTest {
            val events = mutableListOf<String>()
            val original = localAsset(createdAtEpochMs = 1)
            val assets = RecordingAssetStore(events, original)
            val files = RecordingFileStore(events, original.fileId to WEB_VTT_BYTES)
            val selections = RecordingSelectionStore(events)
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)
            val oldLease = coordinator.beginSync(original) ?: error("Expected an initial sync lease")
            var failDelete = true
            files.beforeDelete = {
                if (failDelete) {
                    failDelete = false
                    throw IllegalStateException("forward clear failed")
                }
            }

            assertFailsWith<IllegalStateException> { coordinator.clearAllLocalSubtitles() }

            val replacement = original.copy(createdAtEpochMs = 2, lastUsedAtEpochMs = 2)
            coordinator.commitInstall(
                coordinator.reserveInstall(replacement.context()),
                replacement,
                WEB_VTT_BYTES,
            )
            val newLease = coordinator.beginSync(replacement)

            assertTrue(newLease != null)
            coordinator.releaseSync(newLease)
            coordinator.releaseSync(oldLease)
        }

    @Test
    fun invalidLocalAssetIntentFailsInsideActorWithoutPersistingIt() =
        runTest {
            val events = mutableListOf<String>()
            val assets = RecordingAssetStore(events)
            val files = RecordingFileStore(events)
            val selections = RecordingSelectionStore(events)
            val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)
            val write = coordinator.submitSelection(testKey(), SubtitleSelectionIntent.LocalAsset("missing"))

            advanceUntilIdle()

            assertFailsWith<IllegalArgumentException> { write.await() }
            assertNull(selections.get(testKey()))
            assertEquals(0, selections.saveCalls)
        }

    @Test
    fun ownerShutdownSettlesActiveAndUndeliveredRequests() =
        runTest {
            val ownerJob = SupervisorJob()
            val ownerScope = CoroutineScope(ownerJob + StandardTestDispatcher(testScheduler))
            val selections = BlockingSelectionStore()
            val coordinator = LocalSubtitleMutationCoordinator(null, null, selections, ownerScope)
            val active = coordinator.submitSelection(testKey(), SubtitleSelectionIntent.Track(1))
            val queued = coordinator.submitSelection(testKey(), SubtitleSelectionIntent.Track(2))
            runCurrent()

            ownerJob.cancel()
            advanceUntilIdle()

            assertTrue(active.isCancelled)
            assertTrue(queued.isCancelled)
        }

    @Test
    fun successAndCancellationUseOneDeterministicFinalizationWinner() =
        runTest {
            val successReady = CompletableDeferred<Unit>()
            val releaseSuccess = CompletableDeferred<Unit>()
            var compensationCount = 0
            val cancelledRequest =
                LocalSubtitleMutationCoordinator.MutationRequest<Unit>(1) { transaction, _ ->
                    transaction.compensateWith { compensationCount += 1 }
                }
            val runner =
                launch {
                    cancelledRequest.run {
                        successReady.complete(Unit)
                        releaseSuccess.await()
                    }
                }

            successReady.await()
            val cancellation =
                async {
                    cancelledRequest.cancelAndAwait(CancellationException("direct cancellation won"))
                }
            runCurrent()
            assertFalse(cancelledRequest.completion.isCompleted)
            assertFalse(cancellation.isCompleted)
            releaseSuccess.complete(Unit)
            runner.join()
            cancellation.await()

            assertTrue(cancelledRequest.completion.isCancelled)
            assertEquals(1, compensationCount)

            var retroactiveCompensation = false
            val successfulRequest =
                LocalSubtitleMutationCoordinator.MutationRequest<Unit>(2) { transaction, _ ->
                    transaction.compensateWith { retroactiveCompensation = true }
                }
            successfulRequest.run()
            successfulRequest.cancelAndAwait(CancellationException("too late"))

            assertFalse(successfulRequest.completion.isCancelled)
            assertEquals(Unit, successfulRequest.completion.await())
            assertFalse(retroactiveCompensation)
        }

    @Test
    fun callerCancellationWaitsForCompensationAfterOperationFailureWins() =
        runTest {
            val primaryFailure = IllegalStateException("operation failed")
            val cleanupStarted = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            var compensationCount = 0
            val request =
                LocalSubtitleMutationCoordinator.MutationRequest<Unit>(1) { transaction, _ ->
                    transaction.compensateWith {
                        compensationCount += 1
                        cleanupStarted.complete(Unit)
                        releaseCleanup.await()
                    }
                    throw primaryFailure
                }
            val runner = launch { request.run() }
            val caller =
                async {
                    try {
                        request.completion.await()
                    } catch (cancellation: CancellationException) {
                        request.cancelAndAwait(cancellation)
                        throw cancellation
                    }
                }

            cleanupStarted.await()
            caller.cancel()
            runCurrent()

            assertEquals(1, compensationCount)
            assertFalse(caller.isCompleted)
            assertFalse(request.completion.isCompleted)

            releaseCleanup.complete(Unit)
            runner.join()
            caller.join()

            assertTrue(caller.isCancelled)
            assertEquals(1, compensationCount)
            val thrown = assertFailsWith<IllegalStateException> { request.completion.await() }
            assertSame(primaryFailure, thrown.cause ?: thrown)
        }

    @Test
    fun callerCancellationAfterActorSuccessDoesNotCompensateCommittedInstall() =
        runTest {
            val events = mutableListOf<String>()
            val actorDispatcher = StandardTestDispatcher(testScheduler)
            val ownerJob = SupervisorJob()
            val coordinator =
                LocalSubtitleMutationCoordinator(
                    assetStore = RecordingAssetStore(events),
                    fileStore = RecordingFileStore(events),
                    selectionStore = RecordingSelectionStore(events),
                    scope = CoroutineScope(ownerJob + actorDispatcher),
                )
            val candidate = localAsset()
            val callerDispatcher = QueuedTestDispatcher()
            val caller =
                async(callerDispatcher) {
                    coordinator.commitInstall(
                        coordinator.reserveInstall(candidate.context()),
                        candidate,
                        WEB_VTT_BYTES,
                    )
                }

            callerDispatcher.runNext()
            runCurrent()
            caller.cancel()
            callerDispatcher.runAll()

            assertTrue(caller.isCancelled)
            assertEquals(
                listOf("file.write", "asset.upsert", "selection.save"),
                events.filter { event -> event in MUTATION_EVENTS || event == "file.write" || event == "asset.upsert" },
            )
            ownerJob.cancel()
        }

    @Test
    fun ownerCancellationBeforeWriterFirstDispatchSettlesPrequeuedAndLaterSubmissions() =
        runTest {
            val events = mutableListOf<String>()
            val ownerJob = SupervisorJob()
            val ownerScope = CoroutineScope(ownerJob + StandardTestDispatcher(testScheduler))
            val selections = RecordingSelectionStore(events)
            val coordinator = LocalSubtitleMutationCoordinator(null, null, selections, ownerScope)
            val prequeued = coordinator.submitSelection(testKey(), SubtitleSelectionIntent.Track(1))

            ownerJob.cancel()
            val later = coordinator.submitSelection(testKey(), SubtitleSelectionIntent.Track(2))
            advanceUntilIdle()

            assertTrue(prequeued.isCancelled)
            assertTrue(later.isCancelled)
            assertEquals(0, selections.saveCalls)
        }

    private suspend fun TestScope.assertCancelledInstallCompensates(stage: InstallPauseStage) {
        val events = mutableListOf<String>()
        val assets = RecordingAssetStore(events)
        val files = RecordingFileStore(events)
        val selections = RecordingSelectionStore(events)
        val stageReached = CompletableDeferred<Unit>()
        val releaseStage = CompletableDeferred<Unit>()
        val pause: suspend () -> Unit = {
            stageReached.complete(Unit)
            releaseStage.await()
        }
        when (stage) {
            InstallPauseStage.File -> files.afterWrite = pause
            InstallPauseStage.Asset -> assets.afterUpsert = { pause() }
            InstallPauseStage.Selection -> selections.afterSave = pause
        }
        val coordinator = LocalSubtitleMutationCoordinator(assets, files, selections, backgroundScope)
        val candidate = localAsset(id = "asset-${stage.name.lowercase()}")
        val install =
            async {
                coordinator.commitInstall(
                    coordinator.reserveInstall(candidate.context()),
                    candidate,
                    WEB_VTT_BYTES,
                )
            }

        stageReached.await()
        install.cancel()
        runCurrent()
        assertFalse(install.isCompleted, "Caller returned before $stage compensation could run")

        releaseStage.complete(Unit)
        runCurrent()
        advanceUntilIdle()

        assertTrue(install.isCancelled)
        assertNull(selections.get(candidate.key()))
        assertNull(assets.get(candidate.id))
        assertFalse(files.exists(candidate.fileId), "$stage left a file: $events")
    }
}

private enum class InstallPauseStage {
    File,
    Asset,
    Selection,
}

private class RecordingAssetStore(
    private val events: MutableList<String>,
    vararg initial: LocalSubtitleAsset,
) : LocalSubtitleAssetStore {
    private val values = initial.associateBy(LocalSubtitleAsset::id).toMutableMap()
    var afterUpsert: suspend (LocalSubtitleAsset) -> Unit = {}
    var beforeDelete: suspend (String) -> Unit = {}
    var upsertCalls = 0
        private set
    var deleteCalls = 0
        private set
    var allCalls = 0
        private set

    override fun observe(context: LocalSubtitleContext): Flow<List<LocalSubtitleAsset>> = flowOf(values.values.toList())

    override fun observePendingSync(): Flow<List<LocalSubtitleAsset>> = flowOf(values.values.toList())

    override suspend fun get(assetId: String): LocalSubtitleAsset? = values[assetId]

    override suspend fun findByProviderFile(
        context: LocalSubtitleContext,
        provider: String,
        providerFileId: String,
    ): LocalSubtitleAsset? =
        values.values.firstOrNull { asset ->
            asset.context() == context && asset.provider == provider && asset.providerFileId == providerFileId
        }

    override suspend fun upsert(asset: LocalSubtitleAsset) {
        events += "asset.upsert"
        upsertCalls += 1
        values[asset.id] = asset
        afterUpsert(asset)
    }

    override suspend fun delete(assetId: String) {
        events += "asset.delete"
        deleteCalls += 1
        beforeDelete(assetId)
        values.remove(assetId)
    }

    override suspend fun all(): List<LocalSubtitleAsset> {
        allCalls += 1
        return values.values.toList()
    }

    override suspend fun clearAll() {
        events += "asset.clear"
        values.clear()
    }
}

private class RecordingFileStore(
    private val events: MutableList<String>,
    vararg initial: Pair<String, ByteArray>,
) : LocalSubtitleFileStore {
    private val values = initial.toMap().toMutableMap()
    var beforeRead: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    var beforeDelete: suspend (String) -> Unit = {}
    var afterDelete: suspend () -> Unit = {}
    private val deleteCalls = mutableMapOf<String, Int>()
    var listFileIdsCalls = 0
        private set

    override suspend fun writeAtomically(
        fileId: String,
        bytes: ByteArray,
    ) {
        events += "file.write"
        values[fileId] = bytes
        afterWrite()
    }

    override suspend fun read(fileId: String): ByteArray? {
        beforeRead()
        return values[fileId]
    }

    override suspend fun exists(fileId: String): Boolean = values.containsKey(fileId)

    override suspend fun delete(fileId: String) {
        events += "file.delete"
        deleteCalls[fileId] = (deleteCalls[fileId] ?: 0) + 1
        beforeDelete(fileId)
        values.remove(fileId)
        afterDelete()
    }

    override suspend fun listFileIds(): Set<String> {
        listFileIdsCalls += 1
        return values.keys.toSet()
    }

    override fun resolvePath(fileId: String): String? = fileId.takeIf(values::containsKey)

    fun deleteCalls(fileId: String): Int = deleteCalls[fileId] ?: 0
}

private class RecordingSelectionStore(
    private val events: MutableList<String>,
) : SubtitleSelectionStore {
    private val values = mutableMapOf<SubtitleSelectionKey, SubtitleSelectionIntent>()
    var beforeGet: suspend () -> Unit = {}
    var afterSave: suspend () -> Unit = {}
    var beforeDelete: suspend (SubtitleSelectionKey) -> Unit = {}
    var afterDelete: suspend () -> Unit = {}
    var saveCalls = 0
        private set
    var deleteCalls = 0
        private set

    override suspend fun get(key: SubtitleSelectionKey): SubtitleSelectionIntent? {
        beforeGet()
        return values[key]
    }

    override suspend fun save(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    ) {
        events += "selection.save"
        saveCalls += 1
        values[key] = selection
        afterSave()
    }

    override suspend fun delete(key: SubtitleSelectionKey) {
        events += "selection.delete"
        deleteCalls += 1
        beforeDelete(key)
        values.remove(key)
        afterDelete()
    }

    override suspend fun clearLocalAssetSelections() {
        events += "selection.clearLocal"
        values.entries.removeAll { (_, selection) -> selection is SubtitleSelectionIntent.LocalAsset }
    }

    override suspend fun clearNonLocalAccountSelections(
        serverId: String,
        userId: String,
    ) {
        events += "selection.clearAccount"
        values.entries.removeAll { (key, selection) ->
            key.serverId == serverId && key.userId == userId && selection !is SubtitleSelectionIntent.LocalAsset
        }
    }

    override suspend fun clearServerScoped(serverId: String) {
        events += "selection.clearServer"
        values.entries.removeAll { (key, selection) ->
            key.serverId == serverId && selection !is SubtitleSelectionIntent.LocalAsset
        }
    }

    override suspend fun clearServerScoped() {
        events += "selection.clearAll"
        values.entries.removeAll { (_, selection) -> selection !is SubtitleSelectionIntent.LocalAsset }
    }
}

private class BlockingSelectionStore : SubtitleSelectionStore {
    override suspend fun get(key: SubtitleSelectionKey): SubtitleSelectionIntent? = null

    override suspend fun save(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    ): Unit = awaitCancellation()

    override suspend fun delete(key: SubtitleSelectionKey): Unit = awaitCancellation()

    override suspend fun clearServerScoped(serverId: String) = Unit

    override suspend fun clearServerScoped() = Unit
}

private class QueuedTestDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        tasks.addLast(block)
    }

    fun runNext() {
        tasks.removeFirst().run()
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}

private fun localAsset(
    id: String = "asset",
    fileId: String = "$id.vtt",
    createdAtEpochMs: Long = 1,
    itemId: String = "item",
    providerFileId: String = "provider-file",
): LocalSubtitleAsset =
    LocalSubtitleAsset(
        id = id,
        serverId = "server",
        userId = "user",
        itemId = itemId,
        mediaSourceId = "source",
        provider = "OpenSubtitles",
        providerSubtitleId = "subtitle",
        providerFileId = providerFileId,
        language = "en",
        label = "English",
        releaseName = "Release",
        originalFormat = "srt",
        mimeType = "text/vtt",
        fileId = fileId,
        hearingImpaired = false,
        forced = false,
        trusted = true,
        createdAtEpochMs = createdAtEpochMs,
        lastUsedAtEpochMs = createdAtEpochMs,
        syncState = LocalSubtitleSyncState.Pending,
    )

private fun LocalSubtitleAsset.context(): LocalSubtitleContext = LocalSubtitleContext(serverId, userId, itemId, mediaSourceId)

private fun LocalSubtitleAsset.key(): SubtitleSelectionKey = SubtitleSelectionKey(serverId, userId, itemId, mediaSourceId)

private fun testKey(): SubtitleSelectionKey = SubtitleSelectionKey("server", "user", "item", "source")

private val MUTATION_EVENTS =
    setOf(
        "selection.save",
        "selection.delete",
        "asset.delete",
        "file.delete",
    )

private val WEB_VTT_BYTES = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n".encodeToByteArray()
