// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.local.SubtitleSelectionKey
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
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.LogScrubber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
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
    fun startupReconciliationRemovesMissingRowsSelectionsAndOrphanFiles() =
        runTest {
            val asset = localAsset("primary", LocalSubtitleSyncState.Pending)
            val assetStore = FakeLocalSubtitleAssetStore(asset)
            val fileStore = ReconcileFileStore(mutableSetOf("orphan.vtt"))
            val selectionStore = ReconcileSelectionStore()
            val key = SubtitleSelectionKey(asset.serverId, asset.userId, asset.itemId, asset.mediaSourceId)
            selectionStore.save(key, SubtitleSelectionIntent.LocalAsset(asset.id))
            val reconciler = LocalSubtitleStorageReconciler(assetStore, fileStore, selectionStore, backgroundScope)

            reconciler.reconcile()

            assertEquals(null, assetStore.get(asset.id))
            assertEquals(null, selectionStore.get(key))
            assertEquals(setOf("orphan.vtt"), fileStore.deleted)
        }
}

private data class SyncFixture(
    val repository: DefaultLocalSubtitleSyncRepository,
    val api: FakeLocalSubtitleSyncApi,
    val store: FakeLocalSubtitleAssetStore,
    val session: Session,
    val asset: LocalSubtitleAsset,
)

private fun fixture(
    sourceIds: List<String> = listOf("primary"),
    assetSourceId: String = sourceIds.first(),
    canManageSubtitles: Boolean = true,
    initialServerCopy: Boolean = false,
    serverCopyAfterUpload: Boolean = false,
    uploadFailure: Throwable? = null,
    initialState: LocalSubtitleSyncState = LocalSubtitleSyncState.Pending,
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
        )
    return SyncFixture(
        repository =
            DefaultLocalSubtitleSyncRepository(
                api = api,
                assetStore = store,
                fileStore = FakeLocalSubtitleFileStore(asset.fileId, WEB_VTT.encodeToByteArray()),
                reconciliationAttempts = 1,
                reconciliationDelayMs = 0,
            ),
        api = api,
        store = store,
        session =
            Session(
                serverUrl = "https://jellyfin.example",
                serverId = asset.serverId,
                serverName = "Server",
                userId = asset.userId,
                userName = "User",
                accessToken = "token",
                deviceId = "device",
            ),
        asset = asset,
    )
}

private class FakeLocalSubtitleSyncApi(
    private val sourceIds: List<String>,
    private val canManageSubtitles: Boolean,
    private var hasServerCopy: Boolean,
    private val serverCopyAfterUpload: Boolean,
    private val uploadFailure: Throwable?,
) : LocalSubtitleSyncApi {
    var uploadCalls = 0

    override suspend fun getCurrentUser(context: AuthenticatedRequestContext): UserDto =
        UserDto("user", "User", UserPolicyDto(enableSubtitleManagement = canManageSubtitles))

    override suspend fun getItemDetail(
        context: AuthenticatedRequestContext,
        itemId: String,
    ): BaseItemDto =
        BaseItemDto(
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

    override suspend fun uploadSubtitle(
        context: AuthenticatedRequestContext,
        itemId: String,
        subtitle: UploadSubtitleDto,
    ) {
        uploadCalls += 1
        uploadFailure?.let { failure -> throw failure }
        if (serverCopyAfterUpload) hasServerCopy = true
    }

    override suspend fun getSubtitleText(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
        streamIndex: Int,
    ): String = WEB_VTT
}

private class FakeLocalSubtitleAssetStore(
    asset: LocalSubtitleAsset,
) : LocalSubtitleAssetStore {
    private val assets = MutableStateFlow(listOf(asset))
    val current: LocalSubtitleAsset get() = assets.value.single()

    override fun observe(context: LocalSubtitleContext): Flow<List<LocalSubtitleAsset>> =
        assets.map { values -> values.filter { it.context == context } }

    override fun observePendingSync(): Flow<List<LocalSubtitleAsset>> = assets

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

    override suspend fun all(): List<LocalSubtitleAsset> = assets.value

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
