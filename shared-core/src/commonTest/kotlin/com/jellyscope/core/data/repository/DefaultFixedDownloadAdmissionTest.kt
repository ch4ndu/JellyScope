// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.remote.AuthenticatedRequestContext
import com.jellyscope.core.data.remote.FixedDownloadCleanupResult
import com.jellyscope.core.data.remote.FixedDownloadPreflightResult
import com.jellyscope.core.data.remote.FixedDownloadRequest
import com.jellyscope.core.data.remote.ItemsQuery
import com.jellyscope.core.data.remote.JellyfinApi
import com.jellyscope.core.data.remote.PlaybackDeviceProfileDto
import com.jellyscope.core.domain.action.SessionRemovalAuthorization
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadAdmissionDecision
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadBusinessKey
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.FixedDownloadDraft
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.OfflineMediaSnapshot
import com.jellyscope.core.domain.model.OfflineTrackKind
import com.jellyscope.core.domain.model.OfflineTrackSnapshot
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import com.jellyscope.core.domain.playback.EffectivePlayerDevicePolicy
import com.jellyscope.core.domain.playback.PlaybackInfoRequestPolicy
import com.jellyscope.core.domain.playback.qualityRungForBitrate
import com.jellyscope.core.domain.usecase.FixedDownloadAdmissionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DefaultFixedDownloadAdmissionTest {
    @Test
    fun disabledSessionRejectsBeforePreflightLeaseOrEnqueue() =
        runTest {
            val source = fixedSource()
            val api = RecordingFixedDownloadApi(FixedDownloadPreflightResult.Ready(source))
            val admission = admission(api, enableContentDownloading = false)
            var enqueueCalls = 0

            val result =
                admission.admitAndEnqueue(draft()) {
                    enqueueCalls += 1
                    DownloadEnqueueResult.RemovalInProgress
                }

            assertEquals(DownloadEnqueueResult.Rejected(DownloadAdmissionDecision.PermissionDenied), result)
            assertEquals(0, api.preflightCalls)
            assertEquals(0, api.stoppedSources.size)
            assertEquals(0, enqueueCalls)
        }

    @Test
    fun successfulPreviewStopsItsEphemeralEncodingOnceAndUsesTrustedSnapshotFacts() =
        runTest {
            val source = fixedSource()
            val api = RecordingFixedDownloadApi(FixedDownloadPreflightResult.Ready(source))
            val admission = admission(api)

            val result = assertIs<FixedDownloadAdmissionResult.Ready>(admission.admit(draft()))

            assertEquals(listOf(source), api.stoppedSources)
            assertEquals(source.durationMs, result.request.snapshot.durationMs)
            assertEquals("hls", result.request.snapshot.backendSource.container)
            assertEquals("h264", result.request.snapshot.backendSource.videoCodec)
            assertEquals("aac", result.request.snapshot.backendSource.audioCodec)
            assertEquals(false, result.request.snapshot.backendSource.isHdrOrDolbyVision)
            assertEquals(1, result.request.snapshot.embeddedTracks.size)
            assertEquals(
                OfflineTrackKind.Audio,
                result.request.snapshot.embeddedTracks
                    .single()
                    .kind,
            )
            assertEquals(
                source.audioStreamIndex,
                result.request.snapshot.selectedAudioTrack
                    ?.streamIndex,
            )
            assertEquals(null, result.request.snapshot.selectedSubtitleTrack)
        }

    @Test
    fun sourceMismatchStopsItsEphemeralEncodingOnceBeforeReturningRejection() =
        runTest {
            val source = fixedSource(itemId = "different-item")
            val api = RecordingFixedDownloadApi(FixedDownloadPreflightResult.Ready(source))
            val admission = admission(api)

            val result = assertIs<FixedDownloadAdmissionResult.Rejected>(admission.admit(draft()))

            assertEquals(DownloadAdmissionDecision.SourceChanged, result.decision)
            assertEquals(listOf(source), api.stoppedSources)
        }

    @Test
    fun enqueueRejectionStopsItsEphemeralEncodingOnceAfterAuthoritativeOutcome() =
        runTest {
            val source = fixedSource()
            val api = RecordingFixedDownloadApi(FixedDownloadPreflightResult.Ready(source))
            val admission = admission(api)
            val enqueueResult = DownloadEnqueueResult.Rejected(DownloadAdmissionDecision.NetworkUnavailable)

            val result = admission.admitAndEnqueue(draft()) { enqueueResult }

            assertEquals(enqueueResult, result)
            assertEquals(listOf(source), api.stoppedSources)
        }

    @Test
    fun staleGuardedLeaseStopsItsEphemeralEncodingOnceWithoutCallingEnqueue() =
        runTest {
            val account = AccountIdentity("server", "user")
            val registry = ServerScopedStoreRegistry()
            registry.transitionToAccount(account, boundaryEpoch = 4L)
            val source = fixedSource()
            val api =
                RecordingFixedDownloadApi(FixedDownloadPreflightResult.Ready(source)) {
                    registry.transitionToAccount(AccountIdentity("other-server", "other-user"), boundaryEpoch = 1L)
                }
            val admission =
                DefaultFixedDownloadAdmission(
                    sessionRepository = FixedAdmissionSessionRepository(account, enableContentDownloading = true),
                    serverScopedStoreRegistry = registry,
                    jellyfinApi = api,
                )
            var enqueueCalls = 0

            val result =
                admission.admitAndEnqueue(draft()) {
                    enqueueCalls += 1
                    DownloadEnqueueResult.RemovalInProgress
                }

            assertEquals(DownloadEnqueueResult.Rejected(DownloadAdmissionDecision.SourceChanged), result)
            assertEquals(0, enqueueCalls)
            assertEquals(listOf(source), api.stoppedSources)
        }

    @Test
    fun enqueueCancellationPreservesCancellationAndStopsItsEphemeralEncodingOnce() =
        runTest {
            val source = fixedSource()
            val api = RecordingFixedDownloadApi(FixedDownloadPreflightResult.Ready(source))
            val admission = admission(api)

            assertFailsWith<CancellationException> {
                admission.admitAndEnqueue(draft()) {
                    throw CancellationException("enqueue cancelled")
                }
            }

            assertEquals(listOf(source), api.stoppedSources)
        }

    private suspend fun admission(
        api: JellyfinApi,
        enableContentDownloading: Boolean = true,
    ): DefaultFixedDownloadAdmission {
        val account = AccountIdentity("server", "user")
        val registry = ServerScopedStoreRegistry()
        registry.transitionToAccount(account, boundaryEpoch = 4L)
        return DefaultFixedDownloadAdmission(
            sessionRepository = FixedAdmissionSessionRepository(account, enableContentDownloading),
            serverScopedStoreRegistry = registry,
            jellyfinApi = api,
        )
    }

    private fun draft(): FixedDownloadDraft =
        FixedDownloadDraft(
            downloadId = DownloadId("download-fixed"),
            businessKey = DownloadBusinessKey(AccountIdentity("server", "user"), "item", "source"),
            quality = DownloadQuality.Fixed(4_000_000L),
            selectedAudioStreamIndex = 1,
            subtitleSelection = DownloadSubtitleSelection.Off,
            artifactKey = DownloadArtifactKey("artifact-fixed"),
            snapshot =
                OfflineMediaSnapshot(
                    title = "Movie",
                    itemKind = MediaKind.Movie,
                    durationMs = 1_000L,
                    embeddedTracks =
                        listOf(
                            OfflineTrackSnapshot(
                                kind = OfflineTrackKind.Audio,
                                streamIndex = 1,
                                codec = "eac3",
                                language = "en",
                                label = "English",
                                isDefault = true,
                                isExternal = false,
                            ),
                            OfflineTrackSnapshot(
                                kind = OfflineTrackKind.Subtitle,
                                streamIndex = 2,
                                codec = "srt",
                                language = "en",
                                label = "English",
                                isDefault = false,
                                isExternal = false,
                            ),
                        ),
                    selectedAudioTrack =
                        OfflineTrackSnapshot(
                            kind = OfflineTrackKind.Audio,
                            streamIndex = 1,
                            codec = "eac3",
                            language = "en",
                            label = "English",
                            isDefault = true,
                            isExternal = false,
                        ),
                    selectedSubtitleTrack =
                        OfflineTrackSnapshot(
                            kind = OfflineTrackKind.Subtitle,
                            streamIndex = 2,
                            codec = "srt",
                            language = "en",
                            label = "English",
                            isDefault = false,
                            isExternal = false,
                        ),
                    backendSource = BackendSourceDescriptor("mkv", "hevc", "eac3", true),
                ),
            createdAtEpochMs = 1L,
        )

    private fun fixedSource(itemId: String = "item"): com.jellyscope.core.data.remote.FixedDownloadSource =
        com.jellyscope.core.data.remote.FixedDownloadSource(
            itemId = itemId,
            mediaSourceId = "source",
            quality = qualityRungForBitrate(4_000_000L) ?: error("missing canonical test rung"),
            audioStreamIndex = 1,
            subtitleStreamIndex = null,
            durationMs = 8_000L,
            estimatedBytes = 2_000L,
            transcodingUrl = "https://server.example/videos/item/master.m3u8",
            deviceId = "device",
            playSessionId = "play-session",
        )
}

private class FixedAdmissionSessionRepository(
    account: AccountIdentity,
    enableContentDownloading: Boolean,
) : SessionRepository {
    private val state =
        MutableStateFlow<SessionState>(
            SessionState.LoggedIn(
                session =
                    Session(
                        serverUrl = "https://server.example",
                        serverId = account.serverId,
                        serverName = "Server",
                        userId = account.userId,
                        userName = "User",
                        accessToken = "token",
                        deviceId = "device",
                        enableContentDownloading = enableContentDownloading,
                    ),
                boundaryEpoch = 4L,
            ),
        )
    override val sessionState: StateFlow<SessionState> = state

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

private class RecordingFixedDownloadApi(
    private val preflightResult: FixedDownloadPreflightResult,
    private val beforePreflightReturn: (suspend () -> Unit)? = null,
) : JellyfinApi {
    var preflightCalls = 0
    val stoppedSources = mutableListOf<com.jellyscope.core.data.remote.FixedDownloadSource>()

    override suspend fun preflightFixedDownload(
        context: AuthenticatedRequestContext,
        request: FixedDownloadRequest,
    ): FixedDownloadPreflightResult {
        preflightCalls += 1
        beforePreflightReturn?.invoke()
        return preflightResult
    }

    override suspend fun stopFixedDownloadEncoding(
        context: AuthenticatedRequestContext,
        source: com.jellyscope.core.data.remote.FixedDownloadSource,
    ): FixedDownloadCleanupResult {
        stoppedSources += source
        return FixedDownloadCleanupResult.Stopped
    }

    override suspend fun getPublicSystemInfo(serverUrl: String) = error("unused")

    override suspend fun authenticateByName(
        serverUrl: String,
        username: String,
        password: String,
    ) = error("unused")

    override suspend fun initiateQuickConnect(serverUrl: String) = error("unused")

    override suspend fun getQuickConnectState(
        serverUrl: String,
        secret: String,
    ) = error("unused")

    override suspend fun authenticateWithQuickConnect(
        serverUrl: String,
        secret: String,
    ) = error("unused")

    override suspend fun getUserViews(context: AuthenticatedRequestContext) = error("unused")

    override suspend fun getResumeItems(
        context: AuthenticatedRequestContext,
        limit: Int,
        fields: List<String>,
        parentId: String?,
        includeItemTypes: List<String>,
    ) = error("unused")

    override suspend fun getItemDetail(
        context: AuthenticatedRequestContext,
        itemId: String,
        includePlaybackFields: Boolean,
    ) = error("unused")

    override suspend fun getSimilarItems(
        context: AuthenticatedRequestContext,
        itemId: String,
        limit: Int,
    ) = error("unused")

    override suspend fun getSeasons(
        context: AuthenticatedRequestContext,
        seriesId: String,
    ) = error("unused")

    override suspend fun getEpisodes(
        context: AuthenticatedRequestContext,
        seriesId: String,
        seasonId: String,
        seasonIndex: Int?,
    ) = error("unused")

    override suspend fun getNextUp(
        context: AuthenticatedRequestContext,
        seriesId: String?,
        limit: Int,
        fields: List<String>,
        parentId: String?,
    ) = error("unused")

    override suspend fun getUpcomingEpisodes(
        context: AuthenticatedRequestContext,
        limit: Int,
        fields: List<String>,
    ) = error("unused")

    override suspend fun getLatestItems(
        context: AuthenticatedRequestContext,
        limit: Int,
        fields: List<String>,
        parentId: String?,
        includeItemTypes: List<String>,
    ) = error("unused")

    override suspend fun getMovieRecommendations(
        context: AuthenticatedRequestContext,
        parentId: String,
        categoryLimit: Int,
        itemLimit: Int,
    ) = error("unused")

    override suspend fun getItems(
        context: AuthenticatedRequestContext,
        query: ItemsQuery,
    ) = error("unused")

    override suspend fun getGenres(
        context: AuthenticatedRequestContext,
        parentId: String?,
    ) = error("unused")

    override suspend fun getStudios(
        context: AuthenticatedRequestContext,
        parentId: String?,
    ) = error("unused")

    override suspend fun getLibraryFilterOptions(
        context: AuthenticatedRequestContext,
        parentId: String?,
    ) = error("unused")

    override suspend fun getMediaSegments(
        context: AuthenticatedRequestContext,
        itemId: String,
    ) = error("unused")

    override suspend fun getPersons(
        context: AuthenticatedRequestContext,
        searchTerm: String,
        limit: Int,
    ) = error("unused")

    override suspend fun getPlaybackInfo(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        deviceProfile: PlaybackDeviceProfileDto,
        playerDevicePolicy: EffectivePlayerDevicePolicy,
        requestPolicy: PlaybackInfoRequestPolicy,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
    ) = error("unused")

    override suspend fun reportPlaybackStart(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
        positionTicks: Long,
        playSessionId: String,
        playMethod: String,
    ) = error("unused")

    override suspend fun reportPlaybackProgress(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
        positionTicks: Long,
        playSessionId: String,
        playMethod: String,
        isPaused: Boolean,
        eventName: String,
    ) = error("unused")

    override suspend fun reportPlaybackStopped(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
        positionTicks: Long,
        playSessionId: String,
    ) = error("unused")

    override suspend fun markItemPlayed(
        context: AuthenticatedRequestContext,
        itemId: String,
    ) = error("unused")

    override suspend fun markItemUnplayed(
        context: AuthenticatedRequestContext,
        itemId: String,
    ) = error("unused")

    override suspend fun setItemFavorite(
        context: AuthenticatedRequestContext,
        itemId: String,
    ) = error("unused")

    override suspend fun unsetItemFavorite(
        context: AuthenticatedRequestContext,
        itemId: String,
    ) = error("unused")
}
