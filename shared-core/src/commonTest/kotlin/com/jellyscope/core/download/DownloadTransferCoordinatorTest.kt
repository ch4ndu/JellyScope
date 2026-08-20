// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.data.local.DOWNLOAD_ORIGINAL_PART_KEY
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
import com.jellyscope.core.data.local.GateHeldBoundaryCommit
import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.remote.AuthHeaderProvider
import com.jellyscope.core.data.remote.JellyfinApi
import com.jellyscope.core.data.remote.JellyfinClientFactory
import com.jellyscope.core.data.remote.KtorJellyfinApi
import com.jellyscope.core.data.repository.DownloadActiveAttemptRegistration
import com.jellyscope.core.data.repository.DownloadCommandResult
import com.jellyscope.core.data.repository.DownloadDeletionResult
import com.jellyscope.core.data.repository.DownloadQueueRepository
import com.jellyscope.core.data.repository.SessionRemovalAuthorization
import com.jellyscope.core.data.repository.SessionRepository
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadBusinessKey
import com.jellyscope.core.domain.model.DownloadFailure
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadPlatformWorkIdentity
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.DownloadReservationExtensionResult
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.OfflineMediaSnapshot
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadTransferCoordinatorTest {
    private val account = AccountIdentity("server", "user-1")
    private val session =
        Session(
            serverUrl = "https://jellyfin.example",
            serverId = account.serverId,
            serverName = "Server",
            userId = account.userId,
            userName = "User",
            accessToken = "token-1",
            deviceId = "device-1",
        )

    @Test
    fun originalTransferPersistsSourceFactsExtendsReservationAndCompletesExactlyOnce() =
        runTest {
            val registry = ServerScopedStoreRegistry()
            registry.transitionToAccount(account, boundaryEpoch = 4L)
            val queue = TransferQueueFake(mutableListOf(queuedRecord()))
            val queueCoordinator = DownloadQueueCoordinator(queue)
            val artifacts = TransferArtifactStore()
            val fixture = apiFixture()
            try {
                val result =
                    DownloadTransferCoordinator(
                        sessionRepository = FakeSessionRepository(session),
                        serverScopedStoreRegistry = registry,
                        jellyfinApi = fixture.api,
                        queueCoordinator = queueCoordinator,
                        artifactStore = artifacts,
                        localSubtitleAssetStore = EmptyLocalSubtitleAssetStore,
                        localSubtitleFileStore = EmptyLocalSubtitleFileStore,
                    ).runOnce()

                assertEquals(DownloadTransferResult.Completed, result)
                val completed = queue.records.single()
                assertEquals(DownloadState.Completed, completed.state)
                assertEquals(4L, completed.physicalBytes)
                assertEquals(4L, completed.reservationBytes)
                assertEquals(4L, completed.request.expectedSourceBytes)
                assertEquals(LAST_MODIFIED, completed.request.sourceValidator)
                assertEquals("data", artifacts.completedBytes().decodeToString())
                assertTrue(queue.extensionCalls > 0)
                assertTrue(queue.progressCalls <= 2)

                // A late completion callback for the old generation is update-only.
                assertFalse(
                    queueCoordinator.completeFinalizing(
                        DownloadAttemptIdentity(completed.downloadId, completed.attemptGeneration),
                    ),
                )
                assertEquals(DownloadState.Completed, queue.records.single().state)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun accountSwitchAfterResponseHeadersPreventsBodyAdmissionAndAnyWrite() =
        runTest {
            val registry = ServerScopedStoreRegistry()
            registry.transitionToAccount(account, boundaryEpoch = 4L)
            val queue = TransferQueueFake(mutableListOf(queuedRecord()))
            val artifacts = TransferArtifactStore()
            val fixture =
                apiFixture {
                    if (it == "bytes=0-") {
                        registry.transitionToAccount(AccountIdentity("server", "other"), boundaryEpoch = 5L)
                    }
                }
            try {
                val result =
                    DownloadTransferCoordinator(
                        sessionRepository = FakeSessionRepository(session),
                        serverScopedStoreRegistry = registry,
                        jellyfinApi = fixture.api,
                        queueCoordinator = DownloadQueueCoordinator(queue),
                        artifactStore = artifacts,
                        localSubtitleAssetStore = EmptyLocalSubtitleAssetStore,
                        localSubtitleFileStore = EmptyLocalSubtitleFileStore,
                    ).runOnce()

                assertEquals(DownloadTransferResult.BoundaryChanged, result)
                assertTrue(artifacts.writes == 0)
                assertEquals(DownloadState.Queued, queue.records.single().state)
                assertEquals(2L, queue.records.single().attemptGeneration)
                assertFalse(queue.records.single().state == DownloadState.Completed)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun accountRemovalAfterResponseHeadersPreventsBodyAdmissionAndAnyWrite() =
        runTest {
            val registry = ServerScopedStoreRegistry()
            registry.transitionToAccount(account, boundaryEpoch = 4L)
            val queue = TransferQueueFake(mutableListOf(queuedRecord()))
            val artifacts = TransferArtifactStore()
            val fixture =
                apiFixture {
                    if (it == "bytes=0-") {
                        registry.clearAccount(account)
                    }
                }
            try {
                val result =
                    DownloadTransferCoordinator(
                        sessionRepository = FakeSessionRepository(session),
                        serverScopedStoreRegistry = registry,
                        jellyfinApi = fixture.api,
                        queueCoordinator = DownloadQueueCoordinator(queue),
                        artifactStore = artifacts,
                        localSubtitleAssetStore = EmptyLocalSubtitleAssetStore,
                        localSubtitleFileStore = EmptyLocalSubtitleFileStore,
                    ).runOnce()

                assertEquals(DownloadTransferResult.BoundaryChanged, result)
                assertEquals(0, artifacts.writes)
                assertEquals(DownloadState.Queued, queue.records.single().state)
                assertEquals(2L, queue.records.single().attemptGeneration)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun cancellationBeforeWriterRegistrationRequeuesClaimAndUnblocksNextFifo() =
        runTest {
            val registry = ServerScopedStoreRegistry()
            registry.transitionToAccount(account, boundaryEpoch = 4L)
            val queue =
                TransferQueueFake(
                    mutableListOf(
                        queuedRecord(fifoSequence = 1L),
                        queuedRecord(downloadId = DownloadId("download_next"), fifoSequence = 2L),
                    ),
                )
            val artifacts = TransferArtifactStore()
            val fixture =
                apiFixture {
                    if (it == "bytes=0-0") throw CancellationException("test cancellation")
                }
            try {
                kotlin.test.assertFailsWith<CancellationException> {
                    DownloadTransferCoordinator(
                        sessionRepository = FakeSessionRepository(session),
                        serverScopedStoreRegistry = registry,
                        jellyfinApi = fixture.api,
                        queueCoordinator = DownloadQueueCoordinator(queue),
                        artifactStore = artifacts,
                        localSubtitleAssetStore = EmptyLocalSubtitleAssetStore,
                        localSubtitleFileStore = EmptyLocalSubtitleFileStore,
                    ).runOnce()
                }
                assertEquals(DownloadState.Paused, queue.records.first().state)
                assertEquals(2L, queue.records.first().attemptGeneration)
                assertEquals(DownloadId("download_next"), DownloadQueueCoordinator(queue).claimNext(account)?.downloadId)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun lifecycleCancellationBeforeWriterRegistrationQueuesTheNextGeneration() =
        runTest {
            val registry = ServerScopedStoreRegistry()
            registry.transitionToAccount(account, boundaryEpoch = 4L)
            val queue =
                TransferQueueFake(
                    mutableListOf(
                        queuedRecord(fifoSequence = 1L),
                        queuedRecord(downloadId = DownloadId("download_next"), fifoSequence = 2L),
                    ),
                )
            val queueCoordinator = DownloadQueueCoordinator(queue)
            val artifacts = TransferArtifactStore()
            val fixture =
                apiFixture {
                    if (it == "bytes=0-0") throw DownloadLifecycleRequeueCancellation()
                }
            try {
                assertFailsWith<DownloadLifecycleRequeueCancellation> {
                    DownloadTransferCoordinator(
                        sessionRepository = FakeSessionRepository(session),
                        serverScopedStoreRegistry = registry,
                        jellyfinApi = fixture.api,
                        queueCoordinator = queueCoordinator,
                        artifactStore = artifacts,
                        localSubtitleAssetStore = EmptyLocalSubtitleAssetStore,
                        localSubtitleFileStore = EmptyLocalSubtitleFileStore,
                    ).runOnce()
                }
                assertEquals(DownloadState.Queued, queue.records.first().state)
                assertEquals(2L, queue.records.first().attemptGeneration)
                assertEquals(DownloadId("download_original"), queueCoordinator.claimNext(account)?.downloadId)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun lifecycleCancellationRequeuesOnlyTheCapturedAttemptAfterWriterCancellationPausesIt() =
        runTest {
            val registry = ServerScopedStoreRegistry()
            registry.transitionToAccount(account, boundaryEpoch = 4L)
            val queue =
                TransferQueueFake(
                    mutableListOf(
                        queuedRecord(fifoSequence = 1L),
                        queuedRecord(downloadId = DownloadId("download_paused"), fifoSequence = 2L)
                            .copy(state = DownloadState.Paused, attemptGeneration = 3L),
                    ),
                )
            val queueCoordinator = DownloadQueueCoordinator(queue)
            val artifacts = TransferArtifactStore()
            val fixture =
                apiFixture {
                    if (it == "bytes=0-") throw CancellationException("test active cancellation")
                }
            val capturedAttempt = DownloadAttemptIdentity(DownloadId("download_original"), 1L)
            try {
                assertFailsWith<CancellationException> {
                    DownloadTransferCoordinator(
                        sessionRepository = FakeSessionRepository(session),
                        serverScopedStoreRegistry = registry,
                        jellyfinApi = fixture.api,
                        queueCoordinator = queueCoordinator,
                        artifactStore = artifacts,
                        localSubtitleAssetStore = EmptyLocalSubtitleAssetStore,
                        localSubtitleFileStore = EmptyLocalSubtitleFileStore,
                    ).runOnce()
                }

                assertEquals(DownloadState.Paused, queue.records.first().state)
                assertEquals(capturedAttempt.attemptGeneration, queue.records.first().attemptGeneration)
                assertEquals(
                    DownloadLifecycleRequeueOutcome.Requeued,
                    queueCoordinator.checkpointAndRequeueForLifecycle(account, capturedAttempt),
                )
                assertEquals(DownloadState.Queued, queue.records.first().state)
                assertEquals(DownloadState.Paused, queue.records[1].state)
                assertEquals(DownloadId("download_original"), queueCoordinator.claimNext(account)?.downloadId)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun quotaLoweredAfterResponseAdmissionBlocksBeforeFinalizationCommit() =
        runTest {
            val registry = ServerScopedStoreRegistry()
            registry.transitionToAccount(account, boundaryEpoch = 4L)
            val queue = TransferQueueFake(mutableListOf(queuedRecord()))
            queue.records[0] = queue.records[0].copy(reservationBytes = 4L)
            val artifacts = TransferArtifactStore()
            val fixture =
                apiFixture {
                    if (it == "bytes=0-") queue.rejectExistingReservation = true
                }
            try {
                val result =
                    DownloadTransferCoordinator(
                        sessionRepository = FakeSessionRepository(session),
                        serverScopedStoreRegistry = registry,
                        jellyfinApi = fixture.api,
                        queueCoordinator = DownloadQueueCoordinator(queue),
                        artifactStore = artifacts,
                        localSubtitleAssetStore = EmptyLocalSubtitleAssetStore,
                        localSubtitleFileStore = EmptyLocalSubtitleFileStore,
                    ).runOnce()

                assertEquals(DownloadTransferResult.BlockedByQuota, result)
                assertEquals(DownloadState.BlockedByQuota, queue.records.single().state)
                assertTrue(artifacts.writes > 0)
                assertTrue(artifacts.completedBytes().isEmpty())
            } finally {
                fixture.client.close()
            }
        }

    private fun queuedRecord(
        downloadId: DownloadId = DownloadId("download_original"),
        fifoSequence: Long = 1L,
    ): DownloadRecord =
        DownloadRecord(
            request =
                DownloadRequest(
                    downloadId = downloadId,
                    businessKey = DownloadBusinessKey(account, "item-1", "source-1"),
                    quality = DownloadQuality.Original,
                    artifactKind = DownloadArtifactKind.OriginalFile,
                    selectedAudioStreamIndex = null,
                    subtitleSelection = DownloadSubtitleSelection.Off,
                    admissionEstimateBytes = 1L,
                    initialReservationBytes = 1L,
                    artifactKey = DownloadArtifactKey("artifact_original"),
                    snapshot =
                        OfflineMediaSnapshot(
                            title = "Movie",
                            itemKind = MediaKind.Movie,
                            backendSource = BackendSourceDescriptor("mkv", "h264", "aac", false),
                        ),
                    createdAtEpochMs = 1L,
                ),
            fifoSequence = fifoSequence,
            state = DownloadState.Queued,
            reservationBytes = 1L,
            physicalBytes = 0L,
            checkpointBytes = 0L,
            attemptGeneration = 0L,
            updatedAtEpochMs = 1L,
        )

    private fun apiFixture(onTransferRequest: suspend (String?) -> Unit = {}): ApiFixture {
        val engine =
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/Users/user-1" -> respondJson(ALLOWED_USER_JSON)
                    "/Items/item-1" -> respondJson(ITEM_JSON)
                    "/Videos/item-1/stream" -> {
                        val range = request.headers[HttpHeaders.Range]
                        onTransferRequest(range)
                        when (range) {
                            "bytes=0-0" -> respondPartial("a", "bytes 0-0/4")
                            "bytes=0-" -> respondPartial("data", "bytes 0-3/4")
                            else -> error("Unexpected range")
                        }
                    }
                    else -> error("Unexpected request path: ${request.url.encodedPath}")
                }
            }
        val client = JellyfinClientFactory(enableHttpLogging = true).createDownloadTransfer(engine)
        return ApiFixture(
            api =
                KtorJellyfinApi(
                    client = client,
                    downloadClient = client,
                    authHeaderProvider =
                        object : AuthHeaderProvider {
                            override suspend fun authHeader(token: String?): String = AUTHORIZATION
                        },
                ),
            client = client,
        )
    }
}

private data class ApiFixture(
    val api: JellyfinApi,
    val client: HttpClient,
)

private const val AUTHORIZATION = "MediaBrowser Token=\"token-1\""
private const val LAST_MODIFIED = "Wed, 21 Oct 2015 07:28:00 GMT"
private const val ALLOWED_USER_JSON =
    """{"Id":"user-1","Name":"User","Policy":{"EnableContentDownloading":true}}"""
private const val ITEM_JSON =
    """{"Id":"item-1","Type":"Movie","IsLive":false,"MediaSources":[{"Id":"source-1","Size":4,"IsInfiniteStream":false}]}"""

private fun MockRequestHandleScope.respondJson(content: String) =
    respond(content = content, headers = headersOf(HttpHeaders.ContentType, "application/json"))

private fun MockRequestHandleScope.respondPartial(
    content: String,
    contentRange: String,
) = respond(
    content = content,
    status = HttpStatusCode.PartialContent,
    headers =
        headersOf(
            HttpHeaders.ContentRange to listOf(contentRange),
            HttpHeaders.LastModified to listOf(LAST_MODIFIED),
            HttpHeaders.ContentLength to listOf(content.encodeToByteArray().size.toString()),
        ),
)

private class FakeSessionRepository(
    session: Session,
) : SessionRepository {
    private val state = MutableStateFlow<SessionState>(SessionState.LoggedIn(session, boundaryEpoch = 4L))
    override val sessionState: StateFlow<SessionState> = state.asStateFlow()

    override suspend fun setLoggedIn(session: Session) {
        state.value = SessionState.LoggedIn(session, boundaryEpoch = 4L)
    }

    override suspend fun setLoggedOut(
        serverUrl: String?,
        authorization: SessionRemovalAuthorization,
    ): Result<Unit> {
        state.value = SessionState.LoggedOut(serverUrl)
        return Result.success(Unit)
    }
}

private class TransferQueueFake(
    val records: MutableList<DownloadRecord>,
) : DownloadQueueRepository {
    var extensionCalls = 0
    var progressCalls = 0
    var rejectExistingReservation = false
    private var registration: DownloadActiveAttemptRegistration? = null

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
            records.firstOrNull { record ->
                record.businessKey.accountIdentity == activeAccount && record.state == DownloadState.Queued
            } ?: return null
        val claimed =
            current.copy(
                state = DownloadState.Downloading,
                attemptGeneration = current.attemptGeneration + 1L,
                platformWorkIdentity = platformWorkIdentity,
            )
        records[records.indexOf(current)] = claimed
        return claimed
    }

    override suspend fun updateAttemptProgress(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        physicalBytes: Long,
        checkpointBytes: Long,
    ): Boolean {
        progressCalls += 1
        val current = get(downloadId, expectedAttemptGeneration) ?: return false
        if (current.state != DownloadState.Downloading && current.state != DownloadState.Finalizing) return false
        if (physicalBytes < current.physicalBytes || checkpointBytes !in 0L..physicalBytes) return false
        records[records.indexOf(current)] = current.copy(physicalBytes = physicalBytes, checkpointBytes = checkpointBytes)
        return true
    }

    override suspend fun updateOriginalSourceFacts(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        expectedSourceBytes: Long,
        sourceValidator: String,
    ): Boolean {
        val current = get(downloadId, expectedAttemptGeneration) ?: return false
        records[records.indexOf(current)] =
            current.copy(
                request =
                    current.request.copy(
                        expectedSourceBytes = expectedSourceBytes,
                        sourceValidator = sourceValidator,
                    ),
            )
        return true
    }

    override suspend fun extendAttemptReservation(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        requiredReservationBytes: Long,
    ): DownloadReservationExtensionResult {
        extensionCalls += 1
        val current =
            get(downloadId, expectedAttemptGeneration)
                ?: return DownloadReservationExtensionResult.StaleAttempt
        if (rejectExistingReservation && requiredReservationBytes <= current.reservationBytes) {
            return DownloadReservationExtensionResult.Rejected(
                com.jellyscope.core.domain.model.DownloadAdmissionDecision.QuotaExceeded,
            )
        }
        if (requiredReservationBytes > 10_000L) {
            return DownloadReservationExtensionResult.Rejected(
                com.jellyscope.core.domain.model.DownloadAdmissionDecision.QuotaExceeded,
            )
        }
        if (requiredReservationBytes <= current.reservationBytes) {
            return DownloadReservationExtensionResult.Unchanged(current.reservationBytes)
        }
        records[records.indexOf(current)] = current.copy(reservationBytes = requiredReservationBytes)
        return DownloadReservationExtensionResult.Extended(requiredReservationBytes)
    }

    override suspend fun transitionAttempt(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        nextState: DownloadState,
        platformWorkIdentity: DownloadPlatformWorkIdentity?,
        failure: DownloadFailure?,
    ): Boolean {
        val current = get(downloadId, expectedAttemptGeneration) ?: return false
        if (!isLegalTransition(current.state, nextState)) return false
        val next =
            current.copy(
                state = nextState,
                platformWorkIdentity = platformWorkIdentity,
                failure = failure,
            )
        records[records.indexOf(current)] = next
        return true
    }

    override suspend fun completeFinalizing(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
    ): Boolean {
        val current = get(downloadId, expectedAttemptGeneration) ?: return false
        if (current.state != DownloadState.Finalizing) return false
        records[records.indexOf(current)] =
            current.copy(
                state = DownloadState.Completed,
                reservationBytes = current.physicalBytes,
                platformWorkIdentity = null,
            )
        return true
    }

    override suspend fun registerActiveAttempt(registration: DownloadActiveAttemptRegistration): Boolean {
        val current = get(registration.attempt.downloadId, registration.attempt.attemptGeneration) ?: return false
        if (current.state != DownloadState.Downloading) return false
        this.registration = registration
        return true
    }

    override suspend fun updateRegisteredAttemptFacts(
        attempt: DownloadAttemptIdentity,
        physicalBytes: Long,
        checkpointBytes: Long,
    ): Boolean = updateAttemptProgress(attempt.downloadId, attempt.attemptGeneration, physicalBytes, checkpointBytes)

    override suspend fun clearRegisteredAttempt(attempt: DownloadAttemptIdentity): Boolean {
        registration = null
        return true
    }

    override suspend fun checkpointAndInvalidateActiveAttempt(
        accountIdentity: AccountIdentity,
        registration: DownloadActiveAttemptRegistration,
        nextState: DownloadState,
    ): DownloadAttemptInvalidationResult {
        val current =
            get(registration.attempt.downloadId, registration.attempt.attemptGeneration)
                ?: return DownloadAttemptInvalidationResult.StaleAttempt
        val next =
            current.copy(
                state = nextState,
                attemptGeneration = current.attemptGeneration + 1L,
                platformWorkIdentity = null,
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
            get(attempt.downloadId, attempt.attemptGeneration)
                ?: return DownloadAttemptInvalidationResult.StaleAttempt
        val next =
            current.copy(
                state = nextState,
                attemptGeneration = current.attemptGeneration + 1L,
                platformWorkIdentity = null,
            )
        records[records.indexOf(current)] = next
        return DownloadAttemptInvalidationResult.Invalidated(next)
    }

    override suspend fun checkpointAndRequeueForBoundary(
        accountIdentity: AccountIdentity,
        registration: DownloadActiveAttemptRegistration,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): DownloadAttemptInvalidationResult = DownloadAttemptInvalidationResult.StaleAttempt

    override suspend fun reconcileFinalizingForBoundary(accountIdentity: AccountIdentity): DownloadAttemptInvalidationResult =
        DownloadAttemptInvalidationResult.StaleAttempt

    private fun get(
        downloadId: DownloadId,
        generation: Long,
    ): DownloadRecord? = records.firstOrNull { record -> record.downloadId == downloadId && record.attemptGeneration == generation }

    private fun isLegalTransition(
        current: DownloadState,
        next: DownloadState,
    ): Boolean =
        when (current) {
            DownloadState.Downloading ->
                next == DownloadState.Failed ||
                    next == DownloadState.BlockedByQuota ||
                    next == DownloadState.Finalizing ||
                    next == DownloadState.Paused
            DownloadState.Finalizing -> next == DownloadState.Failed
            DownloadState.Paused -> next == DownloadState.Queued
            else -> false
        }
}

private object EmptyLocalSubtitleAssetStore : LocalSubtitleAssetStore {
    override fun observe(context: LocalSubtitleContext) = flowOf<List<LocalSubtitleAsset>>()

    override fun observePendingSync() = flowOf<List<LocalSubtitleAsset>>()

    override suspend fun get(assetId: String): LocalSubtitleAsset? = null

    override suspend fun findByProviderFile(
        context: LocalSubtitleContext,
        provider: String,
        providerFileId: String,
    ): LocalSubtitleAsset? = null

    override suspend fun upsert(asset: LocalSubtitleAsset) = Unit

    override suspend fun delete(assetId: String) = Unit

    override suspend fun all(): List<LocalSubtitleAsset> = emptyList()

    override suspend fun clearAll() = Unit
}

private object EmptyLocalSubtitleFileStore : LocalSubtitleFileStore {
    override suspend fun writeAtomically(
        fileId: String,
        bytes: ByteArray,
    ) = Unit

    override suspend fun read(fileId: String): ByteArray? = null

    override suspend fun exists(fileId: String): Boolean = false

    override suspend fun delete(fileId: String) = Unit

    override suspend fun listFileIds(): Set<String> = emptySet()

    override fun resolvePath(fileId: String): String? = null
}

private class TransferArtifactStore : DownloadArtifactStore {
    private val partKey = DOWNLOAD_ORIGINAL_PART_KEY
    private var staging: MutableList<Byte>? = null
    private var completed: MutableList<Byte>? = null
    var writes = 0
        private set

    fun completedBytes(): ByteArray = completed?.toByteArray() ?: ByteArray(0)

    override suspend fun openStagingWriter(
        artifactKey: DownloadArtifactKey,
        partKey: DownloadArtifactPartKey,
        mode: DownloadArtifactWriteMode,
    ): DownloadArtifactWriter {
        require(partKey == this.partKey)
        val bytes =
            when (mode) {
                DownloadArtifactWriteMode.Create -> {
                    require(staging == null)
                    mutableListOf()
                }
                is DownloadArtifactWriteMode.Resume -> {
                    require(staging?.size?.toLong() == mode.expectedLengthBytes)
                    staging ?: error("Missing staging")
                }
            }
        staging = bytes
        return object : DownloadArtifactWriter {
            private var closed = false
            override val partKey: DownloadArtifactPartKey = this@TransferArtifactStore.partKey
            override val lengthBytes: Long get() = bytes.size.toLong()

            override suspend fun write(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ) {
                check(!closed)
                require(length <= 64 * 1024)
                repeat(length) { index -> bytes += buffer[offset + index] }
                writes += 1
            }

            override suspend fun checkpoint(): DownloadArtifactPartCheckpoint = DownloadArtifactPartCheckpoint(partKey, bytes.size.toLong())

            override suspend fun close() {
                closed = true
            }
        }
    }

    override suspend fun inspect(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
    ): DownloadArtifactInspection? {
        val bytes = if (area == DownloadArtifactArea.Staging) staging else completed
        return bytes?.let {
            DownloadArtifactInspection(
                artifactKey = artifactKey,
                area = area,
                parts = listOf(DownloadArtifactPartInspection(partKey, it.size.toLong())),
            )
        }
    }

    override suspend fun completedPartPath(
        artifactKey: DownloadArtifactKey,
        partKey: DownloadArtifactPartKey,
    ): String? = null

    override suspend fun readPart(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
        partKey: DownloadArtifactPartKey,
        maxBytes: Int,
    ): ByteArray? = null

    override suspend fun validateStagingCheckpoint(
        artifactKey: DownloadArtifactKey,
        checkpoint: DownloadArtifactCheckpoint,
    ): Boolean = checkpoint.parts == listOf(DownloadArtifactPartCheckpoint(partKey, staging?.size?.toLong() ?: -1L))

    override suspend fun normalizeStagingCheckpoint(
        artifactKey: DownloadArtifactKey,
        checkpoint: DownloadArtifactCheckpoint,
    ): Boolean = true

    override suspend fun promote(artifactKey: DownloadArtifactKey): DownloadArtifactInspection {
        completed = staging
        staging = null
        return requireNotNull(inspect(artifactKey, DownloadArtifactArea.Completed))
    }

    override suspend fun delete(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
    ) {
        if (area == DownloadArtifactArea.Staging) staging = null else completed = null
    }

    override suspend fun enumerate(area: DownloadArtifactArea): List<DownloadArtifactInspection> = emptyList()

    override suspend fun capacity(): DownloadArtifactCapacity = DownloadArtifactCapacity(2_000_000_000L, 2_000_000_000L)
}
