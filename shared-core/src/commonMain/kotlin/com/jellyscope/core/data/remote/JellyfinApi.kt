// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import com.jellyscope.core.domain.playback.EffectivePlayerDevicePolicy
import com.jellyscope.core.domain.playback.PlaybackInfoRequestPolicy

interface JellyfinApi {
    suspend fun getPublicSystemInfo(serverUrl: String): PublicSystemInfoDto

    suspend fun authenticateByName(
        serverUrl: String,
        username: String,
        password: String,
    ): AuthenticationResultDto

    suspend fun initiateQuickConnect(serverUrl: String): QuickConnectResultDto

    suspend fun getQuickConnectState(
        serverUrl: String,
        secret: String,
    ): QuickConnectResultDto

    suspend fun authenticateWithQuickConnect(
        serverUrl: String,
        secret: String,
    ): AuthenticationResultDto

    suspend fun getUserViews(context: AuthenticatedRequestContext): UserViewsQueryResultDto

    suspend fun getCurrentUser(context: AuthenticatedRequestContext): UserDto =
        throw UnsupportedOperationException("Current-user policy is unavailable.")

    suspend fun preflightOriginalDownload(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
    ): OriginalDownloadPreflightResult =
        OriginalDownloadPreflightResult.Rejected(
            OriginalDownloadFailure.ServerUnavailable,
        )

    suspend fun <T> streamOriginalDownload(
        context: AuthenticatedRequestContext,
        source: OriginalDownloadSource,
        startByte: Long,
        consume: suspend (OriginalDownloadStream) -> T,
    ): OriginalDownloadStreamResult<T> =
        OriginalDownloadStreamResult.Rejected(
            OriginalDownloadFailure.ServerUnavailable,
        )

    /**
     * Authenticated fixed-quality admission.  The returned URL is an ephemeral transport fact;
     * callers must not persist or log it.  The default keeps lightweight/test API doubles
     * source-compatible while making unsupported fixed downloads fail closed.
     */
    suspend fun preflightFixedDownload(
        context: AuthenticatedRequestContext,
        request: FixedDownloadRequest,
    ): FixedDownloadPreflightResult = FixedDownloadPreflightResult.Rejected(FixedDownloadFailure.ServerUnavailable)

    /**
     * Streams one bounded fixed-HLS resource without collecting the response body.  The caller
     * owns playlist bounds and package completeness; this transport only authenticates trusted
     * same-origin resources and keeps redirects disabled.
     */
    suspend fun <T> streamFixedDownloadResource(
        context: AuthenticatedRequestContext,
        source: FixedDownloadSource,
        resourceUrl: String,
        maxBytes: Long,
        consume: suspend (FixedDownloadResource) -> T,
    ): FixedDownloadResourceResult<T> =
        FixedDownloadResourceResult.Rejected(
            failure = FixedDownloadFailure.ServerUnavailable,
            reason = FixedDownloadResourceRejectReason.UnexpectedTransportFailure,
        )

    /**
     * Best-effort kill for the ephemeral encoding admitted by [preflightFixedDownload]. The
     * identity is intentionally carried only by the in-memory source and must never be persisted.
     */
    suspend fun stopFixedDownloadEncoding(
        context: AuthenticatedRequestContext,
        source: FixedDownloadSource,
    ): FixedDownloadCleanupResult = FixedDownloadCleanupResult.Rejected

    suspend fun uploadSubtitle(
        context: AuthenticatedRequestContext,
        itemId: String,
        subtitle: UploadSubtitleDto,
    ): Unit = throw UnsupportedOperationException("Subtitle upload is unavailable.")

    suspend fun getSubtitleText(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
        streamIndex: Int,
    ): String = throw UnsupportedOperationException("Subtitle retrieval is unavailable.")

    /**
     * Download-only bounded subtitle read.  Production HTTP implementations stream at most
     * [maxBytes] + 1 bytes and reject an oversized response; the default preserves compatibility
     * for non-HTTP test implementations while keeping the ordinary playback API unchanged.
     */
    suspend fun getSubtitleTextBounded(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
        streamIndex: Int,
        maxBytes: Int,
    ): ByteArray {
        require(maxBytes in 0 until Int.MAX_VALUE) { "Maximum subtitle bytes must be bounded." }
        val bytes = getSubtitleText(context, itemId, mediaSourceId, streamIndex).encodeToByteArray()
        if (bytes.size > maxBytes) throw JellyfinApiException.PayloadTooLarge
        return bytes
    }

    suspend fun postClientLogDocument(
        context: AuthenticatedRequestContext,
        content: String,
    ): ClientLogDocumentDto = throw UnsupportedOperationException("Client log upload is unavailable.")

    suspend fun getResumeItems(
        context: AuthenticatedRequestContext,
        limit: Int = 20,
        fields: List<String> = defaultItemFields,
        parentId: String? = null,
        includeItemTypes: List<String> = emptyList(),
    ): BaseItemQueryResultDto

    suspend fun getItemDetail(
        context: AuthenticatedRequestContext,
        itemId: String,
        includePlaybackFields: Boolean = false,
    ): BaseItemDto

    suspend fun getSimilarItems(
        context: AuthenticatedRequestContext,
        itemId: String,
        limit: Int,
    ): BaseItemQueryResultDto

    suspend fun getSeasons(
        context: AuthenticatedRequestContext,
        seriesId: String,
    ): BaseItemQueryResultDto

    suspend fun getEpisodes(
        context: AuthenticatedRequestContext,
        seriesId: String,
        seasonId: String,
        seasonIndex: Int? = null,
    ): BaseItemQueryResultDto

    suspend fun getNextUp(
        context: AuthenticatedRequestContext,
        seriesId: String? = null,
        limit: Int = 20,
        fields: List<String> = defaultItemFields,
        parentId: String? = null,
        includeResumable: Boolean = true,
    ): BaseItemQueryResultDto

    suspend fun getUpcomingEpisodes(
        context: AuthenticatedRequestContext,
        limit: Int = 50,
        fields: List<String> = defaultItemFields,
    ): BaseItemQueryResultDto

    suspend fun getLatestItems(
        context: AuthenticatedRequestContext,
        limit: Int = 20,
        fields: List<String> = defaultItemFields,
        parentId: String? = null,
        includeItemTypes: List<String> = emptyList(),
    ): List<BaseItemDto>

    suspend fun getMovieRecommendations(
        context: AuthenticatedRequestContext,
        parentId: String,
        categoryLimit: Int = 6,
        itemLimit: Int = 20,
    ): List<RecommendationDto>

    suspend fun getItems(
        context: AuthenticatedRequestContext,
        query: ItemsQuery,
    ): BaseItemQueryResultDto

    suspend fun getGenres(
        context: AuthenticatedRequestContext,
        parentId: String?,
    ): ItemFacetQueryResultDto

    suspend fun getStudios(
        context: AuthenticatedRequestContext,
        parentId: String?,
    ): ItemFacetQueryResultDto

    suspend fun getLibraryFilterOptions(
        context: AuthenticatedRequestContext,
        parentId: String?,
    ): LibraryFilterOptionsDto

    suspend fun getMediaSegments(
        context: AuthenticatedRequestContext,
        itemId: String,
    ): List<MediaSegmentDto>

    suspend fun getPersons(
        context: AuthenticatedRequestContext,
        searchTerm: String,
        limit: Int,
    ): PersonsQueryResultDto

    suspend fun getPlaybackInfo(
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
    ): PlaybackInfoResponseDto

    suspend fun reportPlaybackStart(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
        positionTicks: Long,
        playSessionId: String,
        playMethod: String,
    )

    suspend fun reportPlaybackProgress(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
        positionTicks: Long,
        playSessionId: String,
        playMethod: String,
        isPaused: Boolean,
        eventName: String,
    )

    suspend fun reportPlaybackStopped(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
        positionTicks: Long,
        playSessionId: String,
    )

    suspend fun markItemPlayed(
        context: AuthenticatedRequestContext,
        itemId: String,
    )

    suspend fun markItemUnplayed(
        context: AuthenticatedRequestContext,
        itemId: String,
    )

    suspend fun setItemFavorite(
        context: AuthenticatedRequestContext,
        itemId: String,
    )

    suspend fun unsetItemFavorite(
        context: AuthenticatedRequestContext,
        itemId: String,
    )
}

data class AuthenticatedRequestContext(
    val serverUrl: String,
    val userId: String,
    val accessToken: String,
)

data class ItemsQuery(
    val recursive: Boolean,
    val includeItemTypes: List<String>,
    val limit: Int?,
    val parentId: String? = null,
    val startIndex: Int? = null,
    val sortBy: String? = null,
    val sortOrder: String? = null,
    val fields: List<String> = defaultItemFields,
    val enableImageTypes: List<String> = defaultImageTypes,
    val imageTypeLimit: Int = 1,
    // Computing the exact TotalRecordCount is expensive on large libraries; when
    // false the server skips it (returns only the page), so pagination must
    // derive "has more" from page fullness instead.
    val enableTotalRecordCount: Boolean = true,
    val enableUserData: Boolean? = null,
    val searchTerm: String? = null,
    val personIds: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val genreIds: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
    val officialRatings: List<String> = emptyList(),
    val studioIds: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val seriesStatus: List<String> = emptyList(),
    val filters: List<ItemFilter> = emptyList(),
    val ids: List<String>? = null,
)

enum class ItemFilter(
    val apiValue: String,
) {
    IsPlayed("IsPlayed"),
    IsUnplayed("IsUnplayed"),
    IsResumable("IsResumable"),
    IsFavorite("IsFavorite"),
    HasSubtitles("HasSubtitles"),
    HasTrailer("HasTrailer"),
    HasSpecialFeature("HasSpecialFeature"),
}

val defaultItemFields =
    listOf(
        "DateCreated",
        "Overview",
        "Genres",
        "OfficialRating",
        "CommunityRating",
        "PremiereDate",
        "ProductionYear",
        "RunTimeTicks",
        "SeriesName",
        "SeriesId",
        "SeasonId",
        "IndexNumber",
        "ParentIndexNumber",
        "ImageTags",
        "BackdropImageTags",
        "UserData",
    )

val detailItemFields = defaultItemFields + "People"

// Detail SCREENS need media sources (versions/badges) + cast + trailer button,
// but NOT Chapters/Trickplay (those are player-only and the player re-fetches
// the item on Play). Keeping them off the detail path avoids large payloads.
val detailScreenItemFields =
    detailItemFields +
        listOf(
            "MediaSources",
            "RemoteTrailers",
            "ProviderIds",
            "Studios",
            "Taglines",
        )

// The episode strip only needs badges (MediaSources) + image + progress — never
// cast/chapters/trickplay/trailers, which is a big cost multiplied per episode.
val episodeStripFields = defaultItemFields + "MediaSources"

val mediaSourceItemFields =
    detailItemFields +
        listOf(
            "MediaSources",
            "Chapters",
            "Trickplay",
            "RemoteTrailers",
            "ProviderIds",
            "Studios",
            "Taglines",
        )

val defaultImageTypes = listOf("Primary", "Backdrop")

// The detail hero can show a title clear-logo when the server has one.
val detailImageTypes = defaultImageTypes + "Logo"
