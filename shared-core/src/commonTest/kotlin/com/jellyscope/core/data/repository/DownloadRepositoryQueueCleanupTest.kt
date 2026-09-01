// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.AccountWorkLease
import com.jellyscope.core.data.local.DOWNLOAD_ORIGINAL_PART_KEY
import com.jellyscope.core.data.local.DOWNLOAD_ORIGINAL_SIDECAR_PART_KEY
import com.jellyscope.core.data.local.DownloadArtifactArea
import com.jellyscope.core.data.local.DownloadArtifactCapacity
import com.jellyscope.core.data.local.DownloadArtifactCheckpoint
import com.jellyscope.core.data.local.DownloadArtifactInspection
import com.jellyscope.core.data.local.DownloadArtifactPartCheckpoint
import com.jellyscope.core.data.local.DownloadArtifactPartInspection
import com.jellyscope.core.data.local.DownloadArtifactPartKey
import com.jellyscope.core.data.local.DownloadArtifactStore
import com.jellyscope.core.data.local.DownloadArtifactWriteMode
import com.jellyscope.core.data.local.DownloadArtifactWriter
import com.jellyscope.core.data.local.DownloadAttemptInvalidationResult
import com.jellyscope.core.data.local.DownloadRecordStore
import com.jellyscope.core.data.local.DownloadRemovalStore
import com.jellyscope.core.data.local.DownloadSettingsStore
import com.jellyscope.core.data.local.GateHeldBoundaryCommit
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.repository.DownloadActiveAttemptRegistration
import com.jellyscope.core.domain.action.SessionRemovalAuthorization
import com.jellyscope.core.domain.action.SessionRemovalError
import com.jellyscope.core.domain.action.SessionRemovalScope
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.BeginDownloadRemovalResult
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadBusinessKey
import com.jellyscope.core.domain.model.DownloadCommandResult
import com.jellyscope.core.domain.model.DownloadDeletionResult
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.DownloadFailure
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadPlatformWorkIdentity
import com.jellyscope.core.domain.model.DownloadPlatformWorkKind
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadRemovalConfirmation
import com.jellyscope.core.domain.model.DownloadRemovalKind
import com.jellyscope.core.domain.model.DownloadRemovalOperation
import com.jellyscope.core.domain.model.DownloadRemovalOperationId
import com.jellyscope.core.domain.model.DownloadRemovalPreview
import com.jellyscope.core.domain.model.DownloadRemovalTarget
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.DownloadReservationExtensionResult
import com.jellyscope.core.domain.model.DownloadSettings
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.OfflineMediaSnapshot
import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import com.jellyscope.core.domain.usecase.GetOfflinePlaybackPlanUseCase
import com.jellyscope.core.download.DownloadActiveRecoveryAction
import com.jellyscope.core.download.DownloadAttemptIdentity
import com.jellyscope.core.download.DownloadCleanupCoordinator
import com.jellyscope.core.download.DownloadExecutionHost
import com.jellyscope.core.download.DownloadExecutionRecovery
import com.jellyscope.core.download.DownloadExecutionWork
import com.jellyscope.core.download.DownloadLifecycleRequeueOutcome
import com.jellyscope.core.download.DownloadQueueCoordinator
import com.jellyscope.core.download.DownloadRecoveryPlan
import com.jellyscope.core.download.applyDownloadRecoveryPlan
import com.jellyscope.core.download.decideDownloadRecovery
import com.jellyscope.core.playback.OfflineArtifactLeaseIdentity
import com.jellyscope.core.playback.OfflineArtifactLeaseRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadRepositoryQueueCleanupTest {
    private val account = AccountIdentity("server", "user")

    @Test
    fun usageReportsCurrentAccountAndOneOpaqueOtherAccountsAggregate() =
        runTest {
            val otherAccount = AccountIdentity("server", "other-user")
            val records =
                FakeRecordStore(
                    mutableListOf(
                        record(
                            downloadId = "download_current_a",
                            state = DownloadState.Completed,
                            generation = 1L,
                            physicalBytes = 100L,
                        ),
                        record(
                            downloadId = "download_current_b",
                            state = DownloadState.Completed,
                            generation = 1L,
                            physicalBytes = 200L,
                        ),
                        record(
                            downloadId = "download_other",
                            accountIdentity = otherAccount,
                            state = DownloadState.Completed,
                            generation = 1L,
                            physicalBytes = 400L,
                        ),
                    ),
                )

            val usage = repository(records, FakeArtifactStore()).getDownloadUsage(account)

            assertEquals(700L, usage.physicalBytes)
            assertEquals(300L, usage.currentAccountPhysicalBytes)
            assertEquals(400L, usage.otherAccountsPhysicalBytes)
            assertEquals(0L, usage.outstandingReservationBytes)
        }

    @Test
    fun staleLeaseRejectsAtomicBoundaryCommitAndLateGenerationUpdate() =
        runTest {
            val records = FakeRecordStore(mutableListOf(record(state = DownloadState.Downloading, generation = 1L)))
            val artifactStore = FakeArtifactStore()
            val repository = repository(records, artifactStore)
            val lease = AccountWorkLease(account, boundaryEpoch = 4L, generation = 7L)
            var writerClosed = 0
            val registration =
                DownloadActiveAttemptRegistration(
                    accountIdentity = account,
                    lease = lease,
                    attempt = DownloadAttemptIdentity(DownloadId("download_a"), 1L),
                    initialFacts =
                        com.jellyscope.core.data.repository
                            .DownloadCheckpointFacts(100L, 80L),
                    checkpointAndCloseWriter = { _ ->
                        writerClosed += 1
                        com.jellyscope.core.data.repository
                            .DownloadCheckpointFacts(120L, 110L)
                    },
                )

            val staleGate = Gate(false)
            val stale = repository.checkpointAndRequeueForBoundary(account, registration, staleGate)
            assertIs<DownloadAttemptInvalidationResult.StaleAttempt>(stale)
            assertTrue(staleGate.checkedLease === lease)
            assertEquals(1, writerClosed)
            assertEquals(0, records.invalidationCalls)

            // A late callback using the revoked generation remains update-only.
            assertFalse(repository.updateAttemptProgress(DownloadId("download_a"), 0L, 120L, 110L))

            val validRegistration =
                DownloadActiveAttemptRegistration(
                    accountIdentity = account,
                    lease = lease,
                    attempt = DownloadAttemptIdentity(DownloadId("download_a"), 1L),
                    initialFacts =
                        com.jellyscope.core.data.repository
                            .DownloadCheckpointFacts(100L, 80L),
                    checkpointAndCloseWriter = { _ ->
                        com.jellyscope.core.data.repository
                            .DownloadCheckpointFacts(120L, 110L)
                    },
                )
            val validGate = Gate(true)
            val invalidated = repository.checkpointAndRequeueForBoundary(account, validRegistration, validGate)
            assertIs<DownloadAttemptInvalidationResult.Invalidated>(invalidated)
            assertTrue(validGate.checkedLease === lease)
            assertEquals(1, records.invalidationCalls)
        }

    @Test
    fun durableFinalizingReconcilesWithoutLiveRegistration() =
        runTest {
            val records = FakeRecordStore(mutableListOf(record(state = DownloadState.Finalizing, generation = 3L)))
            val artifacts = FakeArtifactStore(completedBytes = 100L)
            val repository = repository(records, artifacts)
            val coordinator = DownloadQueueCoordinator(repository)

            val result = coordinator.checkpointAndRequeueForBoundary(account, Gate(true))

            assertIs<DownloadAttemptInvalidationResult.Finalizing>(result)
            assertEquals(DownloadState.Completed, records.records.single().state)
        }

    @Test
    fun activePauseAndCancelCloseWriterBeforeInvalidationAndDeletion() =
        runTest {
            val records = FakeRecordStore(mutableListOf(record(state = DownloadState.Downloading, generation = 1L)))
            val repository = repository(records, FakeArtifactStore())
            val coordinator = DownloadQueueCoordinator(repository)
            val lease = AccountWorkLease(account, boundaryEpoch = 4L, generation = 7L)
            var writerClosed = 0
            val registration =
                DownloadActiveAttemptRegistration(
                    accountIdentity = account,
                    lease = lease,
                    attempt = DownloadAttemptIdentity(DownloadId("download_a"), 1L),
                    initialFacts = DownloadCheckpointFacts(100L, 100L),
                    checkpointAndCloseWriter = { _ ->
                        writerClosed += 1
                        DownloadCheckpointFacts(120L, 110L)
                    },
                )

            assertTrue(coordinator.registerActiveAttempt(registration))
            assertEquals(DownloadCommandResult.Applied, coordinator.pause(account, DownloadId("download_a")))
            assertEquals(1, writerClosed)
            assertEquals(2L, records.records.single().attemptGeneration)
            assertEquals(DownloadState.Paused, records.records.single().state)
            assertFalse(repository.updateAttemptProgress(DownloadId("download_a"), 1L, 130L, 120L))

            records.records[0] = records.records.single().copy(state = DownloadState.Downloading)
            val cancelRegistration =
                DownloadActiveAttemptRegistration(
                    accountIdentity = account,
                    lease = lease,
                    attempt = DownloadAttemptIdentity(DownloadId("download_a"), 2L),
                    initialFacts = DownloadCheckpointFacts(120L, 110L),
                    checkpointAndCloseWriter = { _ ->
                        writerClosed += 1
                        DownloadCheckpointFacts(140L, 130L)
                    },
                )
            assertTrue(coordinator.registerActiveAttempt(cancelRegistration))
            assertEquals(DownloadDeletionResult.Deleted, coordinator.cancel(account, DownloadId("download_a")))
            assertEquals(2, writerClosed)
            assertTrue(records.records.isEmpty())
            assertFalse(repository.updateAttemptProgress(DownloadId("download_a"), 2L, 140L, 130L))
        }

    @Test
    fun lifecycleRequeueReturnsActiveAttemptToFifoForTheNextWake() =
        runTest {
            val records =
                mutableListOf(
                    record(state = DownloadState.Downloading, generation = 1L),
                    record(downloadId = "download_next", state = DownloadState.Queued, generation = 0L)
                        .copy(fifoSequence = 2L),
                )
            val queue = FakeQueueRepository(records)
            val coordinator = DownloadQueueCoordinator(queue)
            val attempt = DownloadAttemptIdentity(DownloadId("download_a"), 1L)
            val registration =
                DownloadActiveAttemptRegistration(
                    accountIdentity = account,
                    lease = AccountWorkLease(account, boundaryEpoch = 1L, generation = 1L),
                    attempt = attempt,
                    initialFacts = DownloadCheckpointFacts(100L, 100L),
                    checkpointAndCloseWriter = { _ -> DownloadCheckpointFacts(120L, 120L) },
                )

            assertTrue(coordinator.registerActiveAttempt(registration))

            assertEquals(
                DownloadLifecycleRequeueOutcome.Requeued,
                coordinator.checkpointAndRequeueForLifecycle(account, attempt),
            )
            assertEquals(DownloadState.Queued, records.first().state)
            assertEquals(2L, records.first().attemptGeneration)
            assertEquals(DownloadId("download_a"), coordinator.claimNext(account)?.downloadId)
        }

    @Test
    fun noneRemovalRequiresConfirmationWithoutMutatingSagaAndStaleTokenIsRejected() =
        runTest {
            val queue = FakeQueueRepository(mutableListOf(record(state = DownloadState.Completed, generation = 0L)))
            val removalStore = FakeRemovalStore(queue)
            val coordinator = cleanup(queue, removalStore)

            val none =
                coordinator.prepareRemoval(
                    scope = SessionRemovalScope.Account(account),
                    authorization = SessionRemovalAuthorization.None,
                    gateHeldBoundaryCommit = Gate(true),
                )
            assertIs<SessionRemovalError.DownloadRemovalConfirmationRequired>(none.exceptionOrNull())
            assertEquals(0, removalStore.beginCalls)

            val emptyQueue = FakeQueueRepository(mutableListOf())
            val emptyRemovalStore = FakeRemovalStore(emptyQueue)
            val emptyCoordinator = cleanup(emptyQueue, emptyRemovalStore)
            val noDownloads =
                emptyCoordinator.prepareRemoval(
                    scope = SessionRemovalScope.Account(account),
                    authorization = SessionRemovalAuthorization.None,
                    gateHeldBoundaryCommit = Gate(true),
                )
            assertTrue(noDownloads.isSuccess)
            assertEquals(null, noDownloads.getOrNull())
            assertEquals(0, emptyRemovalStore.beginCalls)

            val scope = SessionRemovalScope.Account(account)
            val preview = coordinator.previewRemoval(scope)
            val token = coordinator.issueRemovalAuthorization(scope, preview)
            removalStore.membershipRevision += 1L
            val stale =
                coordinator.prepareRemoval(
                    scope = SessionRemovalScope.Account(account),
                    authorization = token,
                    gateHeldBoundaryCommit = Gate(true),
                )
            assertIs<SessionRemovalError.ConfirmationStale>(stale.exceptionOrNull())
            assertEquals(0, removalStore.beginCalls)
        }

    @Test
    fun artifactInUseIsRejectedBeforeSagaInsertion() =
        runTest {
            val record = record(state = DownloadState.Completed, generation = 0L)
            val queue = FakeQueueRepository(mutableListOf(record))
            val removalStore = FakeRemovalStore(queue)
            val artifacts = FakeArtifactStore()
            val leaseRegistry = OfflineArtifactLeaseRegistry()
            val lease =
                assertNotNull(
                    leaseRegistry.acquire(
                        OfflineArtifactLeaseIdentity(record.downloadId, record.attemptGeneration),
                        DownloadArtifactKind.OriginalFile,
                        com.jellyscope.core.playback.TrustedOfflineArtifactResource(
                            DownloadArtifactPartKey.from("main.bin"),
                            "/private/main.bin",
                        ),
                    ),
                )
            val coordinator = cleanup(queue, removalStore, artifacts, leaseRegistry)
            val scope = SessionRemovalScope.Account(account)
            val preview = coordinator.previewRemoval(scope)
            val authorization = coordinator.issueRemovalAuthorization(scope, preview)

            val result =
                coordinator.prepareRemoval(
                    SessionRemovalScope.Account(account),
                    authorization,
                    Gate(true),
                )
            assertIs<SessionRemovalError.ArtifactInUse>(result.exceptionOrNull())
            assertEquals(0, removalStore.beginCalls)
            assertTrue(artifacts.deleted.isEmpty())
            lease.release()
        }

    @Test
    fun previewQuiescesActiveWriterAndDismissalReleasesFifoGuard() =
        runTest {
            val queue = FakeQueueRepository(mutableListOf(record(state = DownloadState.Downloading, generation = 4L)))
            val removalStore = FakeRemovalStore(queue)
            val queueCoordinator = DownloadQueueCoordinator(queue)
            var writerClosed = 0
            val registration =
                DownloadActiveAttemptRegistration(
                    accountIdentity = account,
                    lease = AccountWorkLease(account, boundaryEpoch = 1L, generation = 1L),
                    attempt = DownloadAttemptIdentity(DownloadId("download_a"), 4L),
                    initialFacts = DownloadCheckpointFacts(100L, 100L),
                    checkpointAndCloseWriter = { _ ->
                        writerClosed += 1
                        DownloadCheckpointFacts(140L, 120L)
                    },
                )
            assertTrue(queueCoordinator.registerActiveAttempt(registration))
            val coordinator = cleanup(queue, removalStore, queueCoordinator = queueCoordinator)
            val scope = SessionRemovalScope.Account(account)
            var releaseWakeCalls = 0
            queueCoordinator.registerRemovalPreviewReleaseWake {
                releaseWakeCalls += 1
                Result.success(Unit)
            }

            val preview = coordinator.previewRemoval(scope)

            assertEquals(1, writerClosed)
            assertEquals(140L, preview.confirmations.single().displayedBytes)
            assertEquals(140L, queue.records.single().physicalBytes)
            assertEquals(120L, queue.records.single().checkpointBytes)
            assertEquals(DownloadState.Queued, queue.records.single().state)
            assertNull(queueCoordinator.claimNext(account))
            assertFalse(queueCoordinator.hasClaimEligibleWork(account))
            assertEquals(0, releaseWakeCalls)

            assertTrue(coordinator.releaseRemovalPreview(scope, preview))
            assertEquals(1, releaseWakeCalls)
            assertTrue(queueCoordinator.hasClaimEligibleWork(account))
            assertEquals(DownloadId("download_a"), queueCoordinator.claimNext(account)?.downloadId)
        }

    @Test
    fun dismissalUsesTheRecoveredHostWakeAfterReleasingThePreviewGuard() =
        runTest {
            val queue = FakeQueueRepository(mutableListOf(record(state = DownloadState.Queued, generation = 0L)))
            val removalStore = FakeRemovalStore(queue)
            val queueCoordinator = DownloadQueueCoordinator(queue)
            val host = FakeExecutionHost(mutableListOf())
            val recovery = DownloadExecutionRecovery(queueCoordinator, FakeRecoveryDriver())
            assertTrue(recovery.recoverBeforeFirstWake(host).isSuccess)

            val coordinator = cleanup(queue, removalStore, queueCoordinator = queueCoordinator)
            val scope = SessionRemovalScope.Account(account)
            val preview = coordinator.previewRemoval(scope)

            assertTrue(coordinator.releaseRemovalPreview(scope, preview))
            assertEquals(1, host.userActionWakeCalls)
            assertEquals(DownloadId("download_a"), queueCoordinator.claimNext(account)?.downloadId)
        }

    @Test
    fun cancellationDuringDismissalWakeLeavesPreviewGuardsReleasedForFifoClaim() =
        runTest {
            val queue = FakeQueueRepository(mutableListOf(record(state = DownloadState.Queued, generation = 0L)))
            val removalStore = FakeRemovalStore(queue)
            val queueCoordinator = DownloadQueueCoordinator(queue)
            val wakeStarted = CompletableDeferred<Unit>()
            val wakeGate = CompletableDeferred<Unit>()
            queueCoordinator.registerRemovalPreviewReleaseWake {
                wakeStarted.complete(Unit)
                wakeGate.await()
                Result.success(Unit)
            }
            val coordinator = cleanup(queue, removalStore, queueCoordinator = queueCoordinator)
            val scope = SessionRemovalScope.Account(account)
            val preview = coordinator.previewRemoval(scope)

            val releaseJob = launch { coordinator.releaseRemovalPreview(scope, preview) }
            runCurrent()
            wakeStarted.await()

            // Cancellation now lands in the best-effort wake, after the logical guard release.
            releaseJob.cancel()
            releaseJob.join()

            assertTrue(queueCoordinator.hasClaimEligibleWork(account))
            assertEquals(DownloadId("download_a"), queueCoordinator.claimNext(account)?.downloadId)
        }

    @Test
    fun confirmationUsesDisplayedPreviewAndRejectsPendingEnqueueRace() =
        runTest {
            val queue = FakeQueueRepository(mutableListOf(record(state = DownloadState.Completed, generation = 0L)))
            val removalStore = FakeRemovalStore(queue)
            val coordinator = cleanup(queue, removalStore)
            val scope = SessionRemovalScope.Account(account)
            val preview = coordinator.previewRemoval(scope)
            val authorization = coordinator.issueRemovalAuthorization(scope, preview)
            queue.records += record(downloadId = "download_b", state = DownloadState.Queued, generation = 0L)
            removalStore.membershipRevision += 1L

            val result = coordinator.prepareRemoval(scope, authorization, Gate(true))

            assertIs<SessionRemovalError.ConfirmationStale>(result.exceptionOrNull())
            assertEquals(0, removalStore.beginCalls)
            assertEquals(DownloadState.Queued, queue.records.last().state)
        }

    @Test
    fun emptyPreviewRaceAlsoRequiresFreshConfirmation() =
        runTest {
            val queue = FakeQueueRepository(mutableListOf())
            val removalStore = FakeRemovalStore(queue)
            val coordinator = cleanup(queue, removalStore)
            val scope = SessionRemovalScope.Account(account)
            val preview = coordinator.previewRemoval(scope)
            val authorization = coordinator.issueRemovalAuthorization(scope, preview)
            queue.records += record(downloadId = "download_late", state = DownloadState.Queued, generation = 0L)
            removalStore.membershipRevision += 1L

            val result = coordinator.prepareRemoval(scope, authorization, Gate(true))

            assertIs<SessionRemovalError.ConfirmationStale>(result.exceptionOrNull())
            assertEquals(0, removalStore.beginCalls)
        }

    @Test
    fun offlinePlaybackPreflightRejectsSameTotalBytesWithWrongOriginalSidecarSplit() =
        runTest {
            val records =
                FakeRecordStore(
                    mutableListOf(
                        record(
                            state = DownloadState.Completed,
                            generation = 0L,
                            expectedSourceBytes = 80L,
                            subtitleSelection = DownloadSubtitleSelection.ExternalTextSidecar("subtitle_a"),
                        ),
                    ),
                )
            val artifacts =
                FakeArtifactStore(
                    completedParts =
                        listOf(
                            DownloadArtifactPartInspection(DOWNLOAD_ORIGINAL_PART_KEY, 70L),
                            DownloadArtifactPartInspection(DOWNLOAD_ORIGINAL_SIDECAR_PART_KEY, 30L),
                        ),
                )
            val repository = repository(records, artifacts)

            assertNull(
                GetOfflinePlaybackPlanUseCase(repository)(
                    accountIdentity = account,
                    downloadId = DownloadId("download_a"),
                ),
            )
        }

    @Test
    fun replaySettlesIndependentCrashHalvesAndIsIdempotent() =
        runTest {
            val keyA = DownloadArtifactKey("artifact_a")
            val keyB = DownloadArtifactKey("artifact_b")
            val accountB = AccountIdentity("server", "other")
            val queue =
                FakeQueueRepository(
                    mutableListOf(
                        record("download_a", account, keyA, DownloadState.Completed, 0L),
                        record("download_b", accountB, keyB, DownloadState.Completed, 0L),
                    ),
                )
            val removalStore = FakeRemovalStore(queue)
            val operation =
                DownloadRemovalOperation(
                    operationId = DownloadRemovalOperationId("operation_1"),
                    kind = DownloadRemovalKind.FullLogout,
                    createdAtEpochMs = 1L,
                    targets =
                        listOf(
                            DownloadRemovalTarget(
                                operationId = DownloadRemovalOperationId("operation_1"),
                                accountIdentity = account,
                                observedMembershipRevision = 0L,
                                cleanupGeneration = 1L,
                                remainingArtifactKeys = listOf(keyA),
                                accountRemoved = true,
                                downloadsRemoved = false,
                            ),
                            DownloadRemovalTarget(
                                operationId = DownloadRemovalOperationId("operation_1"),
                                accountIdentity = accountB,
                                observedMembershipRevision = 0L,
                                cleanupGeneration = 1L,
                                remainingArtifactKeys = emptyList(),
                                accountRemoved = false,
                                downloadsRemoved = true,
                            ),
                        ),
                )
            removalStore.pendingOperations += operation
            val artifacts = FakeArtifactStore()
            val executor = FakeExecutor(setOf(accountB))
            val coordinator = cleanup(queue, removalStore, artifacts)

            assertTrue(coordinator.resumeIncompleteRemovalOperations(executor, Gate(true)).isSuccess)
            assertEquals(1, executor.removeCalls)
            assertTrue(artifacts.deleted.isNotEmpty())
            assertTrue(removalStore.pendingOperations.isEmpty())

            // A second replay observes no header and cannot call the executor again.
            assertTrue(coordinator.resumeIncompleteRemovalOperations(executor, Gate(true)).isSuccess)
            assertEquals(1, executor.removeCalls)
        }

    @Test
    fun recoveryCancelsDuplicatesBeforeReassociateAndReconcilesFinalizing() {
        val active =
            record(state = DownloadState.Downloading, generation = 1L).copy(
                platformWorkIdentity = DownloadPlatformWorkIdentity(DownloadPlatformWorkKind.AndroidWorkManager, "current"),
            )
        val currentWork =
            DownloadExecutionWork(
                attempt = DownloadAttemptIdentity(active.downloadId, active.attemptGeneration),
                platformWorkIdentity = requireNotNull(active.platformWorkIdentity),
            )
        val duplicateWork =
            DownloadExecutionWork(
                attempt = DownloadAttemptIdentity(active.downloadId, active.attemptGeneration),
                platformWorkIdentity = DownloadPlatformWorkIdentity(DownloadPlatformWorkKind.AndroidWorkManager, "duplicate"),
            )
        val reassociatePlan = decideDownloadRecovery(listOf(active), listOf(duplicateWork, currentWork))
        assertEquals(listOf(duplicateWork.platformWorkIdentity), reassociatePlan.cancelBeforeClaim)
        val reassociate = assertIs<DownloadActiveRecoveryAction.Reassociate>(reassociatePlan.activeAction)
        assertEquals(currentWork, reassociate.work)

        val finalizing = record(state = DownloadState.Finalizing, generation = 2L)
        val finalizingPlan = decideDownloadRecovery(listOf(finalizing), listOf(duplicateWork))
        assertEquals(listOf(duplicateWork.platformWorkIdentity), finalizingPlan.cancelBeforeClaim)
        assertIs<DownloadActiveRecoveryAction.ReconcileFinalizing>(finalizingPlan.activeAction)
    }

    @Test
    fun recoveryApplicationCancelsBeforeReassociateAndAction() =
        runTest {
            val stale = DownloadPlatformWorkIdentity(DownloadPlatformWorkKind.AndroidWorkManager, "stale")
            val current = DownloadPlatformWorkIdentity(DownloadPlatformWorkKind.AndroidWorkManager, "current")
            val work =
                DownloadExecutionWork(
                    attempt = DownloadAttemptIdentity(DownloadId("download_a"), 1L),
                    platformWorkIdentity = current,
                )
            val events = mutableListOf<String>()
            val host = FakeExecutionHost(events)
            val plan =
                DownloadRecoveryPlan(
                    cancelBeforeClaim = listOf(stale),
                    activeAction = DownloadActiveRecoveryAction.Reassociate(work),
                )

            val result =
                applyDownloadRecoveryPlan(host, plan) {
                    if (it is DownloadActiveRecoveryAction.Reassociate) {
                        host.reassociate(it.work).getOrThrow()
                    }
                    events += "action"
                    Result.success(Unit)
                }

            assertTrue(result.isSuccess)
            assertEquals(listOf("cancel:stale", "reassociate:current", "action"), events)
        }

    @Test
    fun recoveryCancelsNativeIdentityWithNoDurableAttemptBeforeAnyClaim() {
        val staleIdentity =
            DownloadPlatformWorkIdentity(
                DownloadPlatformWorkKind.AndroidUserInitiatedJob,
                "orphan-job",
            )
        val plan =
            decideDownloadRecovery(
                records = emptyList(),
                discoveredWork =
                    listOf(
                        DownloadExecutionWork(
                            attempt = null,
                            platformWorkIdentity = staleIdentity,
                        ),
                    ),
            )

        assertEquals(listOf(staleIdentity), plan.cancelBeforeClaim)
        assertEquals(DownloadActiveRecoveryAction.None, plan.activeAction)
    }

    @Test
    fun recoveryRetainsOnePendingWakeForAQueuedHeadWithoutAClaimedAttempt() {
        val pendingIdentity =
            DownloadPlatformWorkIdentity(
                DownloadPlatformWorkKind.AndroidUserInitiatedJob,
                "pending-job",
            )
        val plan =
            decideDownloadRecovery(
                records = listOf(record(state = DownloadState.Queued, generation = 0L)),
                discoveredWork =
                    listOf(
                        DownloadExecutionWork(
                            attempt = null,
                            platformWorkIdentity = pendingIdentity,
                        ),
                    ),
            )

        assertTrue(plan.cancelBeforeClaim.isEmpty())
        assertEquals(DownloadActiveRecoveryAction.None, plan.activeAction)
    }

    private fun cleanup(
        queue: FakeQueueRepository,
        removalStore: FakeRemovalStore,
        artifacts: FakeArtifactStore = FakeArtifactStore(),
        registry: OfflineArtifactLeaseRegistry = OfflineArtifactLeaseRegistry(),
        queueCoordinator: DownloadQueueCoordinator = DownloadQueueCoordinator(queue),
    ): DownloadCleanupCoordinator =
        DownloadCleanupCoordinator(
            removalStore = removalStore,
            queueRepository = queue,
            queueCoordinator = queueCoordinator,
            artifactStore = artifacts,
            artifactLeaseRegistry = registry,
            removalMutex = DownloadRemovalMutex(),
            serverScopedStoreRegistry = ServerScopedStoreRegistry(),
            nowEpochMilliseconds = { 10L },
            operationIdProvider = { DownloadRemovalOperationId("operation_new") },
        )

    private fun repository(
        records: FakeRecordStore,
        artifacts: FakeArtifactStore,
    ): DefaultDownloadRepository =
        DefaultDownloadRepository(
            settingsStore = FakeSettingsStore(),
            recordStore = records,
            removalStore = FakeRemovalStore(FakeQueueRepository(mutableListOf())),
            artifactStore = artifacts,
            artifactLeaseRegistry = OfflineArtifactLeaseRegistry(),
            removalMutex = DownloadRemovalMutex(),
            nowEpochMilliseconds = { 10L },
        )

    private fun record(
        downloadId: String = "download_a",
        accountIdentity: AccountIdentity = account,
        artifactKey: DownloadArtifactKey = DownloadArtifactKey("artifact_a"),
        state: DownloadState,
        generation: Long,
        expectedSourceBytes: Long = 100L,
        subtitleSelection: DownloadSubtitleSelection = DownloadSubtitleSelection.Off,
        physicalBytes: Long = 100L,
    ): DownloadRecord =
        DownloadRecord(
            request =
                DownloadRequest(
                    downloadId = DownloadId(downloadId),
                    businessKey = DownloadBusinessKey(accountIdentity, "item_$downloadId", "source_$downloadId"),
                    quality = DownloadQuality.Original,
                    artifactKind = DownloadArtifactKind.OriginalFile,
                    selectedAudioStreamIndex = null,
                    subtitleSelection = subtitleSelection,
                    admissionEstimateBytes = 100L,
                    initialReservationBytes = 200L,
                    expectedSourceBytes = expectedSourceBytes,
                    artifactKey = artifactKey,
                    snapshot =
                        OfflineMediaSnapshot(
                            title = "Title $downloadId",
                            itemKind = MediaKind.Movie,
                            backendSource = BackendSourceDescriptor("mkv", "h264", "aac", false),
                        ),
                    createdAtEpochMs = 1L,
                ),
            fifoSequence = 1L,
            state = state,
            reservationBytes = if (state == DownloadState.Completed) physicalBytes else 200L,
            physicalBytes = physicalBytes,
            checkpointBytes = physicalBytes,
            attemptGeneration = generation,
            localResumePositionMs = 0L,
            updatedAtEpochMs = 2L,
        )
}

private class Gate(
    private val current: Boolean,
) : GateHeldBoundaryCommit {
    var checkedLease: AccountWorkLease? = null

    override fun isCurrentLease(lease: AccountWorkLease): Boolean {
        checkedLease = lease
        return current
    }
}

private class FakeSettingsStore : DownloadSettingsStore {
    private var settings = DownloadSettings(null, 1L, 0L)

    override suspend fun get(): DownloadSettings = settings

    override suspend fun setQuotaBytes(quotaBytes: Long?): DownloadSettings {
        settings = settings.copy(quotaBytes = quotaBytes)
        return settings
    }
}

internal class FakeArtifactStore(
    private val completedBytes: Long? = null,
    private val completedParts: List<DownloadArtifactPartInspection>? = null,
) : DownloadArtifactStore {
    val deleted = mutableListOf<String>()

    override suspend fun openStagingWriter(
        artifactKey: DownloadArtifactKey,
        partKey: DownloadArtifactPartKey,
        mode: DownloadArtifactWriteMode,
    ): DownloadArtifactWriter = error("unused")

    override suspend fun inspect(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
    ): DownloadArtifactInspection? =
        (
            completedParts ?: completedBytes?.let { bytes ->
                listOf(DownloadArtifactPartInspection(DOWNLOAD_ORIGINAL_PART_KEY, bytes))
            }
        )?.takeIf { area == DownloadArtifactArea.Completed }
            ?.let { parts ->
                DownloadArtifactInspection(
                    artifactKey = artifactKey,
                    area = area,
                    parts = parts,
                )
            }

    override suspend fun completedPartPath(
        artifactKey: DownloadArtifactKey,
        partKey: DownloadArtifactPartKey,
    ): String? =
        (
            completedParts ?: completedBytes?.let { bytes ->
                listOf(DownloadArtifactPartInspection(DOWNLOAD_ORIGINAL_PART_KEY, bytes))
            }
        )?.firstOrNull { part -> part.partKey == partKey && part.lengthBytes > 0L }
            ?.let { "/trusted/${artifactKey.value}/${partKey.value}" }

    override suspend fun readPart(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
        partKey: DownloadArtifactPartKey,
        maxBytes: Int,
    ): ByteArray? = null

    override suspend fun replaceStagingMetadata(
        artifactKey: DownloadArtifactKey,
        partKey: DownloadArtifactPartKey,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): DownloadArtifactPartCheckpoint = error("unused")

    override suspend fun validateStagingCheckpoint(
        artifactKey: DownloadArtifactKey,
        checkpoint: DownloadArtifactCheckpoint,
    ): Boolean = false

    override suspend fun normalizeStagingCheckpoint(
        artifactKey: DownloadArtifactKey,
        checkpoint: DownloadArtifactCheckpoint,
    ): Boolean = true

    override suspend fun promote(artifactKey: DownloadArtifactKey): DownloadArtifactInspection = error("unused")

    override suspend fun delete(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
    ) {
        deleted +=
            "${artifactKey.value}:${area.name}"
    }

    override suspend fun enumerate(area: DownloadArtifactArea): List<DownloadArtifactInspection> = emptyList()

    override suspend fun capacity(): DownloadArtifactCapacity = DownloadArtifactCapacity(Long.MAX_VALUE, Long.MAX_VALUE)
}

internal class FakeRecordStore(
    initial: MutableList<DownloadRecord>,
) : DownloadRecordStore {
    val records = initial
    var invalidationCalls = 0

    override fun observeAccount(accountIdentity: AccountIdentity): Flow<List<DownloadRecord>> =
        flowOf(
            records.filter {
                it.businessKey.accountIdentity ==
                    accountIdentity
            },
        )

    override suspend fun all(): List<DownloadRecord> = records.toList()

    override suspend fun get(downloadId: DownloadId): DownloadRecord? = records.firstOrNull { it.downloadId == downloadId }

    override suspend fun get(
        downloadId: DownloadId,
        attemptGeneration: Long,
    ): DownloadRecord? =
        records.firstOrNull {
            it.downloadId ==
                downloadId &&
                it.attemptGeneration == attemptGeneration
        }

    override suspend fun enqueue(
        request: DownloadRequest,
        deviceAvailableBytes: Long,
    ): DownloadEnqueueResult = error("unused")

    override suspend fun claimOldest(
        accountIdentity: AccountIdentity,
        platformWorkIdentity: DownloadPlatformWorkIdentity?,
        deviceAvailableBytes: Long,
        updatedAtEpochMs: Long,
    ): DownloadRecord? = null

    override suspend fun updateProgress(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        physicalBytes: Long,
        checkpointBytes: Long,
        updatedAtEpochMs: Long,
    ): Boolean {
        val current = get(downloadId, expectedAttemptGeneration) ?: return false
        if (checkpointBytes !in 0L..physicalBytes || physicalBytes < current.physicalBytes) return false
        records[records.indexOf(current)] =
            current.copy(
                physicalBytes = physicalBytes,
                checkpointBytes = checkpointBytes,
                updatedAtEpochMs = updatedAtEpochMs,
            )
        return true
    }

    override suspend fun updateOriginalSourceFacts(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        expectedSourceBytes: Long,
        sourceValidator: String,
    ): Boolean = get(downloadId, expectedAttemptGeneration) != null

    override suspend fun checkpointAndInvalidateAttempt(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        physicalBytes: Long,
        checkpointBytes: Long,
        nextState: DownloadState,
        updatedAtEpochMs: Long,
    ): DownloadAttemptInvalidationResult {
        val current = get(downloadId, expectedAttemptGeneration) ?: return DownloadAttemptInvalidationResult.StaleAttempt
        if (current.businessKey.accountIdentity != accountIdentity) return DownloadAttemptInvalidationResult.StaleAttempt
        invalidationCalls += 1
        val next =
            current.copy(
                state = nextState,
                attemptGeneration = expectedAttemptGeneration + 1L,
                physicalBytes = physicalBytes,
                checkpointBytes = checkpointBytes,
                reservationBytes = maxOf(current.reservationBytes, physicalBytes),
                updatedAtEpochMs = updatedAtEpochMs,
            )
        records[records.indexOf(current)] = next
        return DownloadAttemptInvalidationResult.Invalidated(next)
    }

    override suspend fun extendReservation(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        requiredReservationBytes: Long,
        deviceAvailableBytes: Long,
    ): DownloadReservationExtensionResult = DownloadReservationExtensionResult.StaleAttempt

    override suspend fun transition(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        nextState: DownloadState,
        platformWorkIdentity: DownloadPlatformWorkIdentity?,
        failure: DownloadFailure?,
        updatedAtEpochMs: Long,
    ): Boolean = false

    override suspend fun completeFinalizing(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        updatedAtEpochMs: Long,
    ): Boolean {
        val current = get(downloadId, expectedAttemptGeneration) ?: return false
        if (current.state != DownloadState.Finalizing) return false
        records[records.indexOf(current)] =
            current.copy(
                state = DownloadState.Completed,
                reservationBytes = current.physicalBytes,
                updatedAtEpochMs = updatedAtEpochMs,
            )
        return true
    }

    override suspend fun updateLocalPlayback(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        resumePositionMs: Long,
        watched: Boolean?,
        updatedAtEpochMs: Long,
    ): Boolean = false

    override suspend fun delete(downloadId: DownloadId): Boolean = records.removeAll { it.downloadId == downloadId }
}

internal class FakeQueueRepository(
    val records: MutableList<DownloadRecord>,
) : DownloadQueueRepository {
    override suspend fun allDownloads(): List<DownloadRecord> = records.toList()

    override suspend fun pause(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult = DownloadCommandResult.InvalidState

    override suspend fun cancel(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadDeletionResult = DownloadDeletionResult.InvalidState

    override suspend fun claimOldest(
        activeAccount: AccountIdentity,
        platformWorkIdentity: DownloadPlatformWorkIdentity?,
    ): DownloadRecord? {
        val current =
            records
                .filter { record -> record.businessKey.accountIdentity == activeAccount && record.state == DownloadState.Queued }
                .minByOrNull { record -> record.fifoSequence }
                ?: return null
        val next = current.copy(state = DownloadState.Downloading, platformWorkIdentity = platformWorkIdentity)
        records[records.indexOf(current)] = next
        return next
    }

    override suspend fun updateAttemptProgress(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        physicalBytes: Long,
        checkpointBytes: Long,
    ): Boolean = false

    override suspend fun updateOriginalSourceFacts(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        expectedSourceBytes: Long,
        sourceValidator: String,
    ): Boolean = false

    override suspend fun extendAttemptReservation(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        requiredReservationBytes: Long,
    ): DownloadReservationExtensionResult = DownloadReservationExtensionResult.StaleAttempt

    override suspend fun transitionAttempt(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        nextState: DownloadState,
        platformWorkIdentity: DownloadPlatformWorkIdentity?,
        failure: DownloadFailure?,
    ): Boolean = false

    override suspend fun completeFinalizing(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
    ): Boolean = false

    override suspend fun registerActiveAttempt(registration: DownloadActiveAttemptRegistration): Boolean = true

    override suspend fun updateRegisteredAttemptFacts(
        attempt: DownloadAttemptIdentity,
        physicalBytes: Long,
        checkpointBytes: Long,
    ): Boolean = true

    override suspend fun clearRegisteredAttempt(attempt: DownloadAttemptIdentity): Boolean = true

    override suspend fun checkpointAndInvalidateActiveAttempt(
        accountIdentity: AccountIdentity,
        registration: DownloadActiveAttemptRegistration,
        nextState: DownloadState,
    ): DownloadAttemptInvalidationResult {
        val current =
            records.firstOrNull { record ->
                record.downloadId == registration.attempt.downloadId &&
                    record.attemptGeneration == registration.attempt.attemptGeneration &&
                    record.businessKey.accountIdentity == accountIdentity &&
                    record.state == DownloadState.Downloading
            } ?: return DownloadAttemptInvalidationResult.StaleAttempt
        val facts = registration.checkpointAndClose()
        val next =
            current.copy(
                state = nextState,
                platformWorkIdentity = null,
                attemptGeneration = current.attemptGeneration + 1L,
                physicalBytes = facts.physicalBytes,
                checkpointBytes = facts.checkpointBytes,
                reservationBytes = maxOf(current.reservationBytes, facts.physicalBytes),
            )
        records[records.indexOf(current)] = next
        return DownloadAttemptInvalidationResult.Invalidated(next)
    }

    override suspend fun checkpointAndInvalidateUnregisteredAttempt(
        accountIdentity: AccountIdentity,
        attempt: DownloadAttemptIdentity,
        nextState: DownloadState,
    ): DownloadAttemptInvalidationResult {
        val current =
            records.firstOrNull { record ->
                record.downloadId == attempt.downloadId &&
                    record.attemptGeneration == attempt.attemptGeneration &&
                    record.businessKey.accountIdentity == accountIdentity &&
                    record.state == DownloadState.Downloading
            } ?: return DownloadAttemptInvalidationResult.StaleAttempt
        val next =
            current.copy(
                state = nextState,
                platformWorkIdentity = null,
                attemptGeneration = current.attemptGeneration + 1L,
            )
        records[records.indexOf(current)] = next
        return DownloadAttemptInvalidationResult.Invalidated(next)
    }

    override suspend fun checkpointAndRequeueForBoundary(
        accountIdentity: AccountIdentity,
        registration: DownloadActiveAttemptRegistration,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): DownloadAttemptInvalidationResult = checkpointAndInvalidateActiveAttempt(accountIdentity, registration, DownloadState.Queued)

    override suspend fun reconcileFinalizingForBoundary(accountIdentity: AccountIdentity): DownloadAttemptInvalidationResult =
        DownloadAttemptInvalidationResult.StaleAttempt
}

internal class FakeRemovalStore(
    private val queue: FakeQueueRepository,
) : DownloadRemovalStore {
    var membershipRevision = 0L
    var beginCalls = 0
    val pendingOperations = mutableListOf<DownloadRemovalOperation>()

    override suspend fun preview(accountIdentity: AccountIdentity): DownloadRemovalPreview = previewAll(listOf(accountIdentity))

    override suspend fun previewAll(accountIdentities: List<AccountIdentity>): DownloadRemovalPreview {
        val confirmations =
            accountIdentities.map { identity ->
                val records = queue.records.filter { it.businessKey.accountIdentity == identity }
                DownloadRemovalConfirmation(identity, membershipRevision, records.size.toLong(), records.sumOf { it.physicalBytes })
            }
        return DownloadRemovalPreview(membershipRevision, confirmations)
    }

    override suspend fun begin(
        operationId: DownloadRemovalOperationId,
        kind: DownloadRemovalKind,
        accountIdentities: List<AccountIdentity>,
        confirmations: List<DownloadRemovalConfirmation>,
        createdAtEpochMs: Long,
    ): BeginDownloadRemovalResult {
        beginCalls += 1
        if (confirmations.all { it.recordCount == 0L }) return BeginDownloadRemovalResult.NoDownloads
        val targets =
            accountIdentities.map { identity ->
                val matching = queue.records.filter { it.businessKey.accountIdentity == identity }
                DownloadRemovalTarget(
                    operationId,
                    identity,
                    membershipRevision,
                    matching.maxOfOrNull {
                        it.attemptGeneration + 1L
                    } ?: 0L,
                    matching.map { it.request.artifactKey },
                    false,
                    false,
                )
            }
        val operation = DownloadRemovalOperation(operationId, kind, createdAtEpochMs, targets)
        pendingOperations += operation
        return BeginDownloadRemovalResult.Created(operation)
    }

    override suspend fun pending(): List<DownloadRemovalOperation> = pendingOperations.toList()

    override suspend fun markAccountRemoved(
        operationId: DownloadRemovalOperationId,
        accountIdentity: AccountIdentity,
        cleanupGeneration: Long,
    ): Boolean =
        updateTarget(operationId, accountIdentity) {
            it.copy(accountRemoved = true)
        }

    override suspend fun updateRemainingArtifactKeys(
        operationId: DownloadRemovalOperationId,
        accountIdentity: AccountIdentity,
        cleanupGeneration: Long,
        remainingArtifactKeys: List<DownloadArtifactKey>,
    ): Boolean =
        updateTarget(operationId, accountIdentity) {
            it.copy(remainingArtifactKeys = remainingArtifactKeys)
        }

    override suspend fun markDownloadsRemoved(
        operationId: DownloadRemovalOperationId,
        accountIdentity: AccountIdentity,
        cleanupGeneration: Long,
    ): Boolean =
        updateTarget(operationId, accountIdentity) {
            it.copy(remainingArtifactKeys = emptyList(), downloadsRemoved = true)
        }

    override suspend fun deleteIfSettled(operationId: DownloadRemovalOperationId): Boolean {
        val operation = pendingOperations.firstOrNull { it.operationId == operationId } ?: return false
        if (!operation.settled) return false
        pendingOperations.remove(operation)
        return true
    }

    private fun updateTarget(
        operationId: DownloadRemovalOperationId,
        accountIdentity: AccountIdentity,
        update: (DownloadRemovalTarget) -> DownloadRemovalTarget,
    ): Boolean {
        val index = pendingOperations.indexOfFirst { it.operationId == operationId }
        if (index < 0) return false
        val operation = pendingOperations[index]
        val targetIndex = operation.targets.indexOfFirst { it.accountIdentity == accountIdentity }
        if (targetIndex < 0) return false
        val targets = operation.targets.toMutableList()
        targets[targetIndex] = update(targets[targetIndex])
        pendingOperations[index] = operation.copy(targets = targets)
        return true
    }
}

private class FakeExecutor(
    private val present: Set<AccountIdentity>,
) : SessionRemovalExecutor {
    private val absent = mutableSetOf<AccountIdentity>()
    var removeCalls = 0

    override suspend fun removeAccount(accountIdentity: AccountIdentity): Result<Unit> {
        removeCalls += 1
        absent += accountIdentity
        return Result.success(Unit)
    }

    override suspend fun removeAllAccounts(): Result<Unit> {
        removeCalls += 1
        absent += present
        return Result.success(Unit)
    }

    override suspend fun isAccountAbsent(accountIdentity: AccountIdentity): Result<Boolean> =
        Result.success(
            accountIdentity in absent || accountIdentity !in present,
        )
}

private class FakeExecutionHost(
    private val events: MutableList<String>,
) : DownloadExecutionHost {
    var userActionWakeCalls = 0

    override suspend fun wake(): Result<Unit> = Result.success(Unit)

    override suspend fun wakeFromUserAction(): Result<Unit> {
        userActionWakeCalls += 1
        return Result.success(Unit)
    }

    override suspend fun checkpointAndSuspend(attempt: DownloadAttemptIdentity?): Result<Unit> = Result.success(Unit)

    override suspend fun queryActiveWork(): Result<List<DownloadExecutionWork>> = Result.success(emptyList())

    override suspend fun reassociate(work: DownloadExecutionWork): Result<Unit> {
        events += "reassociate:${work.platformWorkIdentity.value}"
        return Result.success(Unit)
    }

    override suspend fun cancel(platformWorkIdentity: DownloadPlatformWorkIdentity): Result<Unit> {
        events += "cancel:${platformWorkIdentity.value}"
        return Result.success(Unit)
    }
}

private class FakeRecoveryDriver : com.jellyscope.core.download.DownloadExecutionDriver {
    override suspend fun wake(): Result<Unit> = Result.success(Unit)

    override suspend fun hasRunnableWork(): Boolean = false

    override suspend fun execute(platformWorkIdentity: DownloadPlatformWorkIdentity): Result<Unit> = Result.success(Unit)

    override suspend fun activeAttempt(): DownloadAttemptIdentity? = null

    override suspend fun checkpointAndSuspend(attempt: DownloadAttemptIdentity?): Result<Unit> = Result.success(Unit)

    override suspend fun checkpointAndRequeue(attempt: DownloadAttemptIdentity?): Result<DownloadLifecycleRequeueOutcome> =
        Result.success(DownloadLifecycleRequeueOutcome.NoActiveAttempt)

    override suspend fun reassociate(work: DownloadExecutionWork): Result<Unit> = Result.success(Unit)

    override suspend fun applyRecoveryAction(action: DownloadActiveRecoveryAction): Result<Unit> = Result.success(Unit)

    override suspend fun cancelRequested(platformWorkIdentity: DownloadPlatformWorkIdentity): Result<Unit> = Result.success(Unit)
}
