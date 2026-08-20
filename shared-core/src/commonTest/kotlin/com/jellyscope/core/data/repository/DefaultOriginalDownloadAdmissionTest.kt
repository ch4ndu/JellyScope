// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.remote.AuthHeaderProvider
import com.jellyscope.core.data.remote.JellyfinClientFactory
import com.jellyscope.core.data.remote.KtorJellyfinApi
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadAdmissionDecision
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadBusinessKey
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.OfflineMediaSnapshot
import com.jellyscope.core.domain.model.OriginalDownloadDraft
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultOriginalDownloadAdmissionTest {
    @Test
    fun disabledSessionRejectsBeforePreflightOrEnqueue() =
        runTest {
            val client =
                JellyfinClientFactory().createDownloadTransfer(
                    MockEngine { error("Original admission must not reach the API") },
                )
            val api =
                KtorJellyfinApi(
                    client = client,
                    downloadClient = client,
                    authHeaderProvider =
                        object : AuthHeaderProvider {
                            override suspend fun authHeader(token: String?): String = "unused"
                        },
                )
            try {
                val registry = ServerScopedStoreRegistry()
                registry.transitionToAccount(AccountIdentity("server", "user"), boundaryEpoch = 1L)
                val admission =
                    DefaultOriginalDownloadAdmission(
                        sessionRepository = DisabledAdmissionSessionRepository,
                        serverScopedStoreRegistry = registry,
                        jellyfinApi = api,
                        localSubtitleAssetStore = EmptyLocalSubtitleAssetStore,
                        localSubtitleFileStore = EmptyLocalSubtitleFileStore,
                    )
                var enqueueCalls = 0

                val result =
                    admission.admitAndEnqueue(originalDraft()) {
                        enqueueCalls += 1
                        DownloadEnqueueResult.RemovalInProgress
                    }

                assertEquals(
                    DownloadEnqueueResult.Rejected(DownloadAdmissionDecision.PermissionDenied),
                    result,
                )
                assertEquals(0, enqueueCalls)
            } finally {
                client.close()
            }
        }

    private fun originalDraft() =
        OriginalDownloadDraft(
            downloadId = DownloadId("download-original"),
            businessKey = DownloadBusinessKey(AccountIdentity("server", "user"), "item", "source"),
            selectedAudioStreamIndex = null,
            subtitleSelection = DownloadSubtitleSelection.Off,
            artifactKey = DownloadArtifactKey("artifact-original"),
            snapshot =
                OfflineMediaSnapshot(
                    title = "Movie",
                    itemKind = MediaKind.Movie,
                    backendSource = BackendSourceDescriptor("mkv", "h264", "aac", false),
                ),
            createdAtEpochMs = 1L,
        )
}

private object DisabledAdmissionSessionRepository : SessionRepository {
    private val state =
        MutableStateFlow<SessionState>(
            SessionState.LoggedIn(
                session =
                    Session(
                        serverUrl = "https://server.example",
                        serverId = "server",
                        serverName = "Server",
                        userId = "user",
                        userName = "User",
                        accessToken = "token",
                        deviceId = "device",
                    ),
                boundaryEpoch = 1L,
            ),
        )

    override val sessionState: StateFlow<SessionState> = state

    override suspend fun setLoggedIn(session: Session) {
        state.value = SessionState.LoggedIn(session, boundaryEpoch = 1L)
    }

    override suspend fun setLoggedOut(
        serverUrl: String?,
        authorization: SessionRemovalAuthorization,
    ): Result<Unit> {
        state.value = SessionState.LoggedOut(serverUrl)
        return Result.success(Unit)
    }
}

private object EmptyLocalSubtitleAssetStore : LocalSubtitleAssetStore {
    override fun observe(context: LocalSubtitleContext): Flow<List<LocalSubtitleAsset>> = emptyFlow()

    override fun observePendingSync(): Flow<List<LocalSubtitleAsset>> = emptyFlow()

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
