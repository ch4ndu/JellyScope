// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.data.local.PlaybackPreferencesStore
import com.jellyscope.core.data.local.SubtitleSelectionStore
import com.jellyscope.core.data.repository.AuthRepository
import com.jellyscope.core.data.repository.MediaRepository
import com.jellyscope.core.data.repository.SessionRepository
import com.jellyscope.core.domain.action.AuthError
import com.jellyscope.core.domain.action.SessionRemovalAuthorization
import com.jellyscope.core.domain.model.AccountSession
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.FindResults
import com.jellyscope.core.domain.model.ImageRefs
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.LibraryItemsRequest
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.MediaVersion
import com.jellyscope.core.domain.model.PagedItems
import com.jellyscope.core.domain.model.Person
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.QuickConnectLoginUpdate
import com.jellyscope.core.domain.model.SendClientLogsResult
import com.jellyscope.core.domain.model.ServerInfo
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.platform.DeviceInfoProvider
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackHealthMeasurementCapabilities
import com.jellyscope.core.domain.playback.PlaybackInfo
import com.jellyscope.core.domain.playback.PlaybackInfoRequestPolicy
import com.jellyscope.core.domain.playback.PlaybackMediaSourceInfo
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackProgressEvent
import com.jellyscope.core.domain.playback.PlaybackProgressReporter
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import com.jellyscope.core.domain.playback.SubtitleStyle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.cancellation.CancellationException

internal fun testSession(): Session =
    Session(
        serverUrl = "https://jellyfin.example",
        serverId = "server-1",
        serverName = "Test Server",
        userId = "user-1",
        userName = "user",
        accessToken = "token-1",
        deviceId = "device-1",
    )

internal fun testServerInfo(): ServerInfo =
    ServerInfo(
        serverUrl = "https://jellyfin.example",
        serverId = "server-1",
        serverName = "Test Server",
        version = "10.10.0",
        productName = "Jellyfin Server",
    )

internal fun testDispatchers(dispatcher: CoroutineDispatcher): TvosDispatchers = TvosDispatchers(main = dispatcher, work = dispatcher)

internal fun mediaItem(
    id: String,
    name: String = "Item $id",
    kind: MediaKind = MediaKind.Movie,
    primaryTag: String? = null,
    playedPercentage: Double? = null,
    playbackPositionTicks: Long? = null,
    seriesId: String? = null,
    seasonId: String? = null,
    indexNumber: Int? = null,
    played: Boolean = false,
): MediaItem =
    MediaItem(
        id = id,
        name = name,
        kind = kind,
        imageRefs = ImageRefs(primaryTag = primaryTag),
        playedPercentage = playedPercentage,
        playbackPositionTicks = playbackPositionTicks,
        seriesId = seriesId,
        seasonId = seasonId,
        indexNumber = indexNumber,
        played = played,
    )

internal fun playbackState(
    status: PlaybackStatus,
    positionMs: Long = 0L,
    error: PlaybackError? = null,
): PlaybackState =
    PlaybackState(
        status = status,
        positionMs = positionMs,
        durationMs = 60_000L,
        bufferedPositionMs = positionMs,
        error = error,
    )

internal val testStreams =
    listOf(
        PlaybackMediaStream(
            index = 0,
            type = "Video",
            displayTitle = "1080p",
            title = null,
            language = null,
            codec = "h264",
            channelLayout = null,
            bitRate = 17_100_000L,
            height = 1080,
            isDefault = null,
            isExternal = null,
            deliveryMethod = null,
            deliveryUrl = null,
        ),
        PlaybackMediaStream(
            index = 1,
            type = "Audio",
            displayTitle = "English",
            title = null,
            language = "eng",
            codec = "aac",
            channelLayout = "stereo",
            bitRate = 192_000L,
            height = null,
            isDefault = true,
            isExternal = null,
            deliveryMethod = null,
            deliveryUrl = null,
        ),
    )

internal val testSubtitleStream =
    PlaybackMediaStream(
        index = 2,
        type = "Subtitle",
        displayTitle = "English Subtitles",
        title = null,
        language = "eng",
        codec = "subrip",
        channelLayout = null,
        bitRate = null,
        height = null,
        isDefault = false,
        isExternal = false,
        deliveryMethod = null,
        deliveryUrl = null,
    )

internal val testStreamsWithSubtitle = testStreams + testSubtitleStream

internal fun List<PlaybackMediaStream>.withResponseSubtitleDelivery(method: String): List<PlaybackMediaStream> =
    map { stream ->
        if (stream.type.equals("Subtitle", ignoreCase = true) && stream.deliveryMethod == null) {
            stream.copy(deliveryMethod = method)
        } else {
            stream
        }
    }

internal fun directPlayInfo(
    playSessionId: String? = null,
    streams: List<PlaybackMediaStream> = testStreams,
): PlaybackInfo =
    PlaybackInfo(
        playSessionId = playSessionId,
        mediaSources =
            listOf(
                PlaybackMediaSourceInfo(
                    id = "source-1",
                    supportsDirectPlay = true,
                    supportsDirectStream = false,
                    supportsTranscoding = false,
                    transcodingUrl = null,
                    container = "mkv",
                    bitrate = 17_100_000L,
                    mediaStreams = streams,
                ),
            ),
    )

internal fun directStreamInfo(
    playSessionId: String? = null,
    streams: List<PlaybackMediaStream> = testStreams,
): PlaybackInfo =
    PlaybackInfo(
        playSessionId = playSessionId,
        mediaSources =
            listOf(
                PlaybackMediaSourceInfo(
                    id = "source-1",
                    supportsDirectPlay = false,
                    supportsDirectStream = true,
                    supportsTranscoding = false,
                    transcodingUrl = null,
                    container = "mkv",
                    transcodingContainer = "mp4",
                    bitrate = 17_100_000L,
                    mediaStreams = streams,
                ),
            ),
    )

internal fun transcodeInfo(
    playSessionId: String? = null,
    streams: List<PlaybackMediaStream> = testStreams,
): PlaybackInfo =
    PlaybackInfo(
        playSessionId = playSessionId,
        mediaSources =
            listOf(
                PlaybackMediaSourceInfo(
                    id = "source-1",
                    supportsDirectPlay = false,
                    supportsDirectStream = false,
                    supportsTranscoding = true,
                    transcodingUrl = "/Videos/item-1/master.m3u8",
                    container = "mkv",
                    bitrate = 17_100_000L,
                    mediaStreams = streams,
                ),
            ),
    )

internal fun testDetail(
    item: MediaItem = mediaItem(id = "item-1"),
    streams: List<PlaybackMediaStream> = testStreams,
    container: String? = null,
): MediaItemDetail =
    MediaItemDetail(
        item = item,
        versions =
            listOf(
                MediaVersion(
                    id = "source-1",
                    name = "1080p",
                    container = container,
                    mediaStreams = streams,
                ),
            ),
        chapters = item.let { emptyList() },
    )

internal data class RecordedPlanRequest(
    val itemId: String,
    val startTimeTicks: Long,
    val policy: PlaybackInfoRequestPolicy,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val maxStreamingBitrate: Long? = null,
)

internal class FakeTvMediaRepository(
    var continueWatching: Result<List<MediaItem>> = Result.success(emptyList()),
    var nextUp: Result<List<MediaItem>> = Result.success(emptyList()),
    var recentlyAdded: Result<List<MediaItem>> = Result.success(emptyList()),
    var libraries: Result<List<Library>> = Result.success(emptyList()),
    var detail: Result<MediaItemDetail> = Result.success(testDetail()),
    var detailsById: Map<String, Result<MediaItemDetail>> = emptyMap(),
    var seasons: Result<List<MediaItem>> = Result.success(emptyList()),
    var episodesBySeasonId: Map<String, List<MediaItem>> = emptyMap(),
    var episodesFailure: Boolean = false,
    var pageProvider: (LibraryItemsRequest) -> Result<PagedItems> = { request ->
        Result.success(PagedItems(items = emptyList(), totalCount = 0, startIndex = request.startIndex))
    },
    val playbackInfoQueue: ArrayDeque<Result<PlaybackInfo>> = ArrayDeque(),
    var defaultPlaybackInfo: Result<PlaybackInfo> = Result.success(directPlayInfo()),
    var mediaSegments: List<com.jellyscope.core.domain.playback.MediaSegment> = emptyList(),
    var favorites: Result<List<MediaItem>> = Result.success(emptyList()),
    var recommendationRowsByParent: Map<String, List<com.jellyscope.core.domain.model.LibraryRecommendationRow>> = emptyMap(),
    var relatedGroups: List<com.jellyscope.core.domain.model.RelatedGroup> = emptyList(),
    var setPlayedResult: Result<Unit> = Result.success(Unit),
    var setFavoriteResult: Result<Unit> = Result.success(Unit),
    var uploadClientLogsResult: SendClientLogsResult = SendClientLogsResult.Failure,
    var searchProvider: (FindQuery) -> Result<FindResults> = { Result.success(FindResults()) },
) : MediaRepository {
    val planRequests = mutableListOf<RecordedPlanRequest>()
    var subtitleFallbackGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val playedCalls = mutableListOf<Pair<String, Boolean>>()
    val favoriteCalls = mutableListOf<Pair<String, Boolean>>()
    val playedResponses = ArrayDeque<kotlinx.coroutines.CompletableDeferred<Result<Unit>>>()
    val favoriteResponses = ArrayDeque<kotlinx.coroutines.CompletableDeferred<Result<Unit>>>()
    var expectedUploadDispatcher: ContinuationInterceptor? = null
    var observedUploadDispatcher: ContinuationInterceptor? = null

    override suspend fun uploadClientLogs(content: String): SendClientLogsResult {
        observedUploadDispatcher = currentCoroutineContext()[ContinuationInterceptor]
        return uploadClientLogsResult
    }

    override suspend fun getLibraries(): Result<List<Library>> = libraries

    override suspend fun getContinueWatching(): Result<List<MediaItem>> = continueWatching

    override suspend fun getNextUp(seriesId: String?): Result<List<MediaItem>> = nextUp

    override suspend fun getRecentlyAdded(): Result<List<MediaItem>> = recentlyAdded

    override suspend fun getLibraryRecommendationSection(
        request: com.jellyscope.core.domain.model.LibraryRecommendationRequest,
    ): Result<List<com.jellyscope.core.domain.model.LibraryRecommendationRow>> =
        Result.success(recommendationRowsByParent[request.parentId].orEmpty())

    override fun getRelatedGroups(detail: MediaItemDetail): kotlinx.coroutines.flow.Flow<com.jellyscope.core.domain.model.RelatedGroup> =
        kotlinx.coroutines.flow.flow {
            relatedGroups.forEach { group -> emit(group) }
        }

    // When set, getItemDetail suspends until completed — lets tests hold a
    // startItem open to exercise the mid-switch window.
    var detailGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun getItemDetail(
        itemId: String,
        includePlaybackFields: Boolean,
    ): Result<MediaItemDetail> {
        detailGate?.await()
        return detailsById[itemId] ?: detail
    }

    override suspend fun getRelated(
        itemId: String,
        kind: MediaKind,
        seriesId: String?,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getSeasons(seriesId: String): Result<List<MediaItem>> = seasons

    override suspend fun getEpisodes(
        seriesId: String,
        seasonId: String,
        seasonIndex: Int?,
    ): Result<List<MediaItem>> =
        if (episodesFailure) {
            Result.failure(IllegalStateException("episodes unavailable"))
        } else {
            Result.success(episodesBySeasonId[seasonId].orEmpty())
        }

    override suspend fun getLibraryItems(request: LibraryItemsRequest): Result<PagedItems> = pageProvider(request)

    override suspend fun getRibbonItems(
        ribbon: MediaRibbon,
        limit: Int,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getFavorites(): Result<List<MediaItem>> = favorites

    override suspend fun search(query: FindQuery): Result<FindResults> = searchProvider(query)

    override suspend fun findPersons(term: String): Result<List<Person>> = Result.success(emptyList())

    override suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
    ): Result<PlaybackInfo> =
        getPlaybackInfo(
            itemId = itemId,
            mediaSourceId = mediaSourceId,
            startTimeTicks = startTimeTicks,
            audioStreamIndex = audioStreamIndex,
            subtitleStreamIndex = subtitleStreamIndex,
            maxStreamingBitrate = maxStreamingBitrate,
            requestPolicy = PlaybackInfoRequestPolicy(),
        )

    override suspend fun getMediaSegments(itemId: String): Result<List<com.jellyscope.core.domain.playback.MediaSegment>> =
        Result.success(mediaSegments)

    override suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
        requestPolicy: PlaybackInfoRequestPolicy,
    ): Result<PlaybackInfo> {
        planRequests +=
            RecordedPlanRequest(
                itemId = itemId,
                startTimeTicks = startTimeTicks,
                policy = requestPolicy,
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                maxStreamingBitrate = maxStreamingBitrate,
            )
        if (requestPolicy.forceEncodeSubtitle != null) {
            subtitleFallbackGate?.await()
        }
        return playbackInfoQueue.removeFirstOrNull() ?: defaultPlaybackInfo
    }

    override suspend fun setPlayed(
        itemId: String,
        played: Boolean,
    ): Result<Unit> {
        playedCalls += itemId to played
        return playedResponses.removeFirstOrNull()?.await() ?: setPlayedResult
    }

    override suspend fun setFavorite(
        itemId: String,
        favorite: Boolean,
    ): Result<Unit> {
        favoriteCalls += itemId to favorite
        return favoriteResponses.removeFirstOrNull()?.await() ?: setFavoriteResult
    }
}

internal class FakeTvPlayerController : PlayerController {
    val playbackStateFlow = MutableStateFlow(playbackState(PlaybackStatus.Idle))

    override val playbackState: StateFlow<PlaybackState> = playbackStateFlow
    override val runtimeDiagnostics: StateFlow<PlaybackRuntimeDiagnostics> =
        MutableStateFlow(PlaybackRuntimeDiagnostics.EMPTY)
    override val platformPlayer: Any? = null
    override var transcodeSeekRestartsStream: Boolean = false
    override var playbackHealthMeasurementCapabilities =
        PlaybackHealthMeasurementCapabilities.None

    val preparedPlans = mutableListOf<PlaybackPlan>()
    val seekPositions = mutableListOf<Long>()
    var playCount = 0
    var pauseCount = 0
    var stopCount = 0
    var releaseCount = 0
    var prepareFailure: Throwable? = null

    // Mirrors the Apple controller: prepare pushes a fresh state synchronously,
    // so a stale sample from the previous item never survives a re-prepare.
    override fun prepare(
        plan: PlaybackPlan,
        subtitleAsset: SubtitleAsset?,
    ) {
        preparedPlans += plan
        prepareFailure?.let { exception -> throw exception }
        playbackStateFlow.value =
            playbackStateFlow.value.copy(status = PlaybackStatus.Loading, positionMs = 0L, error = null)
    }

    var failOnNextPlay: com.jellyscope.core.domain.playback.PlaybackError? = null

    val embeddedAudioSelections = mutableListOf<com.jellyscope.core.domain.playback.EmbeddedAudioSelection>()
    val embeddedSubtitleSelections = mutableListOf<com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection?>()

    override fun selectEmbeddedAudio(selection: com.jellyscope.core.domain.playback.EmbeddedAudioSelection) {
        embeddedAudioSelections += selection
    }

    override fun selectEmbeddedSubtitle(selection: com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection?) {
        embeddedSubtitleSelections += selection
    }

    override fun setPlaybackSpeed(speed: Float) = Unit

    override fun setSubtitleStyle(style: SubtitleStyle) = Unit

    override fun play() {
        playCount += 1
        failOnNextPlay?.let { error ->
            failOnNextPlay = null
            playbackStateFlow.value = playbackStateFlow.value.copy(status = PlaybackStatus.Failed, error = error)
        }
    }

    override fun pause() {
        pauseCount += 1
    }

    override fun seekTo(positionMs: Long) {
        seekPositions += positionMs
    }

    override fun stop() {
        stopCount += 1
    }

    override fun retry() = Unit

    override fun release() {
        releaseCount += 1
    }
}

internal sealed interface TvReport {
    data class Start(
        val positionMs: Long,
        val playSessionId: String,
    ) : TvReport

    data class Progress(
        val positionMs: Long,
        val isPaused: Boolean,
        val eventName: PlaybackProgressEvent,
        val playSessionId: String,
    ) : TvReport

    data class Stopped(
        val positionMs: Long,
        val playSessionId: String,
    ) : TvReport
}

internal class RecordingProgressReporter : PlaybackProgressReporter {
    val reports = mutableListOf<TvReport>()
    var startAttempts = 0
    var failingStarts = 0

    override suspend fun reportStart(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
    ) {
        startAttempts += 1
        if (failingStarts > 0) {
            failingStarts -= 1
            throw IllegalStateException("Start rejected.")
        }
        reports += TvReport.Start(positionMs, playSessionId)
    }

    override suspend fun reportProgress(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
        isPaused: Boolean,
        eventName: PlaybackProgressEvent,
    ) {
        reports += TvReport.Progress(positionMs, isPaused, eventName, playSessionId)
    }

    override suspend fun reportStopped(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
    ) {
        reports += TvReport.Stopped(positionMs, playSessionId)
    }
}

internal class FakeTvDeviceInfoProvider : DeviceInfoProvider {
    override val deviceName: String = "Apple TV"
    private var next = 0

    override fun newDeviceId(): String {
        next += 1
        return "psid-$next"
    }
}

internal class FakeTvAuthRepository(
    var validateResult: Result<ServerInfo> = Result.success(testServerInfo()),
    var loginResult: Result<Session> = Result.success(testSession()),
) : AuthRepository {
    val quickConnectUpdates =
        MutableSharedFlow<Result<QuickConnectLoginUpdate>>(
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )
    var quickConnectCollections = 0
    var quickConnectCancellations = 0
    var logoutCount = 0

    override suspend fun validateServer(input: String): Result<ServerInfo> = validateResult

    override suspend fun login(
        serverUrl: String,
        username: String,
        password: String,
    ): Result<Session> = loginResult

    override fun loginWithQuickConnect(serverInfo: ServerInfo): Flow<Result<QuickConnectLoginUpdate>> =
        flow {
            quickConnectCollections += 1
            emitAll(quickConnectUpdates)
        }.onCompletion { cause ->
            if (cause is CancellationException) {
                quickConnectCancellations += 1
            }
        }

    override suspend fun logout(authorization: SessionRemovalAuthorization): Result<Unit> {
        logoutCount += 1
        return Result.success(Unit)
    }
}

internal class FakeRecentSearchStore(
    private val initial: List<String> = emptyList(),
) : com.jellyscope.core.data.local.RecentSearchStore {
    private val queries = initial.toMutableList()
    var failAdds = false
    var blockLists = false
    var expectedDispatcher: ContinuationInterceptor? = null
    var observedAddDispatcher: ContinuationInterceptor? = null
    var observedListDispatcher: ContinuationInterceptor? = null
    var observedClearDispatcher: ContinuationInterceptor? = null

    override suspend fun add(
        serverId: String,
        query: String,
    ) {
        observedAddDispatcher = currentCoroutineContext()[ContinuationInterceptor]
        if (failAdds) {
            throw IllegalStateException("recents store down")
        }
        queries.remove(query)
        queries.add(0, query)
    }

    override suspend fun list(serverId: String): List<String> {
        observedListDispatcher = currentCoroutineContext()[ContinuationInterceptor]
        if (blockLists) {
            awaitCancellation()
        }
        return queries.toList()
    }

    override suspend fun clear(serverId: String) {
        observedClearDispatcher = currentCoroutineContext()[ContinuationInterceptor]
        queries.clear()
    }

    override suspend fun clearServerScoped() {
        queries.clear()
    }

    override suspend fun clearServerScoped(serverId: String) {
        queries.clear()
    }
}

internal class FakePlaybackPreferencesStore(
    var preferences: PlaybackPreferences = PlaybackPreferences(),
) : PlaybackPreferencesStore {
    val savedSnapshots = mutableListOf<PlaybackPreferences>()
    var failNextSave = false
    var blockGets = false
    var expectedDispatcher: ContinuationInterceptor? = null
    var observedGetDispatcher: ContinuationInterceptor? = null
    var observedSaveDispatcher: ContinuationInterceptor? = null

    override suspend fun get(serverId: String): PlaybackPreferences {
        observedGetDispatcher = currentCoroutineContext()[ContinuationInterceptor]
        if (blockGets) {
            awaitCancellation()
        }
        return preferences
    }

    override suspend fun save(
        serverId: String,
        preferences: PlaybackPreferences,
    ) {
        observedSaveDispatcher = currentCoroutineContext()[ContinuationInterceptor]
        if (failNextSave) {
            failNextSave = false
            throw IllegalStateException("save failed")
        }
        this.preferences = preferences
        savedSnapshots += preferences
    }

    override suspend fun clear(serverId: String) {
        preferences = PlaybackPreferences()
    }

    override suspend fun clearServerScoped() {
        preferences = PlaybackPreferences()
    }

    override suspend fun clearServerScoped(serverId: String) {
        preferences = PlaybackPreferences()
    }
}

internal class FakeSubtitleSelectionStore(
    var stored: SubtitleSelectionIntent? = null,
) : SubtitleSelectionStore {
    val savedSelections = mutableListOf<Pair<SubtitleSelectionKey, SubtitleSelectionIntent>>()
    var deleteCount = 0

    override suspend fun get(key: SubtitleSelectionKey): SubtitleSelectionIntent? = stored

    override suspend fun save(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    ) {
        stored = selection
        savedSelections += key to selection
    }

    override suspend fun delete(key: SubtitleSelectionKey) {
        stored = null
        deleteCount += 1
    }

    override suspend fun clearServerScoped() {
        stored = null
    }

    override suspend fun clearServerScoped(serverId: String) {
        stored = null
    }
}

internal class FakeTvSessionRepository : SessionRepository {
    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Restoring)
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()
    override val accounts: StateFlow<List<AccountSession>> = MutableStateFlow(emptyList())

    fun update(state: SessionState) {
        _sessionState.value = state
    }

    override suspend fun setLoggedIn(session: Session) {
        _sessionState.value = SessionState.LoggedIn(session)
    }

    override suspend fun setLoggedOut(
        serverUrl: String?,
        authorization: SessionRemovalAuthorization,
    ): Result<Unit> {
        _sessionState.value = SessionState.LoggedOut(serverUrl)
        return Result.success(Unit)
    }

    override suspend fun switchTo(accountId: String): Result<Session> = Result.failure(AuthError.AccountNotFound)
}
