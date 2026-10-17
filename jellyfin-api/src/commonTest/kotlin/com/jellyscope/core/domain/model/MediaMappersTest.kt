// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

import com.jellyscope.core.data.remote.BaseItemDto
import com.jellyscope.core.data.remote.ChapterDto
import com.jellyscope.core.data.remote.ItemFacetDto
import com.jellyscope.core.data.remote.MediaSegmentDto
import com.jellyscope.core.data.remote.MediaSourceDto
import com.jellyscope.core.data.remote.MediaStreamDto
import com.jellyscope.core.data.remote.PersonDto
import com.jellyscope.core.data.remote.RemoteTrailerDto
import com.jellyscope.core.data.remote.TrickplayInfoDto
import com.jellyscope.core.data.remote.UserDataDto
import com.jellyscope.core.data.remote.UserViewDto
import com.jellyscope.core.domain.playback.MediaSegmentType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class MediaMappersTest {
    @Test
    fun decodesMediaSourcePathAndNestedTrickplayAtTheMapperBoundary() {
        val detail =
            Json { ignoreUnknownKeys = true }
                .decodeFromString<BaseItemDto>(
                    """
                    {
                      "Id": "movie-1",
                      "Name": "Movie",
                      "Type": "Movie",
                      "MediaSources": [
                        {"Id": "source-1", "Name": "1080p", "Path": "/private/library/Movie.2024.WEB-DL.mkv"}
                      ],
                      "Trickplay": {
                        "source-1": {
                          "320": {
                            "Width": 320, "Height": 180, "TileWidth": 10, "TileHeight": 10,
                            "ThumbnailCount": 100, "Interval": 10000
                          },
                          "640": {
                            "Width": 640, "Height": 360, "TileWidth": 10, "TileHeight": 10,
                            "ThumbnailCount": 201, "Interval": 10000
                          }
                        }
                      }
                    }
                    """.trimIndent(),
                ).toDomainMediaItemDetail()

        assertEquals("Movie.2024.WEB-DL.mkv", detail?.versions?.single()?.releaseBasename)
        assertEquals(
            "Movie.2024.WEB-DL.mkv",
            detail
                ?.item
                ?.versions
                ?.single()
                ?.releaseBasename,
        )
        assertFalse(
            detail
                ?.versions
                ?.single()
                ?.releaseBasename
                .orEmpty()
                .contains("library"),
        )
        assertFalse(
            detail
                ?.versions
                ?.single()
                ?.releaseBasename
                .orEmpty()
                .contains('/'),
        )
        assertFalse(
            detail
                ?.versions
                ?.single()
                ?.releaseBasename
                .orEmpty()
                .contains('\\'),
        )
        assertEquals("1080p", detail?.versions?.single()?.name)
        assertEquals("640", detail?.trickplayByMediaSourceId?.get("source-1")?.resolutionKey)
        assertEquals(3, detail?.trickplayByMediaSourceId?.get("source-1")?.tileCount)
    }

    @Test
    fun mediaSourceReleaseBasenameHandlesBothSeparatorsWithoutUsingDisplayNameFallback() {
        fun mapped(
            path: String?,
            name: String = "1080p",
        ): String? =
            BaseItemDto(
                id = "movie-1",
                name = "Movie",
                type = "Movie",
                mediaSources = listOf(MediaSourceDto(id = "source-1", name = name, path = path)),
            ).toDomainMediaItemDetail()?.versions?.single()?.releaseBasename

        assertEquals("Movie.Release.mkv", mapped("C:\\Media\\Movie.Release.mkv"))
        assertEquals("Movie", mapped("/media/Movie"))
        assertNull(mapped(null))
        assertNull(mapped(""))
        assertNull(mapped("   "))
        assertNull(mapped("/media/"))
        assertNull(mapped("C:\\Media\\"))
        assertNull(mapped("/media/."))
        assertNull(mapped("/media/.."))
        assertNull(mapped(null, name = "Generic Display Name"))
    }

    @Test
    fun mapsMissingOptionalFieldsToSafeDefaults() {
        val item =
            BaseItemDto(
                id = "item-1",
                name = "Missing Fields",
                type = "Movie",
            ).toDomainMediaItem()

        requireNotNull(item)
        assertEquals(MediaKind.Movie, item.kind)
        assertNull(item.overview)
        assertEquals(emptyList(), item.genres)
        assertNull(item.productionYear)
        assertNull(item.runtime)
        assertNull(item.isLive)
        assertNull(item.imageRefs.primaryTag)
        assertFalse(item.played)
        assertFalse(item.isFavorite)
        assertNull(item.playedPercentage)
    }

    @Test
    fun mapsItemLiveAndSourceTimelineFacts() {
        val detail =
            BaseItemDto(
                id = "live-1",
                name = "Live item",
                type = "Movie",
                isLive = true,
                mediaSources =
                    listOf(
                        MediaSourceDto(
                            id = "source-1",
                            runTimeTicks = 600_000_000L,
                            isInfiniteStream = true,
                        ),
                    ),
            ).toDomainMediaItemDetail()

        requireNotNull(detail)
        assertEquals(true, detail.item.isLive)
        assertEquals(60.seconds, detail.versions.single().runtime)
        assertEquals(true, detail.versions.single().isInfiniteStream)
    }

    @Test
    fun mapsEpisodeLabelAndRuntime() {
        val item =
            BaseItemDto(
                id = "episode-1",
                name = "Episode",
                type = "Episode",
                seriesName = "Show",
                seriesId = "series-1",
                seasonId = "season-2",
                parentIndexNumber = 2,
                indexNumber = 5,
                runTimeTicks = 300_000_000,
                premiereDate = "2024-02-03T00:00:00Z",
                overview = "Episode overview.",
                genres = listOf("Drama", "", "Mystery"),
                productionYear = 2024,
                imageTags = mapOf("Primary" to "primary-tag"),
                backdropImageTags = listOf("backdrop-tag"),
                mediaSources =
                    listOf(
                        MediaSourceDto(
                            id = "source-1",
                            size = 1_234_567,
                            mediaStreams = listOf(MediaStreamDto(type = "Video")),
                        ),
                    ),
                userData =
                    UserDataDto(
                        played = false,
                        isFavorite = true,
                        playedPercentage = 25.0,
                        playbackPositionTicks = 100,
                        unplayedItemCount = 3,
                    ),
            ).toDomainMediaItem()

        requireNotNull(item)
        assertEquals(MediaKind.Episode, item.kind)
        assertEquals("S2E5", item.episodeLabel)
        assertEquals("series-1", item.seriesId)
        assertEquals("season-2", item.seasonId)
        assertEquals(30.seconds, item.runtime)
        assertEquals("2024-02-03T00:00:00Z", item.premiereDate?.toString())
        assertEquals(1_234_567L, item.sizeBytes)
        assertEquals("Episode overview.", item.overview)
        assertEquals(listOf("Drama", "Mystery"), item.genres)
        assertEquals(2024, item.productionYear)
        assertEquals("primary-tag", item.imageRefs.primaryTag)
        assertEquals("backdrop-tag", item.imageRefs.backdropTag)
        assertEquals(true, item.isFavorite)
        assertEquals(3, item.unplayedItemCount)
        assertEquals(listOf("source-1"), item.versions.map { version -> version.id })
    }

    @Test
    fun mapsFullDetailFieldsAndMediaVersions() {
        val detail =
            BaseItemDto(
                id = "movie-1",
                name = "Movie",
                type = "Movie",
                overview = "A movie overview.",
                genres = listOf("Drama", "Sci-Fi"),
                officialRating = "PG-13",
                communityRating = 8.2,
                criticRating = 87.4,
                providerIds = mapOf("imDb" to "tt1234567", "tMdB" to "603"),
                productionYear = 2024,
                taglines = listOf("A tagline."),
                mediaSources =
                    listOf(
                        MediaSourceDto(
                            id = "source-1",
                            name = "1080p",
                            size = 4_294_967_296L,
                            mediaStreams =
                                listOf(
                                    MediaStreamDto(
                                        type = "Video",
                                        height = 1080,
                                        width = 1920,
                                        averageFrameRate = 23.976f,
                                        videoRangeType = "SDR",
                                        bitDepth = 10,
                                    ),
                                    MediaStreamDto(type = "Audio", displayTitle = "English DTS"),
                                    MediaStreamDto(type = "Subtitle", language = "Spanish"),
                                ),
                        ),
                    ),
                people =
                    listOf(
                        PersonDto(id = "actor-1", name = "Actor One", role = "Lead", type = "Actor", primaryImageTag = "a1"),
                        PersonDto(id = "director-1", name = "Director One", type = "Director"),
                        PersonDto(id = null, name = "Missing Id", type = "Actor"),
                    ),
            ).toDomainMediaItemDetail()

        requireNotNull(detail)
        assertEquals("A movie overview.", detail.overview)
        assertEquals(listOf("Drama", "Sci-Fi"), detail.genres)
        assertEquals("PG-13", detail.officialRating)
        assertEquals(8.2, detail.communityRating)
        assertEquals(87.4, detail.criticRating)
        assertEquals("tt1234567", detail.imdbId)
        assertEquals("603", detail.tmdbId)
        assertEquals("movie", detail.tmdbItemType)
        assertEquals(2024, detail.productionYear)
        assertEquals(listOf("A tagline."), detail.taglines)
        assertEquals("source-1", detail.versions.single().id)
        assertEquals("1080p", detail.versions.single().name)
        assertEquals(4_294_967_296L, detail.versions.single().sizeBytes)
        assertEquals(
            1080,
            detail.versions
                .single()
                .mediaStreams
                .first()
                .height,
        )
        val videoStream =
            detail.versions
                .single()
                .mediaStreams
                .first()
        assertEquals(1920, videoStream.width)
        assertEquals(23.976f.toDouble(), videoStream.realFrameRate)
        assertEquals("SDR", videoStream.videoRangeType)
        assertEquals(10, videoStream.bitDepth)
        assertEquals(listOf("English DTS"), detail.versions.single().audioTracks)
        assertEquals(listOf("Spanish"), detail.versions.single().subtitleTracks)
        assertEquals(listOf("Actor One", "Director One"), detail.people.map { person -> person.name })
        assertEquals(MediaPersonType.Actor, detail.people.first().type)
        assertEquals("Lead", detail.people.first().role)
        assertEquals("a1", detail.people.first().primaryImageTag)
    }

    @Test
    fun preservesBlankMediaSourceNamesForLocalizedUiFallback() {
        val detail =
            BaseItemDto(
                id = "movie-1",
                name = "Movie",
                type = "Movie",
                mediaSources =
                    listOf(
                        MediaSourceDto(id = "source-1", name = ""),
                        MediaSourceDto(id = "source-2", name = "   "),
                    ),
            ).toDomainMediaItemDetail()

        requireNotNull(detail)
        assertEquals(listOf("", "   "), detail.versions.map { version -> version.name })
        assertEquals(listOf("source-1", "source-2"), detail.versions.map { version -> version.id })
    }

    @Test
    fun mapsSeriesProviderIdsToTvTmdbTypeAndIgnoresBlankValues() {
        val detail =
            BaseItemDto(
                id = "series-1",
                name = "Series",
                type = "Series",
                providerIds = mapOf("IMDB" to "", "tmdb" to "1399"),
            ).toDomainMediaItemDetail()

        requireNotNull(detail)
        assertNull(detail.imdbId)
        assertEquals("1399", detail.tmdbId)
        assertEquals("tv", detail.tmdbItemType)
    }

    @Test
    fun mapsMissingDetailFieldsToSafeDefaults() {
        val detail =
            BaseItemDto(
                id = "movie-1",
                name = "Movie",
                type = "Movie",
                userData = null,
            ).toDomainMediaItemDetail()

        requireNotNull(detail)
        assertNull(detail.overview)
        assertEquals(emptyList(), detail.genres)
        assertEquals(emptyList(), detail.versions)
        assertFalse(detail.item.played)
    }

    @Test
    fun mapsPlaybackDetailExtensions() {
        val detail =
            BaseItemDto(
                id = "movie-1",
                name = "Movie",
                type = "Movie",
                chapters =
                    listOf(
                        ChapterDto(name = "Opening", startPositionTicks = 0L),
                        ChapterDto(name = "Act Two", startPositionTicks = 60_000_000L),
                    ),
                trickplay =
                    mapOf(
                        "source-1" to
                            mapOf(
                                "320" to
                                    TrickplayInfoDto(
                                        width = 320,
                                        height = 180,
                                        tileWidth = 10,
                                        tileHeight = 10,
                                        thumbnailCount = 150,
                                        interval = 10_000L,
                                    ),
                            ),
                    ),
                remoteTrailers = listOf(RemoteTrailerDto(url = "https://trailers.example/movie")),
            ).toDomainMediaItemDetail()

        requireNotNull(detail)
        assertEquals(listOf("Opening", "Act Two"), detail.chapters.map { chapter -> chapter.name })
        assertEquals(6_000L, detail.chapters[1].startMs)
        assertEquals("320", detail.trickplayByMediaSourceId["source-1"]?.resolutionKey)
        assertEquals(320, detail.trickplayByMediaSourceId["source-1"]?.width)
        assertEquals(2, detail.trickplayByMediaSourceId["source-1"]?.tileCount)
        assertEquals("https://trailers.example/movie", detail.trailerUrl)
    }

    @Test
    fun mapsMediaSegmentsAndFiltersInvalidRanges() {
        val segments =
            listOf(
                MediaSegmentDto(type = "Intro", startTicks = 10_000_000L, endTicks = 30_000_000L),
                MediaSegmentDto(type = "UnknownType", startTicks = 40_000_000L, endTicks = 50_000_000L),
                MediaSegmentDto(type = "Outro", startTicks = 60_000_000L, endTicks = 60_000_000L),
            ).mapNotNull { segment -> segment.toDomainMediaSegment() }

        assertEquals(listOf(MediaSegmentType.Intro, MediaSegmentType.Unknown), segments.map { segment -> segment.type })
        assertEquals(1_000L, segments.first().startMs)
        assertEquals(3_000L, segments.first().endMs)
    }

    @Test
    fun mapsSeriesAndPersonFacetFields() {
        val series =
            BaseItemDto(
                id = "series-1",
                name = "A Show",
                type = "Series",
            ).toDomainMediaItem()
        val person =
            PersonDto(
                id = "person-1",
                name = "Actor",
                primaryImageTag = "person-tag",
                type = "Actor",
            ).toDomainPerson()
        val mediaPerson =
            PersonDto(
                id = "person-2",
                name = "Writer",
                role = "Teleplay",
                type = "Writer",
                imageTags = mapOf("Primary" to "writer-tag"),
            ).toDomainMediaPerson()

        requireNotNull(series)
        requireNotNull(person)
        requireNotNull(mediaPerson)
        assertEquals(MediaKind.Series, series.kind)
        assertEquals("person-1", person.id)
        assertEquals("Actor", person.name)
        assertEquals("person-tag", person.imageRefs.primaryTag)
        assertEquals(MediaPersonType.Writer, mediaPerson.type)
        assertEquals("Teleplay", mediaPerson.role)
        assertEquals("writer-tag", mediaPerson.primaryImageTag)
    }

    @Test
    fun mapsPersonHeaderAndFacetImages() {
        val header =
            BaseItemDto(
                id = "person-1",
                name = "Actor",
                type = "Person",
                overview = "Actor biography.",
                imageTags = mapOf("Primary" to "person-primary"),
                backdropImageTags = listOf("person-backdrop"),
            ).toDomainPersonHeader()
        val facet =
            ItemFacetDto(
                id = "genre-1",
                name = "Drama",
                imageTags = mapOf("Primary" to "genre-primary"),
                backdropImageTags = listOf("genre-backdrop"),
            ).toDomainLibraryFacet()

        requireNotNull(header)
        requireNotNull(facet)
        assertEquals("person-1", header.id)
        assertEquals("Actor", header.name)
        assertEquals("Actor biography.", header.overview)
        assertEquals("person-primary", header.imageRefs.primaryTag)
        assertEquals("person-backdrop", header.imageRefs.backdropTag)
        assertEquals("genre-1", facet.id)
        assertEquals("Drama", facet.name)
        assertEquals("genre-primary", facet.imageRefs.primaryTag)
        assertEquals("genre-backdrop", facet.imageRefs.backdropTag)
    }

    @Test
    fun mapsLibraryCollectionTypesWithoutFilteringUnsupportedTypes() {
        val libraries =
            listOf(
                UserViewDto(id = "movies", name = "Movies", collectionType = "movies"),
                UserViewDto(id = "shows", name = "Shows", collectionType = "tvshows"),
                UserViewDto(id = "music", name = "Music", collectionType = "music"),
                UserViewDto(id = "boxsets", name = "Collections", collectionType = "boxsets"),
                UserViewDto(id = "playlists", name = "Playlists", collectionType = "playlists"),
                UserViewDto(id = "folders", name = "Folders", collectionType = "folders"),
                UserViewDto(id = "livetv", name = "Live TV", collectionType = "livetv"),
                UserViewDto(id = "mixed", name = "Mixed", collectionType = null),
                UserViewDto(id = "unknown", name = "Unknown", collectionType = "somethingnew"),
            ).mapNotNull { view -> view.toDomainLibrary() }

        assertEquals(
            listOf(
                LibraryCollectionType.Movies,
                LibraryCollectionType.TvShows,
                LibraryCollectionType.Music,
                LibraryCollectionType.BoxSets,
                LibraryCollectionType.Playlists,
                LibraryCollectionType.Folders,
                LibraryCollectionType.LiveTv,
                LibraryCollectionType.Other,
                LibraryCollectionType.Other,
            ),
            libraries.map { library -> library.collectionType },
        )
    }

    @Test
    fun blankChapterNamesMapToEmptySoTheUiSuppliesItsLocalizedFallback() {
        // The mapper must NOT pre-fill a name. Every consumer has a numbered,
        // localized fallback keyed on a blank name (player picker via
        // player_chapter_cd, tvOS via its own fallback); pre-filling the English
        // literal "Chapter" made those unreachable and showed the same
        // untranslated word for every unnamed chapter.
        val detail =
            BaseItemDto(
                id = "movie-blank-chapters",
                name = "Movie",
                type = "Movie",
                chapters =
                    listOf(
                        ChapterDto(name = null, startPositionTicks = 0L),
                        ChapterDto(name = "   ", startPositionTicks = 60_000_000L),
                        ChapterDto(name = "Act Two", startPositionTicks = 120_000_000L),
                    ),
            ).toDomainMediaItemDetail()

        requireNotNull(detail)
        assertEquals(listOf("", "", "Act Two"), detail.chapters.map { chapter -> chapter.name })
    }
}
