// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.preview

import com.jellyscope.core.domain.model.AccountSession
import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibraryFilterSelection
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.core.domain.model.MediaPersonType
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.RelatedGroupKind
import com.jellyscope.core.domain.model.RuntimeBucket
import com.jellyscope.core.domain.model.ServerInfo
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.WatchedFilter
import com.jellyscope.core.domain.playback.AudioTrackOption
import com.jellyscope.core.domain.playback.Chapter
import com.jellyscope.core.domain.playback.MediaSegment
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.NoopPlayerController
import com.jellyscope.core.domain.playback.PlaybackQualityCapOrigin
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerAudioMode
import com.jellyscope.core.domain.playback.PlayerDeviceSettings
import com.jellyscope.core.domain.playback.PlayerHdrMode
import com.jellyscope.core.domain.playback.QualityOption
import com.jellyscope.core.domain.playback.QualityTier
import com.jellyscope.core.domain.playback.SubtitleRenderInfo
import com.jellyscope.core.domain.playback.SubtitleRenderMode
import com.jellyscope.core.domain.playback.SubtitleRenderStatus
import com.jellyscope.core.domain.playback.SubtitleTrackOption
import com.jellyscope.core.domain.playback.androidPlayerBackendPolicy
import com.jellyscope.ui.component.MediaCardKind
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.screen.account.AccountUiState
import com.jellyscope.ui.screen.collection.CollectionUiState
import com.jellyscope.ui.screen.detail.CastAndCrewUi
import com.jellyscope.ui.screen.detail.DetailPlayAction
import com.jellyscope.ui.screen.detail.DetailPlayLabel
import com.jellyscope.ui.screen.detail.DetailTrackSelectionUi
import com.jellyscope.ui.screen.detail.DetailUi
import com.jellyscope.ui.screen.detail.EpisodeUi
import com.jellyscope.ui.screen.detail.MediaInfoUi
import com.jellyscope.ui.screen.detail.MediaVersionUi
import com.jellyscope.ui.screen.detail.RelatedGroupUi
import com.jellyscope.ui.screen.detail.SeasonEpisodesUiState
import com.jellyscope.ui.screen.detail.SeasonUi
import com.jellyscope.ui.screen.detail.SeriesContentUi
import com.jellyscope.ui.screen.detail.SeriesHeaderUi
import com.jellyscope.ui.screen.discover.DiscoverFacetUi
import com.jellyscope.ui.screen.discover.DiscoverListState
import com.jellyscope.ui.screen.discover.DiscoverSuggestionsState
import com.jellyscope.ui.screen.discover.DiscoverUiState
import com.jellyscope.ui.screen.find.FindResultTab
import com.jellyscope.ui.screen.find.FindResultsUi
import com.jellyscope.ui.screen.find.FindUiState
import com.jellyscope.ui.screen.find.PersonUi
import com.jellyscope.ui.screen.grid.GridSort
import com.jellyscope.ui.screen.grid.GridUiState
import com.jellyscope.ui.screen.home.HomeRow
import com.jellyscope.ui.screen.home.HomeUiState
import com.jellyscope.ui.screen.home.RowState
import com.jellyscope.ui.screen.library.LibraryBrowseUiState
import com.jellyscope.ui.screen.library.LibraryFilterOption
import com.jellyscope.ui.screen.library.LibraryFilterOptions
import com.jellyscope.ui.screen.player.PlayerDebugInfo
import com.jellyscope.ui.screen.player.PlayerMediaMetadata
import com.jellyscope.ui.screen.player.PlayerPicker
import com.jellyscope.ui.screen.player.PlayerResizeMode
import com.jellyscope.ui.screen.player.PlayerUiState
import com.jellyscope.ui.screen.player.PlaylistUi
import com.jellyscope.ui.screen.player.QueueItemUi
import com.jellyscope.ui.screen.player.UpNextInfo
import com.jellyscope.ui.screen.settings.SettingsUiState
import com.jellyscope.ui.screen.settings.playerBackendChoices
import kotlin.time.Duration.Companion.minutes

internal object PreviewFixtures {
    private val playerBackendPolicy = androidPlayerBackendPolicy()

    val session =
        Session(
            serverUrl = "https://demo.jellyscope.local",
            serverId = "demo-server",
            serverName = "JellyScope Demo",
            userId = "user-1",
            userName = "Demo User",
            accessToken = "preview-token",
            deviceId = "preview-device",
        )

    val serverInfo =
        ServerInfo(
            serverUrl = session.serverUrl,
            serverId = session.serverId,
            serverName = session.serverName,
            version = "10.10.0",
            productName = "Jellyfin",
        )

    val libraries =
        listOf(
            Library(id = "movies", name = "Movies", collectionType = LibraryCollectionType.Movies),
            Library(id = "shows", name = "Northern Lights", collectionType = LibraryCollectionType.TvShows),
        )

    val mediaCards =
        listOf(
            mediaCard(
                id = "movie-1",
                title = "The Long Signal",
                subtitle = "2025",
                kind = MediaCardKind.Movie,
                progressFraction = 0.64f,
                runtimeText = "2h 08m",
                metadataLine = "2025 · 2h 08m · 4K",
                overview = "A quiet sci-fi mystery about a relay station hearing a signal from the edge of known space.",
                genres = listOf("Sci-Fi", "Mystery"),
            ),
            mediaCard(
                id = "episode-1",
                title = "Signal Drift",
                subtitle = "S2E4",
                kind = MediaCardKind.Episode,
                progressFraction = 0.35f,
                seriesName = "Northern Lights",
                episodeLabel = "S2E4",
                runtimeText = "48m",
                metadataLine = "S2E4 · 48m",
                overview = "The crew discovers that every correction to the course creates a new problem.",
                genres = listOf("Drama"),
            ),
            mediaCard(
                id = "series-1",
                title = "Northern Lights",
                subtitle = "3 seasons",
                kind = MediaCardKind.Series,
                unplayedCount = 7,
                runtimeText = "3 seasons",
                metadataLine = "3 seasons · Drama",
                overview = "A slow-burn expedition drama across the Arctic research routes.",
                genres = listOf("Drama", "Adventure"),
            ),
            mediaCard(
                id = "movie-2",
                title = "Harbor City",
                subtitle = "2022",
                kind = MediaCardKind.Movie,
                watched = true,
                isFavorite = true,
                runtimeText = "1h 54m",
                metadataLine = "2022 · 1h 54m · HD",
                overview = "A compact crime story set across one night on the waterfront.",
                genres = listOf("Crime", "Thriller"),
            ),
            mediaCard(
                id = "movie-3",
                title = "Atlas Minor",
                subtitle = "2019",
                kind = MediaCardKind.Movie,
                supported = false,
                runtimeText = "2h 16m",
                metadataLine = "2019 · 2h 16m",
                overview = "A documentary crew follows an ambitious amateur cartographer.",
                genres = listOf("Documentary"),
            ),
        )

    val homeState =
        HomeUiState(
            featured = RowState.Content(mediaCards.take(3)),
            continueWatching = RowState.Content(mediaCards.take(2)),
            nextUp = RowState.Content(mediaCards.drop(1).take(2)),
            recentlyAdded = RowState.Content(mediaCards),
            favorites = RowState.Content(mediaCards.filter { card -> card.isFavorite }),
        )

    val findState =
        FindUiState(
            queryText = "signal",
            selectedGenreNames = listOf("Sci-Fi"),
            runtimeBucket = RuntimeBucket.Under120,
            watchedFilter = WatchedFilter.Any,
            personSuggestions =
                listOf(
                    PersonUi("person-1", "Avery Stone", imageUrl = null),
                    PersonUi("person-2", "Mina Kapoor", imageUrl = null),
                ),
            groupedResults =
                FindResultsUi(
                    movies = mediaCards.filter { card -> card.kind == MediaCardKind.Movie },
                    shows = mediaCards.filter { card -> card.kind == MediaCardKind.Series },
                    episodes = mediaCards.filter { card -> card.kind == MediaCardKind.Episode },
                ),
            selectedResultTab = FindResultTab.All,
            recentSearches = listOf("space", "crime", "4k"),
        )

    val discoverState =
        DiscoverUiState(
            genres =
                DiscoverListState.Content(
                    listOf(
                        DiscoverFacetUi("genre-1", "Sci-Fi", imageUrl = null),
                        DiscoverFacetUi("genre-2", "Drama", imageUrl = null),
                        DiscoverFacetUi("genre-3", "Thriller", imageUrl = null),
                    ),
                ),
            studios =
                DiscoverListState.Content(
                    listOf(
                        DiscoverFacetUi("studio-1", "Northstar Pictures", imageUrl = null),
                        DiscoverFacetUi("studio-2", "Harbor Works", imageUrl = null),
                    ),
                ),
            collections = DiscoverListState.Content(mediaCards.take(3)),
            suggestions =
                DiscoverSuggestionsState.Content(
                    seedItem = mediaCards.first(),
                    items = mediaCards.drop(1),
                ),
            upcoming = DiscoverListState.Content(mediaCards.filter { card -> card.kind == MediaCardKind.Episode }),
        )

    val libraryState =
        LibraryBrowseUiState(
            parentId = "movies",
            collectionType = LibraryCollectionType.Movies,
            sortBy = LibrarySortBy.Name,
            sortOrder = LibrarySortOrder.Ascending,
            filters = LibraryFilterSelection(),
            items = mediaCards,
            totalCount = 42,
            hasMore = true,
            isLoading = false,
            filterOptions =
                LibraryFilterOptions(
                    genres =
                        listOf(
                            LibraryFilterOption("genre-sci-fi", "Sci-Fi", "Sci-Fi"),
                            LibraryFilterOption("genre-drama", "Drama", "Drama"),
                        ),
                    years =
                        listOf(
                            LibraryFilterOption("year-2025", 2025, "2025"),
                            LibraryFilterOption("year-2022", 2022, "2022"),
                        ),
                    ratings = listOf(LibraryFilterOption("rating-pg13", "PG-13", "PG-13")),
                    studios = listOf(LibraryFilterOption("studio-northstar", "Northstar Pictures", "Northstar Pictures")),
                ),
        )

    val overflowingLibraryState =
        libraryState.copy(
            items =
                List(30) { index ->
                    val source = mediaCards[index % mediaCards.size]
                    source.copy(
                        id = "library-preview-$index",
                        title = "${source.title} ${index + 1}",
                    )
                },
            totalCount = 30,
            hasMore = false,
        )

    val collectionState =
        CollectionUiState(
            collectionId = "collection-1",
            items = mediaCards,
            totalCount = mediaCards.size,
            hasMore = false,
            isLoading = false,
        )

    val gridState =
        GridUiState.Content(
            items = mediaCards,
            sort = GridSort.Default,
            row = HomeRow.ContinueWatching,
        )

    val accountState =
        AccountUiState(
            accounts =
                listOf(
                    AccountSession(
                        accountId = "account-1",
                        serverUrl = session.serverUrl,
                        serverId = session.serverId,
                        serverName = session.serverName,
                        userId = session.userId,
                        userName = session.userName,
                        avatarUserId = session.userId,
                        isActive = true,
                    ),
                    AccountSession(
                        accountId = "account-2",
                        serverUrl = "https://family.jellyscope.local",
                        serverId = "family-server",
                        serverName = "Family Server",
                        userId = "user-2",
                        userName = "Alex",
                        avatarUserId = "user-2",
                        isActive = false,
                    ),
                ),
        )

    val settingsState =
        SettingsUiState(
            serverName = session.serverName,
            serverUrl = session.serverUrl,
            userName = session.userName,
            versionName = "0.1.0-alpha1",
            playerBackendChoices = playerBackendChoices(playerBackendPolicy),
            selectedPlayerBackend =
                playerBackendPolicy.normalizePersisted(PlaybackPreferences().defaultPlayerBackend),
            appTheme = AppColorThemeId.Ocean,
            pictureInPictureEnabled = true,
            playbackPreferences =
                PlaybackPreferences(
                    defaultMaxBitrateBps = 20_000_000,
                    preferredAudioLanguage = "eng",
                    preferredSubtitleLanguage = "eng",
                ),
            playerDeviceSettings =
                PlayerDeviceSettings(
                    audioMode = PlayerAudioMode.Auto,
                    hdrMode = PlayerHdrMode.Auto,
                ),
        )

    val trackSelection =
        DetailTrackSelectionUi(
            audioOptions =
                listOf(
                    AudioTrackOption(
                        streamIndex = 1,
                        ordinal = 0,
                        displayName = "English - 5.1",
                        language = "eng",
                        isDefault = true,
                    ),
                    AudioTrackOption(
                        streamIndex = 2,
                        ordinal = 1,
                        displayName = "Commentary",
                        language = "eng",
                        isDefault = false,
                    ),
                ),
            subtitleOptions =
                listOf(
                    SubtitleTrackOption(
                        streamIndex = 3,
                        ordinal = 0,
                        displayName = "English SDH",
                        language = "eng",
                        isDefault = false,
                        isExternal = false,
                    ),
                ),
            defaultAudioStreamIndex = 1,
            defaultSubtitleStreamIndex = null,
        )

    val mediaInfo =
        MediaInfoUi(
            fileLine = "18.4 GB",
            videoLines = listOf("2160p · HEVC · HDR10 · 18 Mbps"),
            audioLines = listOf("English · EAC3 · 5.1"),
            subtitleLines = listOf("English SDH · SRT"),
        )

    val detail =
        DetailUi(
            itemId = "movie-1",
            title = "The Long Signal",
            headerLine = "Movie · 2025",
            metadataLine = "2025 · 2h 08m · 4K · PG-13",
            genresLine = "Sci-Fi · Mystery",
            officialRating = "PG-13",
            communityRating = "8.3",
            criticRatingText = "92%",
            imdbUrl = null,
            tmdbUrl = null,
            tagline = "Some messages are meant to wait.",
            overview = mediaCards.first().overview,
            directedByLine = "Directed by Avery Stone",
            studioLine = "Northstar Pictures",
            castAndCrew =
                listOf(
                    CastAndCrewUi("person-1", "Mina Kapoor", "Commander Vale", MediaPersonType.Actor, imageUrl = null),
                    CastAndCrewUi("person-2", "Avery Stone", "Director", MediaPersonType.Director, imageUrl = null),
                ),
            streamBadges = listOf("4K", "HDR10", "EAC3 5.1"),
            mediaInfo = mediaInfo,
            timeLeftText = "46m left",
            playAction =
                DetailPlayAction(
                    label = DetailPlayLabel.Resume(42.minutes),
                    startPositionTicks = 25_200_000_000L,
                    mediaSourceId = "media-source-1",
                ),
            restartAction =
                DetailPlayAction(
                    label = DetailPlayLabel.StartOver,
                    startPositionTicks = 0L,
                    mediaSourceId = "media-source-1",
                ),
            isWatched = false,
            watchedToggleInFlight = false,
            isFavorite = true,
            favoriteToggleInFlight = false,
            progressFraction = 0.64f,
            trackSelection = trackSelection,
            versions =
                listOf(
                    MediaVersionUi(
                        id = "media-source-1",
                        name = "4K HDR",
                        audioTracks = listOf("English - 5.1", "Commentary"),
                        subtitleTracks = listOf("English SDH"),
                    ),
                ),
            relatedGroups =
                listOf(
                    RelatedGroupUi(
                        kind = RelatedGroupKind.Similar,
                        label = null,
                        items = mediaCards.drop(1),
                    ),
                ),
            relatedLoading = false,
            backdropUrl = null,
            posterUrl = null,
            logoUrl = null,
            trailerUrl = null,
        )

    val episode =
        EpisodeUi(
            itemId = "episode-1",
            title = "Signal Drift",
            seriesTitle = "Northern Lights",
            episodeLabel = "S2E4",
            episodeBadge = "Episode 4",
            metadataLine = "48m · HD",
            overview = mediaCards[1].overview,
            imageUrl = null,
            streamBadges = listOf("1080p", "AAC 5.1"),
            mediaInfo = mediaInfo,
            timeLeftText = "31m left",
            playAction =
                DetailPlayAction(
                    label = DetailPlayLabel.Resume(17.minutes),
                    startPositionTicks = 10_200_000_000L,
                    mediaSourceId = "episode-source-1",
                ),
            restartAction =
                DetailPlayAction(
                    label = DetailPlayLabel.StartOver,
                    startPositionTicks = 0L,
                    mediaSourceId = "episode-source-1",
                ),
            isWatched = false,
            watchedToggleInFlight = false,
            isFavorite = false,
            favoriteToggleInFlight = false,
            progressFraction = 0.35f,
            trackSelection = trackSelection,
        )

    val seriesContent =
        SeriesContentUi(
            series =
                SeriesHeaderUi(
                    itemId = "series-1",
                    title = "Northern Lights",
                    metadataLine = "2023 · 3 seasons · HD",
                    genresLine = "Drama · Adventure",
                    officialRating = "TV-14",
                    communityRating = "8.7",
                    criticRatingText = "89%",
                    overview = mediaCards[2].overview,
                    castAndCrew = detail.castAndCrew,
                    isFavorite = true,
                    favoriteToggleInFlight = false,
                    backdropUrl = null,
                    posterUrl = null,
                ),
            seasons =
                listOf(
                    SeasonUi("season-1", "Season 1", 1, unplayedCount = 0, posterUrl = null),
                    SeasonUi("season-2", "Season 2", 2, unplayedCount = 4, posterUrl = null),
                    SeasonUi("season-3", "Season 3", 3, unplayedCount = 8, posterUrl = null),
                ),
            selectedSeasonId = "season-2",
            episodesState =
                SeasonEpisodesUiState.Content(
                    episodes =
                        listOf(
                            episode,
                            episode.copy(
                                itemId = "episode-2",
                                title = "Whiteout",
                                episodeLabel = "S2E5",
                                progressFraction = null,
                                timeLeftText = null,
                            ),
                        ),
                ),
            focusedEpisode = episode,
            nextUpEpisode = episode,
            seriesPlayEpisode = episode,
            related = mediaCards.filter { card -> card.kind == MediaCardKind.Series || card.kind == MediaCardKind.Movie },
        )

    val playbackState =
        PlaybackState(
            status = PlaybackStatus.Paused,
            positionMs = 3_600_000L,
            durationMs = 7_680_000L,
            bufferedPositionMs = 4_800_000L,
            currentSegment = MediaSegment(MediaSegmentType.Intro, startTicks = 30_000_000L, endTicks = 900_000_000L),
        )

    val playerState =
        PlayerUiState.Content(
            playbackState = playbackState,
            metadata =
                PlayerMediaMetadata(
                    title = detail.title,
                    productionYear = 2025,
                    runtimeMs = 7_680_000L,
                    qualityBadge = "4K HDR",
                    imageUrl = null,
                ),
            audioOptions = trackSelection.audioOptions,
            subtitleOptions = trackSelection.subtitleOptions,
            qualityOptions =
                listOf(
                    QualityOption(maxBitrateBps = null, tier = QualityTier.High),
                    QualityOption(maxBitrateBps = 20_000_000L, tier = QualityTier.High, resolutionWidth = 3_840, resolutionHeight = 2_160),
                    QualityOption(maxBitrateBps = 8_000_000L, tier = QualityTier.Medium, resolutionWidth = 1_920, resolutionHeight = 1_080),
                ),
            selectedAudioStreamIndex = 1,
            selectedSubtitleStreamIndex = 3,
            selectedQualityMaxBitrate = null,
            pickerVisible = PlayerPicker.None,
            playlist =
                PlaylistUi(
                    items =
                        mediaCards.take(3).mapIndexed { index, card ->
                            QueueItemUi(
                                id = card.id,
                                title = card.title,
                                imageUrl = null,
                                seasonNumber = card.episodeLabel?.let { 2 },
                                episodeNumber = card.episodeLabel?.let { index + 1 },
                            )
                        },
                    currentIndex = 0,
                ),
            chapters =
                listOf(
                    Chapter("Opening", 0L),
                    Chapter("Relay Station", 2_400_000_000L),
                    Chapter("Final Approach", 5_400_000_000L),
                ),
            mediaSegments = listOf(MediaSegment(MediaSegmentType.Intro, 30_000_000L, 900_000_000L)),
            currentSegment = MediaSegment(MediaSegmentType.Intro, 30_000_000L, 900_000_000L),
            subtitleRenderInfo =
                SubtitleRenderInfo(
                    status = SubtitleRenderStatus.Active,
                    mode = SubtitleRenderMode.LocalEmbeddedText,
                    streamIndex = 3,
                    label = "English SDH",
                    language = "eng",
                    reason = "Embedded text",
                ),
            resizeMode = PlayerResizeMode.Fit,
            upNext = UpNextInfo("episode-2", "Whiteout", imageUrl = null, index = 1),
            debugInfo =
                PlayerDebugInfo(
                    playMethod = "Direct Play",
                    container = "mkv",
                    videoCodec = "hevc",
                    videoResolution = "3840x2160",
                    audioCodec = "eac3",
                    audioChannels = "5.1",
                    sourceBitrateBps = 18_000_000L,
                    requestCapBitrateBps = 8_000_000L,
                    qualityCapOrigin = PlaybackQualityCapOrigin.SettingsDefault,
                    launchToFirstFrameMs = 1_240L,
                    subtitleStreamIndex = 3,
                    subtitleLabel = "English SDH",
                    subtitleLanguage = "eng",
                    subtitleRenderMode = SubtitleRenderMode.LocalEmbeddedText,
                    subtitleRenderStatus = SubtitleRenderStatus.Active,
                    subtitleStyleable = true,
                    subtitleRenderReason = "Embedded text",
                ),
        )

    fun mediaCard(
        id: String,
        title: String,
        subtitle: String?,
        kind: MediaCardKind,
        progressFraction: Float? = null,
        watched: Boolean = false,
        isFavorite: Boolean = false,
        unplayedCount: Int? = null,
        supported: Boolean = true,
        seriesName: String? = null,
        episodeLabel: String? = null,
        runtimeText: String? = null,
        metadataLine: String? = null,
        overview: String? = null,
        genres: List<String> = emptyList(),
    ): MediaCardUi =
        MediaCardUi(
            id = id,
            title = title,
            subtitle = subtitle,
            progressFraction = progressFraction,
            watched = watched,
            isFavorite = isFavorite,
            unplayedCount = unplayedCount,
            imageUrl = null,
            kind = kind,
            supported = supported,
            year = subtitle?.takeIf { value -> value.length == 4 },
            seriesName = seriesName,
            episodeLabel = episodeLabel,
            runtimeText = runtimeText,
            metadataLine = metadataLine,
            heroMetadataLine = metadataLine,
            overview = overview,
            genres = genres,
            genresLine = genres.joinToString(" · ").takeIf { value -> value.isNotBlank() },
            backdropUrl = null,
            resumePositionTicks = progressFraction?.let { 10_200_000_000L },
        )

    fun playerController() = NoopPlayerController()
}
