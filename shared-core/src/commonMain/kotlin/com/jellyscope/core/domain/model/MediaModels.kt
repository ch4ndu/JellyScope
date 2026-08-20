// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

import com.jellyscope.core.domain.playback.Chapter
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.TrickplayInfo
import kotlin.time.Duration
import kotlin.time.Instant

data class Library(
    val id: String,
    val name: String,
    val collectionType: LibraryCollectionType,
)

enum class LibraryCollectionType {
    Movies,
    TvShows,
    Music,
    BoxSets,
    Playlists,
    Folders,
    LiveTv,
    Other,
}

// User-created media libraries (movies/tvshows/music/mixed) vs auto/system views
// (collections, playlists, folders, live TV). Use a drop-list so a null/mixed
// CollectionType (a real mixed library) is kept, and future types default in.
val LibraryCollectionType.isUserLibrary: Boolean
    get() =
        when (this) {
            LibraryCollectionType.BoxSets,
            LibraryCollectionType.Playlists,
            LibraryCollectionType.Folders,
            LibraryCollectionType.LiveTv,
            -> false
            else -> true
        }

data class MediaItem(
    val id: String,
    val name: String,
    val kind: MediaKind,
    val seriesName: String? = null,
    val seriesId: String? = null,
    val seasonId: String? = null,
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
    val runtime: Duration? = null,
    val isLive: Boolean? = null,
    val premiereDate: Instant? = null,
    val dateCreated: Instant? = null,
    val sizeBytes: Long? = null,
    val played: Boolean = false,
    val isFavorite: Boolean = false,
    val playedPercentage: Double? = null,
    val playbackPositionTicks: Long? = null,
    val unplayedItemCount: Int? = null,
    val imageRefs: ImageRefs = ImageRefs(),
    val downloadState: DownloadState = DownloadState.NotDownloaded,
    val source: MediaSourceLocality = MediaSourceLocality.Remote,
    val progressSource: ProgressSource = ProgressSource.Server,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val productionYear: Int? = null,
    val officialRating: String? = null,
    val communityRating: Double? = null,
    val people: List<MediaPerson> = emptyList(),
    val versions: List<MediaVersion> = emptyList(),
) {
    val episodeLabel: String?
        get() {
            val season = parentIndexNumber ?: return null
            val episode = indexNumber ?: return null
            return "S${season}E$episode"
        }
}

data class MediaItemDetail(
    val item: MediaItem,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val officialRating: String? = null,
    val communityRating: Double? = null,
    val criticRating: Double? = null,
    val imdbId: String? = null,
    val tmdbId: String? = null,
    val tmdbItemType: String? = null,
    val productionYear: Int? = null,
    val taglines: List<String> = emptyList(),
    val studios: List<Studio> = emptyList(),
    val versions: List<MediaVersion> = emptyList(),
    val people: List<MediaPerson> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val trickplay: TrickplayInfo? = null,
    val trailerUrl: String? = null,
)

data class Studio(
    val id: String,
    val name: String,
)

// A titled shelf of related content. Consumers keep the source order and cap
// the visible shelves with this shared limit.
data class RelatedGroup(
    val kind: RelatedGroupKind,
    // Dynamic label for a named group, such as a genre or studio.
    // Null for kinds that need no label (NextUp, Similar).
    val label: String? = null,
    val items: List<MediaItem> = emptyList(),
)

enum class RelatedGroupKind {
    NextUp,
    Cast,
    Similar,
    Genre,
    Studio,
}

const val RELATED_GROUP_DISPLAY_LIMIT = 4

data class MediaVersion(
    val id: String,
    val name: String,
    val releaseBasename: String? = null,
    val audioTracks: List<String> = emptyList(),
    val subtitleTracks: List<String> = emptyList(),
    val mediaStreams: List<PlaybackMediaStream> = emptyList(),
    val container: String? = null,
    val runtime: Duration? = null,
    val isInfiniteStream: Boolean? = null,
    val sizeBytes: Long? = null,
)

data class MediaPerson(
    val id: String,
    val name: String,
    val role: String? = null,
    val type: MediaPersonType = MediaPersonType.Other,
    val primaryImageTag: String? = null,
)

enum class MediaPersonType {
    Actor,
    Director,
    Writer,
    Producer,
    Other,
}

enum class MediaKind {
    Movie,
    Series,
    Episode,
    Other,
}

data class Person(
    val id: String,
    val name: String,
    val imageRefs: ImageRefs = ImageRefs(),
)

data class ImageRefs(
    val primaryTag: String? = null,
    val backdropTag: String? = null,
    val logoTag: String? = null,
)

enum class DownloadState {
    NotDownloaded,
    Queued,
    Downloading,
    Paused,
    BlockedByQuota,
    Finalizing,
    Completed,
    Failed,
}

sealed interface MediaSourceLocality {
    data object Remote : MediaSourceLocality

    data class Downloaded(
        val artifactRef: OfflineArtifactRef,
    ) : MediaSourceLocality
}

sealed interface ProgressSource {
    data object Server : ProgressSource
}
