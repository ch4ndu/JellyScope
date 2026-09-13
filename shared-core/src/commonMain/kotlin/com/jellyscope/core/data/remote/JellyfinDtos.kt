// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import io.ktor.http.parseQueryString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PublicSystemInfoDto(
    @SerialName("ServerName")
    val serverName: String? = null,
    @SerialName("Version")
    val version: String? = null,
    @SerialName("Id")
    val id: String? = null,
    @SerialName("ProductName")
    val productName: String? = null,
)

@Serializable
data class AuthenticationResultDto(
    @SerialName("AccessToken")
    val accessToken: String,
    @SerialName("User")
    val user: UserDto,
    @SerialName("ServerId")
    val serverId: String? = null,
)

@Serializable
data class UserDto(
    @SerialName("Id")
    val id: String,
    @SerialName("Name")
    val name: String,
    @SerialName("Policy")
    val policy: UserPolicyDto? = null,
)

@Serializable
data class UserPolicyDto(
    @SerialName("EnableContentDownloading") val enableContentDownloading: Boolean = false,
    @SerialName("EnableSubtitleManagement") val enableSubtitleManagement: Boolean = false,
    @SerialName("MaxParentalRating") val maxParentalRating: Int? = null,
)

@Serializable
data class UploadSubtitleDto(
    @SerialName("Language") val language: String,
    @SerialName("Format") val format: String,
    @SerialName("IsForced") val isForced: Boolean,
    @SerialName("IsHearingImpaired") val isHearingImpaired: Boolean,
    @SerialName("Data") val data: String,
)

@Serializable
data class ClientLogDocumentDto(
    @SerialName("FileName") val filename: String,
)

@Serializable
internal data class AuthenticationRequestDto(
    @SerialName("Username")
    val username: String,
    @SerialName("Pw")
    val password: String,
)

@Serializable
data class QuickConnectResultDto(
    @SerialName("Secret")
    val secret: String,
    @SerialName("Code")
    val code: String,
    @SerialName("Authenticated")
    val authenticated: Boolean,
    @SerialName("DateAdded")
    val dateAdded: String? = null,
)

@Serializable
internal data class QuickConnectAuthenticationRequestDto(
    @SerialName("Secret")
    val secret: String,
)

@Serializable
data class UserViewsQueryResultDto(
    @SerialName("Items")
    val items: List<UserViewDto> = emptyList(),
    @SerialName("TotalRecordCount")
    val totalRecordCount: Int = 0,
)

@Serializable
data class UserViewDto(
    @SerialName("Id")
    val id: String? = null,
    @SerialName("Name")
    val name: String? = null,
    @SerialName("CollectionType")
    val collectionType: String? = null,
)

@Serializable
data class BaseItemQueryResultDto(
    @SerialName("Items")
    val items: List<BaseItemDto> = emptyList(),
    @SerialName("TotalRecordCount")
    val totalRecordCount: Int = 0,
)

@Serializable
data class RecommendationDto(
    @SerialName("RecommendationType")
    val recommendationType: String? = null,
    @SerialName("BaselineItemName")
    val baselineItemName: String? = null,
    @SerialName("CategoryId")
    val categoryId: String? = null,
    @SerialName("Items")
    val items: List<BaseItemDto> = emptyList(),
)

@Serializable
data class PersonsQueryResultDto(
    @SerialName("Items")
    val items: List<PersonDto> = emptyList(),
    @SerialName("TotalRecordCount")
    val totalRecordCount: Int = 0,
)

@Serializable
data class ItemFacetQueryResultDto(
    @SerialName("Items")
    val items: List<ItemFacetDto> = emptyList(),
    @SerialName("TotalRecordCount")
    val totalRecordCount: Int = 0,
)

@Serializable
data class ItemFacetDto(
    @SerialName("Id")
    val id: String? = null,
    @SerialName("Name")
    val name: String? = null,
    @SerialName("ImageTags")
    val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags")
    val backdropImageTags: List<String> = emptyList(),
)

@Serializable
data class LibraryFilterOptionsDto(
    @SerialName("OfficialRatings")
    val officialRatings: List<String> = emptyList(),
    @SerialName("Tags")
    val tags: List<String> = emptyList(),
    @SerialName("Years")
    val years: List<Int> = emptyList(),
)

@Serializable
data class PersonDto(
    @SerialName("Id")
    val id: String? = null,
    @SerialName("Name")
    val name: String? = null,
    @SerialName("Role")
    val role: String? = null,
    @SerialName("Type")
    val type: String? = null,
    @SerialName("PrimaryImageTag")
    val primaryImageTag: String? = null,
    @SerialName("ImageTags")
    val imageTags: Map<String, String> = emptyMap(),
)

@Serializable
data class BaseItemDto(
    @SerialName("Id")
    val id: String? = null,
    @SerialName("Name")
    val name: String? = null,
    @SerialName("Type")
    val type: String? = null,
    @SerialName("SeriesName")
    val seriesName: String? = null,
    @SerialName("SeriesId")
    val seriesId: String? = null,
    @SerialName("SeasonId")
    val seasonId: String? = null,
    @SerialName("IndexNumber")
    val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber")
    val parentIndexNumber: Int? = null,
    @SerialName("IsSpecial")
    val isSpecial: Boolean? = null,
    @SerialName("RunTimeTicks")
    val runTimeTicks: Long? = null,
    @SerialName("IsLive")
    val isLive: Boolean? = null,
    @SerialName("PremiereDate")
    val premiereDate: String? = null,
    @SerialName("Overview")
    val overview: String? = null,
    @SerialName("Genres")
    val genres: List<String> = emptyList(),
    @SerialName("OfficialRating")
    val officialRating: String? = null,
    @SerialName("CommunityRating")
    val communityRating: Double? = null,
    @SerialName("CriticRating")
    val criticRating: Double? = null,
    @SerialName("ProviderIds")
    val providerIds: Map<String, String> = emptyMap(),
    @SerialName("ProductionYear")
    val productionYear: Int? = null,
    @SerialName("Taglines")
    val taglines: List<String> = emptyList(),
    @SerialName("Studios")
    val studios: List<NameGuidPairDto> = emptyList(),
    @SerialName("DateCreated")
    val dateCreated: String? = null,
    @SerialName("ImageTags")
    val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags")
    val backdropImageTags: List<String> = emptyList(),
    @SerialName("MediaSources")
    val mediaSources: List<MediaSourceDto> = emptyList(),
    @SerialName("People")
    val people: List<PersonDto> = emptyList(),
    @SerialName("Chapters")
    val chapters: List<ChapterDto> = emptyList(),
    @SerialName("Trickplay")
    val trickplay: Map<String, Map<String, TrickplayInfoDto>?>? = null,
    @SerialName("RemoteTrailers")
    val remoteTrailers: List<RemoteTrailerDto> = emptyList(),
    @SerialName("UserData")
    val userData: UserDataDto? = null,
)

@Serializable
data class NameGuidPairDto(
    @SerialName("Id")
    val id: String? = null,
    @SerialName("Name")
    val name: String? = null,
)

@Serializable
data class ChapterDto(
    @SerialName("Name")
    val name: String? = null,
    @SerialName("StartPositionTicks")
    val startPositionTicks: Long? = null,
)

@Serializable
data class TrickplayInfoDto(
    @SerialName("Width")
    val width: Int? = null,
    @SerialName("Height")
    val height: Int? = null,
    @SerialName("TileWidth")
    val tileWidth: Int? = null,
    @SerialName("TileHeight")
    val tileHeight: Int? = null,
    @SerialName("ThumbnailWidth")
    val thumbnailWidth: Int? = null,
    @SerialName("ThumbnailHeight")
    val thumbnailHeight: Int? = null,
    @SerialName("ThumbnailCount")
    val thumbnailCount: Int? = null,
    @SerialName("Interval")
    val interval: Long? = null,
)

@Serializable
data class RemoteTrailerDto(
    @SerialName("Url")
    val url: String? = null,
)

@Serializable
data class MediaSegmentDto(
    @SerialName("Type")
    val type: String? = null,
    @SerialName("StartTicks")
    val startTicks: Long? = null,
    @SerialName("EndTicks")
    val endTicks: Long? = null,
)

@Serializable
data class MediaSourceDto(
    @SerialName("Id")
    val id: String? = null,
    @SerialName("Name")
    val name: String? = null,
    @SerialName("Path")
    val path: String? = null,
    @SerialName("Size")
    val size: Long? = null,
    @SerialName("Container")
    val container: String? = null,
    @SerialName("RunTimeTicks")
    val runTimeTicks: Long? = null,
    @SerialName("IsInfiniteStream")
    val isInfiniteStream: Boolean? = null,
    @SerialName("MediaStreams")
    val mediaStreams: List<MediaStreamDto> = emptyList(),
)

@Serializable
data class MediaStreamDto(
    @SerialName("Type")
    val type: String? = null,
    @SerialName("DisplayTitle")
    val displayTitle: String? = null,
    @SerialName("Title")
    val title: String? = null,
    @SerialName("Language")
    val language: String? = null,
    @SerialName("Codec")
    val codec: String? = null,
    @SerialName("Index")
    val index: Int? = null,
    @SerialName("ChannelLayout")
    val channelLayout: String? = null,
    @SerialName("BitRate")
    val bitRate: Long? = null,
    @SerialName("Height")
    val height: Int? = null,
    @SerialName("Width")
    val width: Int? = null,
    @SerialName("RealFrameRate")
    val realFrameRate: Float? = null,
    @SerialName("AverageFrameRate")
    val averageFrameRate: Float? = null,
    @SerialName("VideoRangeType")
    val videoRangeType: String? = null,
    @SerialName("BitDepth")
    val bitDepth: Int? = null,
    @SerialName("IsDefault")
    val isDefault: Boolean? = null,
    @SerialName("IsExternal")
    val isExternal: Boolean? = null,
    @SerialName("DeliveryMethod")
    val deliveryMethod: String? = null,
    @SerialName("DeliveryUrl")
    val deliveryUrl: String? = null,
    @SerialName("IsForced")
    val isForced: Boolean? = null,
    @SerialName("IsHearingImpaired")
    val isHearingImpaired: Boolean? = null,
)

@Serializable
data class UserDataDto(
    @SerialName("Played")
    val played: Boolean? = null,
    @SerialName("PlayCount")
    val playCount: Int? = null,
    @SerialName("IsFavorite")
    val isFavorite: Boolean? = null,
    @SerialName("PlayedPercentage")
    val playedPercentage: Double? = null,
    @SerialName("PlaybackPositionTicks")
    val playbackPositionTicks: Long? = null,
    @SerialName("UnplayedItemCount")
    val unplayedItemCount: Int? = null,
)

@Serializable
data class PlaybackInfoResponseDto(
    @SerialName("PlaySessionId")
    val playSessionId: String? = null,
    @SerialName("MediaSources")
    val mediaSources: List<PlaybackInfoMediaSourceDto> = emptyList(),
)

@Serializable
data class PlaybackInfoMediaSourceDto(
    @SerialName("Id")
    val id: String? = null,
    @SerialName("SupportsDirectPlay")
    val supportsDirectPlay: Boolean = false,
    @SerialName("SupportsDirectStream")
    val supportsDirectStream: Boolean = false,
    @SerialName("SupportsTranscoding")
    val supportsTranscoding: Boolean = false,
    @SerialName("TranscodingUrl")
    val transcodingUrl: String? = null,
    @SerialName("Container")
    val container: String? = null,
    @SerialName("TranscodingContainer")
    val transcodingContainer: String? = null,
    @SerialName("TranscodingSubProtocol")
    val transcodingSubProtocol: String? = null,
    @SerialName("DefaultAudioStreamIndex")
    val defaultAudioStreamIndex: Int? = null,
    @SerialName("DefaultSubtitleStreamIndex")
    val defaultSubtitleStreamIndex: Int? = null,
    @SerialName("Bitrate")
    val bitrate: Long? = null,
    @SerialName("TranscodeReasons")
    val transcodeReasons: List<String> = emptyList(),
    @SerialName("MediaStreams")
    val mediaStreams: List<MediaStreamDto> = emptyList(),
)

internal fun PlaybackInfoMediaSourceDto.resolvedTranscodeReasons(): List<String> {
    val responseReasons = transcodeReasons.filter { reason -> reason.isNotBlank() }
    if (responseReasons.isNotEmpty()) {
        return responseReasons
    }

    val query =
        transcodingUrl
            ?.substringAfter('?', missingDelimiterValue = "")
            ?.substringBefore('#')
            ?.takeIf { value -> value.isNotBlank() }
            ?: return emptyList()
    return runCatching {
        parseQueryString(query)
            .getAll("TranscodeReasons")
            .orEmpty()
            .flatMap { value -> value.split(',') }
            .map { reason -> reason.trim() }
            .filter { reason -> reason.isNotEmpty() }
            .distinct()
    }.getOrDefault(emptyList())
}

@Serializable
data class PlaybackInfoRequestDto(
    @SerialName("UserId")
    val userId: String,
    @SerialName("StartTimeTicks")
    val startTimeTicks: Long,
    @SerialName("MediaSourceId")
    val mediaSourceId: String?,
    @SerialName("AutoOpenLiveStream")
    val autoOpenLiveStream: Boolean,
    @SerialName("DeviceProfile")
    val deviceProfile: PlaybackDeviceProfileDto,
    @SerialName("AudioStreamIndex")
    val audioStreamIndex: Int?,
    @SerialName("SubtitleStreamIndex")
    val subtitleStreamIndex: Int?,
    @SerialName("MaxStreamingBitrate")
    val maxStreamingBitrate: Long?,
    @SerialName("MaxAudioChannels")
    val maxAudioChannels: Int?,
    @SerialName("EnableDirectPlay")
    val enableDirectPlay: Boolean,
    @SerialName("EnableDirectStream")
    val enableDirectStream: Boolean,
    @SerialName("EnableTranscoding")
    val enableTranscoding: Boolean,
    @SerialName("AllowAudioStreamCopy")
    val allowAudioStreamCopy: Boolean,
    @SerialName("AllowVideoStreamCopy")
    val allowVideoStreamCopy: Boolean,
    /** Download-only authorization for an explicitly confirmed embedded subtitle burn-in. */
    @SerialName("AlwaysBurnInSubtitleWhenTranscoding")
    val alwaysBurnInSubtitleWhenTranscoding: Boolean? = null,
)

@Serializable
data class PlaybackDeviceProfileDto(
    @SerialName("DirectPlayProfiles")
    val directPlayProfiles: List<DirectPlayProfileDto>,
    @SerialName("TranscodingProfiles")
    val transcodingProfiles: List<TranscodingProfileDto>,
    @SerialName("CodecProfiles")
    val codecProfiles: List<CodecProfileDto>,
    @SerialName("SubtitleProfiles")
    val subtitleProfiles: List<SubtitleProfileDto> = emptyList(),
    @SerialName("MaxStreamingBitrate")
    val maxStreamingBitrate: Long? = null,
    @SerialName("MaxStaticBitrate")
    val maxStaticBitrate: Long? = null,
)

@Serializable
data class SubtitleProfileDto(
    @SerialName("Format")
    val format: String,
    @SerialName("Method")
    val method: String,
)

@Serializable
data class DirectPlayProfileDto(
    @SerialName("Type")
    val type: String,
    @SerialName("Container")
    val container: String,
    @SerialName("VideoCodec")
    val videoCodec: String?,
    @SerialName("AudioCodec")
    val audioCodec: String?,
)

@Serializable
data class TranscodingProfileDto(
    @SerialName("Container")
    val container: String,
    @SerialName("Type")
    val type: String,
    @SerialName("Protocol")
    val protocol: String,
    @SerialName("VideoCodec")
    val videoCodec: String,
    @SerialName("AudioCodec")
    val audioCodec: String,
    @SerialName("Context")
    val context: String,
    @SerialName("MaxAudioChannels")
    val maxAudioChannels: Int? = null,
    @SerialName("EnableSubtitlesInManifest")
    val enableSubtitlesInManifest: Boolean,
    @SerialName("MaxBitrate")
    val maxBitrate: Long? = null,
)

@Serializable
data class CodecProfileDto(
    @SerialName("Type")
    val type: String,
    @SerialName("Codec")
    val codec: String,
    @SerialName("Conditions")
    val conditions: List<ProfileConditionDto>,
)

@Serializable
data class ProfileConditionDto(
    @SerialName("Condition")
    val condition: String,
    @SerialName("Property")
    val property: String,
    @SerialName("Value")
    val value: String,
    @SerialName("IsRequired")
    val isRequired: Boolean,
)

@Serializable
internal data class PlaybackStartRequestDto(
    @SerialName("ItemId")
    val itemId: String,
    @SerialName("MediaSourceId")
    val mediaSourceId: String,
    @SerialName("PositionTicks")
    val positionTicks: Long,
    @SerialName("PlayMethod")
    val playMethod: String,
    @SerialName("PlaySessionId")
    val playSessionId: String,
    @SerialName("CanSeek")
    val canSeek: Boolean,
)

@Serializable
internal data class PlaybackProgressRequestDto(
    @SerialName("ItemId")
    val itemId: String,
    @SerialName("MediaSourceId")
    val mediaSourceId: String,
    @SerialName("PositionTicks")
    val positionTicks: Long,
    @SerialName("PlayMethod")
    val playMethod: String,
    @SerialName("PlaySessionId")
    val playSessionId: String,
    @SerialName("CanSeek")
    val canSeek: Boolean,
    @SerialName("IsPaused")
    val isPaused: Boolean,
    @SerialName("EventName")
    val eventName: String,
)

@Serializable
internal data class PlaybackStoppedRequestDto(
    @SerialName("ItemId")
    val itemId: String,
    @SerialName("MediaSourceId")
    val mediaSourceId: String,
    @SerialName("PositionTicks")
    val positionTicks: Long,
    @SerialName("PlaySessionId")
    val playSessionId: String,
)
