// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.local.SubtitleSelectionStore
import com.jellyscope.core.data.remote.AuthenticatedRequestContext
import com.jellyscope.core.data.remote.BaseItemDto
import com.jellyscope.core.data.remote.MediaSourceDto
import com.jellyscope.core.data.remote.MediaStreamDto
import com.jellyscope.core.data.remote.UploadSubtitleDto
import com.jellyscope.core.data.remote.UserDto
import com.jellyscope.core.data.remote.UserPolicyDto
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.LocalSubtitleSyncState
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.LogScrubber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalSubtitleSyncRepositoryTest {
    @Test
    fun alternateSourceRemainsLocalOnly() =
        runTest {
            val fixture = fixture(sourceIds = listOf("primary", "alternate"), assetSourceId = "alternate")

            fixture.repository.sync(fixture.session, fixture.asset)

            assertEquals(LocalSubtitleSyncState.LocalOnlyAlternateSource, fixture.store.current.syncState)
            assertEquals(0, fixture.api.uploadCalls)
        }

    @Test
    fun missingPermissionDoesNotUpload() =
        runTest {
            val fixture = fixture(canManageSubtitles = false)

            fixture.repository.sync(fixture.session, fixture.asset)

            assertEquals(LocalSubtitleSyncState.PermissionDenied, fixture.store.current.syncState)
            assertEquals(0, fixture.api.uploadCalls)
        }

    @Test
    fun exactServerCopyIsConfirmedWithoutUpload() =
        runTest {
            val fixture = fixture(initialServerCopy = true)

            fixture.repository.sync(fixture.session, fixture.asset)

            assertIs<LocalSubtitleSyncState.Confirmed>(fixture.store.current.syncState)
            assertEquals(7, fixture.store.current.confirmedStreamIndex)
            assertEquals(0, fixture.api.uploadCalls)
        }

    @Test
    fun pendingAssetUploadsOnceAndReconcilesByContent() =
        runTest {
            val fixture = fixture(serverCopyAfterUpload = true)

            fixture.repository.sync(fixture.session, fixture.asset)

            assertEquals(1, fixture.api.uploadCalls)
            assertIs<LocalSubtitleSyncState.Confirmed>(fixture.store.current.syncState)
        }

    @Test
    fun uploadFailureEmitsOnlyTypedSafeDiagnostic() =
        runTest {
            val messages = mutableListOf<String>()
            val writer =
                object : LogWriter() {
                    override fun log(
                        severity: Severity,
                        message: String,
                        tag: String,
                        throwable: Throwable?,
                    ) {
                        if (tag == DiagnosticTag.LocalSubtitleSync.wireValue) {
                            messages += message
                            assertEquals(null, throwable)
                        }
                    }
                }
            val fixture = fixture(uploadFailure = IllegalStateException("subtitle identity message"))
            try {
                Logger.setLogWriters(writer)
                fixture.repository.sync(fixture.session, fixture.asset)
            } finally {
                Logger.setLogWriters(emptyList())
            }

            assertTrue(messages.isNotEmpty(), messages.toString())
            val message =
                messages.firstOrNull { value -> value.contains("operation=localSubtitleUpload") } ?: error(messages.joinToString(" | "))
            assertEquals("stage=upload event=failed operation=localSubtitleUpload exceptionType=IllegalStateException", message)
            assertEquals(message, LogScrubber.capture(DiagnosticTag.LocalSubtitleSync.wireValue, message))
            assertFalse(message.contains("subtitle identity message"))
            assertFalse(message.contains("episode-1"))
        }

    @Test
    fun staleDispatchNeverPostsAutomaticallyButManualRetryMayPostAfterConclusiveReconciliation() =
        runTest {
            val fixture =
                fixture(
                    initialState = LocalSubtitleSyncState.UploadedUnconfirmed,
                    serverCopyAfterUpload = true,
                )

            fixture.repository.sync(fixture.session, fixture.asset)
            assertEquals(0, fixture.api.uploadCalls)
            assertEquals(LocalSubtitleSyncState.UploadedUnconfirmed, fixture.store.current.syncState)

            fixture.repository.retry(fixture.session, fixture.store.current)
            assertEquals(1, fixture.api.uploadCalls)
            assertIs<LocalSubtitleSyncState.Confirmed>(fixture.store.current.syncState)
        }

    @Test
    fun manualSyncCanonicalizesLocalAndRemoteTextOnTheInjectedWorkerDispatcher() =
        runTest {
            val workerDispatcher = QueuedSyncDispatcher()
            val cpuOperations = RecordingSyncCpuOperations(workerDispatcher)
            val fixture =
                fixture(
                    initialServerCopy = true,
                    initialState = LocalSubtitleSyncState.UploadedUnconfirmed,
                    workerDispatcher = workerDispatcher,
                    cpuOperations = cpuOperations,
                    localBytes = WEB_VTT.replace("\n", "\r\n").encodeToByteArray(),
                    remoteSubtitleText = "\n${WEB_VTT.trim()}\n\n",
                )
            val retry = async { fixture.repository.retry(fixture.session, fixture.asset) }

            runCurrent()

            assertEquals(1, workerDispatcher.pendingCount)
            assertEquals(0, fixture.api.itemDetailCalls)

            workerDispatcher.runNext()
            runCurrent()

            assertEquals(1, fixture.api.itemDetailCalls)
            assertEquals(1, fixture.api.subtitleTextCalls)
            assertEquals(1, workerDispatcher.pendingCount)
            assertEquals(LocalSubtitleSyncState.UploadedUnconfirmed, fixture.store.current.syncState)

            workerDispatcher.runNext()
            runCurrent()
            retry.await()

            assertEquals(0, workerDispatcher.pendingCount)
            assertEquals(0, fixture.api.uploadCalls)
            assertIs<LocalSubtitleSyncState.Confirmed>(fixture.store.current.syncState)
            assertEquals(listOf(true), cpuOperations.localWorkerContexts)
            assertEquals(listOf(true), cpuOperations.remoteWorkerContexts)
            assertEquals(emptyList(), cpuOperations.uploadWorkerContexts)
        }

    @Test
    fun uploadEncodingRunsOnWorkerBeforeUploadingPublicationAndPost() =
        runTest {
            val workerDispatcher = QueuedSyncDispatcher()
            val cpuOperations = RecordingSyncCpuOperations(workerDispatcher)
            val fixture =
                fixture(
                    workerDispatcher = workerDispatcher,
                    cpuOperations = cpuOperations,
                )
            val sync = async { fixture.repository.sync(fixture.session, fixture.asset) }

            runCurrent()
            assertEquals(1, workerDispatcher.pendingCount)

            workerDispatcher.runNext()
            runCurrent()

            assertEquals(1, workerDispatcher.pendingCount)
            assertEquals(LocalSubtitleSyncState.Pending, fixture.store.current.syncState)
            assertEquals(0, fixture.api.uploadCalls)
            assertEquals(emptyList(), cpuOperations.uploadWorkerContexts)

            workerDispatcher.runNext()
            runCurrent()
            sync.await()

            assertEquals(listOf(true), cpuOperations.uploadWorkerContexts)
            assertEquals(1, fixture.api.uploadCalls)
            assertEquals(DefaultLocalSubtitleSyncCpuOperations.encodeUpload(WEB_VTT.encodeToByteArray()), fixture.api.uploadedData)
        }

    @Test
    fun startupReconciliationRemovesMissingRowsSelectionsAndOrphanFiles() =
        runTest {
            val asset = localAsset("primary", LocalSubtitleSyncState.Pending)
            val assetStore = FakeLocalSubtitleAssetStore(asset)
            val fileStore = ReconcileFileStore(mutableSetOf("orphan.vtt"))
            val selectionStore = ReconcileSelectionStore()
            val coordinator = LocalSubtitleMutationCoordinator(assetStore, fileStore, selectionStore, backgroundScope)
            val key = SubtitleSelectionKey(asset.serverId, asset.userId, asset.itemId, asset.mediaSourceId)
            selectionStore.save(key, SubtitleSelectionIntent.LocalAsset(asset.id))
            val reconciler = LocalSubtitleStorageReconciler(coordinator)

            reconciler.reconcile()

            assertEquals(null, assetStore.get(asset.id))
            assertEquals(null, selectionStore.get(key))
            assertEquals(setOf(asset.fileId, "orphan.vtt"), fileStore.deleted)
        }

    @Test
    fun startupOwnerAwaitsReconciliationBeforeSyncingTheRestoredPendingAsset() =
        runTest {
            val reconciliationStarted = CompletableDeferred<Unit>()
            val finishReconciliation = CompletableDeferred<Unit>()
            val asset = localAsset("primary", LocalSubtitleSyncState.Pending)
            val assetStore =
                FakeLocalSubtitleAssetStore(asset) {
                    reconciliationStarted.complete(Unit)
                    finishReconciliation.await()
                }
            val fileStore = FakeLocalSubtitleFileStore(asset.fileId, WEB_VTT.encodeToByteArray())
            val mutationCoordinator =
                LocalSubtitleMutationCoordinator(
                    assetStore = assetStore,
                    fileStore = fileStore,
                    selectionStore = ReconcileSelectionStore(),
                    scope = backgroundScope,
                )
            val sessionRepository = StartupSessionRepository()
            val syncRepository = RecordingLocalSubtitleSyncRepository()
            LocalSubtitleSyncCoordinator(
                sessionRepository = sessionRepository,
                assetStore = assetStore,
                storageReconciler = LocalSubtitleStorageReconciler(mutationCoordinator),
                syncRepository = syncRepository,
                scope = backgroundScope,
            )

            runCurrent()
            assertTrue(reconciliationStarted.isCompleted)

            sessionRepository.publishRestored(sessionFor(asset))
            runCurrent()

            assertIs<SessionState.LoggedIn>(sessionRepository.sessionState.value)
            assertEquals(emptyList(), syncRepository.syncedAssetIds)

            finishReconciliation.complete(Unit)
            runCurrent()

            assertEquals(listOf(asset.id), syncRepository.syncedAssetIds)
            runCurrent()
            assertEquals(1, syncRepository.syncedAssetIds.size)
        }

    @Test
    fun reconciliationFailureIsSanitizedAndStartupObservationContinues() =
        runTest {
            val messages = mutableListOf<String>()
            val writer =
                object : LogWriter() {
                    override fun log(
                        severity: Severity,
                        message: String,
                        tag: String,
                        throwable: Throwable?,
                    ) {
                        if (tag == DiagnosticTag.LocalSubtitleSync.wireValue) {
                            messages += message
                            assertEquals(null, throwable)
                        }
                    }
                }
            var firstRead = true
            val asset = localAsset("primary", LocalSubtitleSyncState.Pending)
            val assetStore =
                FakeLocalSubtitleAssetStore(asset) {
                    if (firstRead) {
                        firstRead = false
                        throw IllegalStateException("private subtitle path")
                    }
                }
            val mutationCoordinator =
                LocalSubtitleMutationCoordinator(
                    assetStore = assetStore,
                    fileStore = FakeLocalSubtitleFileStore(asset.fileId, WEB_VTT.encodeToByteArray()),
                    selectionStore = ReconcileSelectionStore(),
                    scope = backgroundScope,
                )
            val sessionRepository = StartupSessionRepository().also { it.publishRestored(sessionFor(asset)) }
            val syncRepository = RecordingLocalSubtitleSyncRepository()
            try {
                Logger.setLogWriters(writer)
                LocalSubtitleSyncCoordinator(
                    sessionRepository = sessionRepository,
                    assetStore = assetStore,
                    storageReconciler = LocalSubtitleStorageReconciler(mutationCoordinator),
                    syncRepository = syncRepository,
                    scope = backgroundScope,
                )
                runCurrent()
            } finally {
                Logger.setLogWriters(emptyList())
            }

            assertEquals(listOf(asset.id), syncRepository.syncedAssetIds)
            assertEquals(1, assetStore.pendingObservationCount)
            val message = messages.single { it.contains("stage=storage-reconcile") }
            assertEquals(
                "stage=storage-reconcile event=failed operation=localSubtitleRefresh exceptionType=IllegalStateException",
                message,
            )
            assertFalse(message.contains("private subtitle path"))
        }

    @Test
    fun reconciliationCancellationStopsBeforeStartupObservation() =
        runTest {
            val asset = localAsset("primary", LocalSubtitleSyncState.Pending)
            val assetStore =
                FakeLocalSubtitleAssetStore(asset) {
                    throw CancellationException("reconciliation cancelled")
                }
            val mutationCoordinator =
                LocalSubtitleMutationCoordinator(
                    assetStore = assetStore,
                    fileStore = FakeLocalSubtitleFileStore(asset.fileId, WEB_VTT.encodeToByteArray()),
                    selectionStore = ReconcileSelectionStore(),
                    scope = backgroundScope,
                )
            val sessionRepository = StartupSessionRepository().also { it.publishRestored(sessionFor(asset)) }
            val syncRepository = RecordingLocalSubtitleSyncRepository()
            LocalSubtitleSyncCoordinator(
                sessionRepository = sessionRepository,
                assetStore = assetStore,
                storageReconciler = LocalSubtitleStorageReconciler(mutationCoordinator),
                syncRepository = syncRepository,
                scope = backgroundScope,
            )

            runCurrent()

            assertEquals(emptyList(), syncRepository.syncedAssetIds)
            assertEquals(0, assetStore.pendingObservationCount)
        }

    @Test
    fun restoredUploadedUnconfirmedSnapshotReconcilesOnceWithoutPostingOrJoiningTheLiveLoop() =
        runTest {
            val asset = localAsset("primary", LocalSubtitleSyncState.UploadedUnconfirmed)
            val assetStore = FakeLocalSubtitleAssetStore(asset)
            val fileStore = FakeLocalSubtitleFileStore(asset.fileId, WEB_VTT.encodeToByteArray())
            val mutationCoordinator =
                LocalSubtitleMutationCoordinator(
                    assetStore = assetStore,
                    fileStore = fileStore,
                    selectionStore = ReconcileSelectionStore(),
                    scope = backgroundScope,
                )
            val api =
                FakeLocalSubtitleSyncApi(
                    sourceIds = listOf("primary"),
                    canManageSubtitles = true,
                    hasServerCopy = false,
                    serverCopyAfterUpload = false,
                    uploadFailure = null,
                    remoteSubtitleText = WEB_VTT,
                )
            val repository =
                CountingLocalSubtitleSyncRepository(
                    DefaultLocalSubtitleSyncRepository(
                        api = api,
                        mutationCoordinator = mutationCoordinator,
                        workerDispatcher = Dispatchers.Unconfined,
                        reconciliationAttempts = 1,
                        reconciliationDelayMs = 0,
                    ),
                )
            val sessionRepository = StartupSessionRepository().also { it.publishRestored(sessionFor(asset)) }
            LocalSubtitleSyncCoordinator(
                sessionRepository = sessionRepository,
                assetStore = assetStore,
                storageReconciler = LocalSubtitleStorageReconciler(mutationCoordinator),
                syncRepository = repository,
                scope = backgroundScope,
            )

            runCurrent()

            assertEquals(1, repository.syncCalls)
            assertEquals(0, api.uploadCalls)
            assertEquals(LocalSubtitleSyncState.UploadedUnconfirmed, assetStore.current.syncState)
            assertEquals(1, assetStore.pendingObservationCount)
            val detailCalls = api.itemDetailCalls

            runCurrent()

            assertEquals(1, repository.syncCalls)
            assertEquals(detailCalls, api.itemDetailCalls)
            assertEquals(0, api.uploadCalls)
        }
}

private data class SyncFixture(
    val repository: DefaultLocalSubtitleSyncRepository,
    val api: FakeLocalSubtitleSyncApi,
    val store: FakeLocalSubtitleAssetStore,
    val session: Session,
    val asset: LocalSubtitleAsset,
)

private fun TestScope.fixture(
    sourceIds: List<String> = listOf("primary"),
    assetSourceId: String = sourceIds.first(),
    canManageSubtitles: Boolean = true,
    initialServerCopy: Boolean = false,
    serverCopyAfterUpload: Boolean = false,
    uploadFailure: Throwable? = null,
    initialState: LocalSubtitleSyncState = LocalSubtitleSyncState.Pending,
    workerDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    cpuOperations: LocalSubtitleSyncCpuOperations = DefaultLocalSubtitleSyncCpuOperations,
    localBytes: ByteArray = WEB_VTT.encodeToByteArray(),
    remoteSubtitleText: String = WEB_VTT,
): SyncFixture {
    val asset = localAsset(assetSourceId, initialState)
    val store = FakeLocalSubtitleAssetStore(asset)
    val api =
        FakeLocalSubtitleSyncApi(
            sourceIds = sourceIds,
            canManageSubtitles = canManageSubtitles,
            hasServerCopy = initialServerCopy,
            serverCopyAfterUpload = serverCopyAfterUpload,
            uploadFailure = uploadFailure,
            remoteSubtitleText = remoteSubtitleText,
        )
    val fileStore = FakeLocalSubtitleFileStore(asset.fileId, localBytes)
    val mutationCoordinator =
        LocalSubtitleMutationCoordinator(
            assetStore = store,
            fileStore = fileStore,
            selectionStore = ReconcileSelectionStore(),
            scope = backgroundScope,
        )
    return SyncFixture(
        repository =
            DefaultLocalSubtitleSyncRepository(
                api = api,
                mutationCoordinator = mutationCoordinator,
                workerDispatcher = workerDispatcher,
                reconciliationAttempts = 1,
                reconciliationDelayMs = 0,
                cpuOperations = cpuOperations,
            ),
        api = api,
        store = store,
        session = sessionFor(asset),
        asset = asset,
    )
}

private class FakeLocalSubtitleSyncApi(
    private val sourceIds: List<String>,
    private val canManageSubtitles: Boolean,
    private var hasServerCopy: Boolean,
    private val serverCopyAfterUpload: Boolean,
    private val uploadFailure: Throwable?,
    private val remoteSubtitleText: String,
) : LocalSubtitleSyncApi {
    var uploadCalls = 0
    var itemDetailCalls = 0
    var subtitleTextCalls = 0
    var uploadedData: String? = null

    override suspend fun getCurrentUser(context: AuthenticatedRequestContext): UserDto =
        UserDto("user", "User", UserPolicyDto(enableSubtitleManagement = canManageSubtitles))

    override suspend fun getItemDetail(
        context: AuthenticatedRequestContext,
        itemId: String,
    ): BaseItemDto {
        itemDetailCalls += 1
        return BaseItemDto(
            id = itemId,
            mediaSources =
                sourceIds.map { sourceId ->
                    MediaSourceDto(
                        id = sourceId,
                        mediaStreams =
                            if (hasServerCopy && sourceId == sourceIds.first()) {
                                listOf(
                                    MediaStreamDto(
                                        type = "Subtitle",
                                        language = "en",
                                        index = 7,
                                        isForced = false,
                                        isHearingImpaired = false,
                                    ),
                                )
                            } else {
                                emptyList()
                            },
                    )
                },
        )
    }

    override suspend fun uploadSubtitle(
        context: AuthenticatedRequestContext,
        itemId: String,
        subtitle: UploadSubtitleDto,
    ) {
        uploadCalls += 1
        uploadedData = subtitle.data
        uploadFailure?.let { failure -> throw failure }
        if (serverCopyAfterUpload) hasServerCopy = true
    }

    override suspend fun getSubtitleText(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
        streamIndex: Int,
    ): String {
        subtitleTextCalls += 1
        return remoteSubtitleText
    }
}

private class FakeLocalSubtitleAssetStore(
    asset: LocalSubtitleAsset,
    private val beforeAll: suspend () -> Unit = {},
) : LocalSubtitleAssetStore {
    private val assets = MutableStateFlow(listOf(asset))
    var pendingObservationCount = 0
    val current: LocalSubtitleAsset get() = assets.value.single()

    override fun observe(context: LocalSubtitleContext): Flow<List<LocalSubtitleAsset>> =
        assets.map { values -> values.filter { it.context == context } }

    override fun observePendingSync(): Flow<List<LocalSubtitleAsset>> {
        pendingObservationCount += 1
        return assets.map { values ->
            values.filter { asset ->
                asset.syncState == LocalSubtitleSyncState.Pending ||
                    asset.syncState == LocalSubtitleSyncState.Uploading ||
                    asset.syncState == LocalSubtitleSyncState.Reconciling
            }
        }
    }

    override suspend fun get(assetId: String): LocalSubtitleAsset? = assets.value.firstOrNull { it.id == assetId }

    override suspend fun findByProviderFile(
        context: LocalSubtitleContext,
        provider: String,
        providerFileId: String,
    ): LocalSubtitleAsset? =
        assets.value.firstOrNull {
            it.context == context &&
                it.provider == provider &&
                it.providerFileId == providerFileId
        }

    override suspend fun upsert(asset: LocalSubtitleAsset) {
        assets.value = assets.value.filterNot { it.id == asset.id } + asset
    }

    override suspend fun delete(assetId: String) {
        assets.value = assets.value.filterNot { it.id == assetId }
    }

    override suspend fun all(): List<LocalSubtitleAsset> {
        beforeAll()
        return assets.value
    }

    override suspend fun clearAll() {
        assets.value = emptyList()
    }
}

private class FakeLocalSubtitleFileStore(
    private val fileId: String,
    private val bytes: ByteArray,
) : LocalSubtitleFileStore {
    override suspend fun writeAtomically(
        fileId: String,
        bytes: ByteArray,
    ) = Unit

    override suspend fun read(fileId: String): ByteArray? = bytes.takeIf { fileId == this.fileId }

    override suspend fun exists(fileId: String): Boolean = fileId == this.fileId

    override suspend fun delete(fileId: String) = Unit

    override suspend fun listFileIds(): Set<String> = setOf(fileId)

    override fun resolvePath(fileId: String): String? = fileId.takeIf { fileId == this.fileId }
}

private class ReconcileFileStore(
    private val files: MutableSet<String>,
) : LocalSubtitleFileStore {
    val deleted = mutableSetOf<String>()

    override suspend fun writeAtomically(
        fileId: String,
        bytes: ByteArray,
    ) {
        files += fileId
    }

    override suspend fun read(fileId: String): ByteArray? = null

    override suspend fun exists(fileId: String): Boolean = fileId in files

    override suspend fun delete(fileId: String) {
        files -= fileId
        deleted += fileId
    }

    override suspend fun listFileIds(): Set<String> = files

    override fun resolvePath(fileId: String): String? = null
}

private class ReconcileSelectionStore : SubtitleSelectionStore {
    private val values = mutableMapOf<SubtitleSelectionKey, SubtitleSelectionIntent>()

    override suspend fun get(key: SubtitleSelectionKey): SubtitleSelectionIntent? = values[key]

    override suspend fun save(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    ) {
        values[key] = selection
    }

    override suspend fun delete(key: SubtitleSelectionKey) {
        values.remove(key)
    }

    override suspend fun clearServerScoped(serverId: String) {
        values.keys.removeAll { it.serverId == serverId }
    }

    override suspend fun clearServerScoped() {
        values.clear()
    }
}

private val LocalSubtitleAsset.context: LocalSubtitleContext
    get() = LocalSubtitleContext(serverId, userId, itemId, mediaSourceId)

private fun sessionFor(asset: LocalSubtitleAsset) =
    Session(
        serverUrl = "https://jellyfin.example",
        serverId = asset.serverId,
        serverName = "Server",
        userId = asset.userId,
        userName = "User",
        accessToken = "token",
        deviceId = "device",
    )

private class StartupSessionRepository : SessionRepository {
    private val mutableSessionState = MutableStateFlow<SessionState>(SessionState.Restoring)
    override val sessionState = mutableSessionState

    fun publishRestored(session: Session) {
        mutableSessionState.value = SessionState.LoggedIn(session, boundaryEpoch = 1L)
    }

    override suspend fun setLoggedIn(session: Session) {
        publishRestored(session)
    }

    override suspend fun setLoggedOut(
        serverUrl: String?,
        authorization: com.jellyscope.core.domain.action.SessionRemovalAuthorization,
    ): Result<Unit> = Result.success(Unit)
}

private class RecordingLocalSubtitleSyncRepository : LocalSubtitleSyncRepository {
    val syncedAssetIds = mutableListOf<String>()

    override suspend fun sync(
        session: Session,
        asset: LocalSubtitleAsset,
    ) {
        syncedAssetIds += asset.id
    }

    override suspend fun retry(
        session: Session,
        asset: LocalSubtitleAsset,
    ) = error("Startup observation does not retry assets.")
}

private class CountingLocalSubtitleSyncRepository(
    private val delegate: LocalSubtitleSyncRepository,
) : LocalSubtitleSyncRepository {
    var syncCalls = 0

    override suspend fun sync(
        session: Session,
        asset: LocalSubtitleAsset,
    ) {
        syncCalls += 1
        delegate.sync(session, asset)
    }

    override suspend fun retry(
        session: Session,
        asset: LocalSubtitleAsset,
    ) = delegate.retry(session, asset)
}

private class RecordingSyncCpuOperations(
    private val dispatcher: QueuedSyncDispatcher,
) : LocalSubtitleSyncCpuOperations {
    val localWorkerContexts = mutableListOf<Boolean>()
    val remoteWorkerContexts = mutableListOf<Boolean>()
    val uploadWorkerContexts = mutableListOf<Boolean>()

    override fun canonicalizeLocal(bytes: ByteArray): String {
        localWorkerContexts += dispatcher.isRunning
        return DefaultLocalSubtitleSyncCpuOperations.canonicalizeLocal(bytes)
    }

    override fun canonicalizeRemote(body: String): String {
        remoteWorkerContexts += dispatcher.isRunning
        return DefaultLocalSubtitleSyncCpuOperations.canonicalizeRemote(body)
    }

    override fun encodeUpload(bytes: ByteArray): String {
        uploadWorkerContexts += dispatcher.isRunning
        return DefaultLocalSubtitleSyncCpuOperations.encodeUpload(bytes)
    }
}

private class QueuedSyncDispatcher : CoroutineDispatcher() {
    private val pending = ArrayDeque<Runnable>()
    var isRunning = false
        private set

    val pendingCount: Int
        get() = pending.size

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        pending.addLast(block)
    }

    fun runNext() {
        isRunning = true
        try {
            pending.removeFirst().run()
        } finally {
            isRunning = false
        }
    }
}

private fun localAsset(
    sourceId: String,
    state: LocalSubtitleSyncState,
) = LocalSubtitleAsset(
    id = "asset",
    serverId = "server",
    userId = "user",
    itemId = "item",
    mediaSourceId = sourceId,
    provider = "OpenSubtitles",
    providerSubtitleId = "subtitle",
    providerFileId = "file",
    language = "en",
    label = "English",
    releaseName = "Movie.1080p",
    originalFormat = "srt",
    mimeType = "text/vtt",
    fileId = "asset.vtt",
    hearingImpaired = false,
    forced = false,
    trusted = true,
    createdAtEpochMs = 1,
    lastUsedAtEpochMs = 1,
    syncState = state,
)

private const val WEB_VTT = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n"
