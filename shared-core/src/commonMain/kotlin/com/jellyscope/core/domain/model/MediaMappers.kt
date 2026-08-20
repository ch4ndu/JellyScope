// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

import com.jellyscope.core.data.remote.BaseItemDto
import com.jellyscope.core.data.remote.ChapterDto
import com.jellyscope.core.data.remote.ItemFacetDto
import com.jellyscope.core.data.remote.MediaSegmentDto
import com.jellyscope.core.data.remote.MediaSourceDto
import com.jellyscope.core.data.remote.MediaStreamDto
import com.jellyscope.core.data.remote.NameGuidPairDto
import com.jellyscope.core.data.remote.PersonDto
import com.jellyscope.core.data.remote.TrickplayInfoDto
import com.jellyscope.core.data.remote.UserViewDto
import com.jellyscope.core.domain.playback.Chapter
import com.jellyscope.core.domain.playback.MediaSegment
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.TrickplayInfo
import com.jellyscope.core.domain.playback.toPlaybackDuration
import kotlin.time.Instant

fun UserViewDto.toDomainLibrary(): Library? {
    val id = id?.takeIf { it.isNotBlank() } ?: return null
    val name = name?.takeIf { it.isNotBlank() } ?: return null

    return Library(
        id = id,
        name = name,
        collectionType =
            when (collectionType?.lowercase()) {
                "movies" -> LibraryCollectionType.Movies
                "tvshows" -> LibraryCollectionType.TvShows
                "music" -> LibraryCollectionType.Music
                "boxsets" -> LibraryCollectionType.BoxSets
                "playlists" -> LibraryCollectionType.Playlists
                "folders" -> LibraryCollectionType.Folders
                "livetv" -> LibraryCollectionType.LiveTv
                else -> LibraryCollectionType.Other
            },
    )
}

fun BaseItemDto.toDomainMediaItem(): MediaItem? {
    val id = id?.takeIf { it.isNotBlank() } ?: return null
    val name = name?.takeIf { it.isNotBlank() } ?: return null
    val mappedPeople = people.mapNotNull { person -> person.toDomainMediaPerson() }

    return MediaItem(
        id = id,
        name = name,
        kind =
            when (type) {
                "Movie" -> MediaKind.Movie
                "Series" -> MediaKind.Series
                "Episode" -> MediaKind.Episode
                else -> MediaKind.Other
            },
        overview = overview?.takeIf { it.isNotBlank() },
        genres = genres.filter { it.isNotBlank() },
        productionYear = productionYear,
        officialRating = officialRating?.takeIf { it.isNotBlank() },
        communityRating = communityRating,
        people = mappedPeople,
        seriesName = seriesName,
        seriesId = seriesId,
        seasonId = seasonId,
        indexNumber = indexNumber,
        parentIndexNumber = parentIndexNumber,
        runtime = runTimeTicks?.toPlaybackDuration(),
        isLive = isLive,
        premiereDate = premiereDate?.let(::parseInstantOrNull),
        dateCreated = dateCreated?.let(::parseInstantOrNull),
        sizeBytes = mediaSources.firstOrNull()?.size,
        played = userData?.played == true,
        isFavorite = userData?.isFavorite == true,
        playedPercentage = userData?.playedPercentage,
        playbackPositionTicks = userData?.playbackPositionTicks,
        unplayedItemCount = userData?.unplayedItemCount,
        imageRefs =
            ImageRefs(
                primaryTag = imageTags["Primary"],
                backdropTag = backdropImageTags.firstOrNull(),
                logoTag = imageTags["Logo"],
            ),
        versions = mediaSources.map { source -> source.toDomainMediaVersion() },
    )
}

fun BaseItemDto.toDomainMediaItemDetail(): MediaItemDetail? {
    val item = toDomainMediaItem() ?: return null
    val mappedPeople = people.mapNotNull { person -> person.toDomainMediaPerson() }
    val imdbId =
        providerIds.entries
            .firstOrNull { entry -> entry.key.equals("Imdb", true) }
            ?.value
            ?.takeIf { value -> value.isNotBlank() }
    val tmdbId =
        providerIds.entries
            .firstOrNull { entry -> entry.key.equals("Tmdb", true) }
            ?.value
            ?.takeIf { value -> value.isNotBlank() }

    return MediaItemDetail(
        item = item,
        overview = overview?.takeIf { it.isNotBlank() },
        genres = genres.filter { it.isNotBlank() },
        officialRating = officialRating?.takeIf { it.isNotBlank() },
        communityRating = communityRating,
        criticRating = criticRating,
        imdbId = imdbId,
        tmdbId = tmdbId,
        tmdbItemType = if (item.kind == MediaKind.Series) "tv" else "movie",
        productionYear = productionYear,
        taglines = taglines.filter { it.isNotBlank() },
        studios = studios.mapNotNull { studio -> studio.toDomainStudio() },
        versions = mediaSources.map { source -> source.toDomainMediaVersion() },
        people = mappedPeople,
        chapters = chapters.mapNotNull { chapter -> chapter.toDomainChapter() },
        trickplay = trickplay.toDomainTrickplayInfo(),
        trailerUrl = remoteTrailers.firstNotNullOfOrNull { trailer -> trailer.url?.takeIf { it.isNotBlank() } },
    )
}

fun BaseItemDto.toDomainPersonHeader(): PersonHeader? {
    val id = id?.takeIf { it.isNotBlank() } ?: return null
    val name = name?.takeIf { it.isNotBlank() } ?: return null

    return PersonHeader(
        id = id,
        name = name,
        overview = overview?.takeIf { it.isNotBlank() },
        imageRefs =
            ImageRefs(
                primaryTag = imageTags["Primary"],
                backdropTag = backdropImageTags.firstOrNull(),
            ),
    )
}

fun NameGuidPairDto.toDomainStudio(): Studio? {
    val id = id?.takeIf { it.isNotBlank() } ?: return null
    val name = name?.takeIf { it.isNotBlank() } ?: return null
    return Studio(id = id, name = name)
}

fun PersonDto.toDomainPerson(): Person? {
    val id = id?.takeIf { it.isNotBlank() } ?: return null
    val name = name?.takeIf { it.isNotBlank() } ?: return null

    return Person(
        id = id,
        name = name,
        imageRefs = ImageRefs(primaryTag = primaryImageTag ?: imageTags["Primary"]),
    )
}

fun ItemFacetDto.toDomainLibraryFacet(): LibraryFacet? {
    val name = name?.takeIf { it.isNotBlank() } ?: return null

    return LibraryFacet(
        id = id?.takeIf { it.isNotBlank() },
        name = name,
        imageRefs =
            ImageRefs(
                primaryTag = imageTags["Primary"],
                backdropTag = backdropImageTags.firstOrNull(),
            ),
    )
}

fun MediaSegmentDto.toDomainMediaSegment(): MediaSegment? {
    val startTicks = startTicks ?: return null
    val endTicks = endTicks ?: return null
    if (endTicks <= startTicks) {
        return null
    }

    return MediaSegment(
        type =
            when (type) {
                MediaSegmentType.Intro.apiValue -> MediaSegmentType.Intro
                MediaSegmentType.Outro.apiValue -> MediaSegmentType.Outro
                MediaSegmentType.Recap.apiValue -> MediaSegmentType.Recap
                MediaSegmentType.Preview.apiValue -> MediaSegmentType.Preview
                MediaSegmentType.Commercial.apiValue -> MediaSegmentType.Commercial
                else -> MediaSegmentType.Unknown
            },
        startTicks = startTicks,
        endTicks = endTicks,
    )
}

fun PersonDto.toDomainMediaPerson(): MediaPerson? {
    val id = id?.takeIf { it.isNotBlank() } ?: return null
    val name = name?.takeIf { it.isNotBlank() } ?: return null

    return MediaPerson(
        id = id,
        name = name,
        role = role?.takeIf { it.isNotBlank() },
        type =
            when (type?.lowercase()) {
                "actor" -> MediaPersonType.Actor
                "director" -> MediaPersonType.Director
                "writer" -> MediaPersonType.Writer
                "producer" -> MediaPersonType.Producer
                else -> MediaPersonType.Other
            },
        primaryImageTag = primaryImageTag ?: imageTags["Primary"],
    )
}

private fun MediaSourceDto.toDomainMediaVersion(): MediaVersion {
    val sourceId = id?.takeIf { it.isNotBlank() }.orEmpty()

    return MediaVersion(
        id = sourceId,
        name = name.orEmpty(),
        releaseBasename = path.toReleaseBasename(),
        sizeBytes = size,
        audioTracks = mediaStreams.readableTitlesFor(type = "Audio"),
        subtitleTracks = mediaStreams.readableTitlesFor(type = "Subtitle"),
        mediaStreams =
            mediaStreams.map { stream ->
                PlaybackMediaStream(
                    index = stream.index,
                    type = stream.type,
                    displayTitle = stream.displayTitle,
                    title = stream.title,
                    language = stream.language,
                    codec = stream.codec,
                    channelLayout = stream.channelLayout,
                    bitRate = stream.bitRate,
                    height = stream.height,
                    isDefault = stream.isDefault,
                    isExternal = stream.isExternal,
                    deliveryMethod = stream.deliveryMethod,
                    deliveryUrl = stream.deliveryUrl,
                    width = stream.width,
                    realFrameRate = (stream.realFrameRate ?: stream.averageFrameRate)?.toDouble(),
                    videoRangeType = stream.videoRangeType,
                    bitDepth = stream.bitDepth,
                )
            },
        container = container,
        runtime = runTimeTicks?.toPlaybackDuration(),
        isInfiniteStream = isInfiniteStream,
    )
}

private fun String?.toReleaseBasename(): String? {
    val value = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (value.endsWith('/') || value.endsWith('\\')) return null
    return value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()
        .takeIf { basename -> basename.isNotEmpty() && basename != "." && basename != ".." }
}

private fun List<MediaStreamDto>.readableTitlesFor(type: String): List<String> =
    filter { stream -> stream.type == type }
        .mapNotNull { stream -> stream.readableTitle() }

private fun MediaStreamDto.readableTitle(): String? =
    displayTitle?.takeIf { it.isNotBlank() }
        ?: title?.takeIf { it.isNotBlank() }
        ?: language?.takeIf { it.isNotBlank() }
        ?: codec?.takeIf { it.isNotBlank() }
        ?: index?.toString()

private fun ChapterDto.toDomainChapter(): Chapter? {
    val startTicks = startPositionTicks?.takeIf { ticks -> ticks >= 0L } ?: return null
    return Chapter(
        name = name?.takeIf { it.isNotBlank() } ?: "",
        startTicks = startTicks,
    )
}

private fun Map<String, TrickplayInfoDto>.toDomainTrickplayInfo(): TrickplayInfo? =
    entries
        .mapNotNull { entry -> entry.value.toDomainTrickplayInfo(resolutionKey = entry.key) }
        .maxByOrNull { info -> info.width }

private fun TrickplayInfoDto.toDomainTrickplayInfo(resolutionKey: String): TrickplayInfo? {
    val width = width ?: resolutionKey.toIntOrNull() ?: return null
    val height = height ?: thumbnailHeight ?: return null
    val thumbnailWidth = thumbnailWidth ?: width
    val thumbnailHeight = thumbnailHeight ?: height
    val tileWidth = tileWidth?.takeIf { value -> value > 0 } ?: return null
    val tileHeight = tileHeight?.takeIf { value -> value > 0 } ?: return null
    val thumbnailCount = thumbnailCount?.takeIf { value -> value > 0 } ?: return null
    val intervalMs = interval?.takeIf { value -> value > 0L } ?: return null

    return TrickplayInfo(
        resolutionKey = resolutionKey,
        width = width,
        height = height,
        tileWidth = tileWidth,
        tileHeight = tileHeight,
        thumbnailWidth = thumbnailWidth,
        thumbnailHeight = thumbnailHeight,
        thumbnailCount = thumbnailCount,
        intervalMs = intervalMs,
    )
}

private fun parseInstantOrNull(value: String): Instant? =
    runCatching {
        Instant.parse(value)
    }.getOrNull()
