// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui.preview

import com.jellyscope.core.domain.model.AccountSession
import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibraryFilterSelection
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.model.ServerInfo
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.AudioTrackOption
import com.jellyscope.core.domain.playback.DeviceDecodingCapabilities
import com.jellyscope.core.domain.playback.EffectivePlayerDevicePolicy
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
import com.jellyscope.ui.screen.discover.DiscoverFacetUi
import com.jellyscope.ui.screen.discover.DiscoverListState
import com.jellyscope.ui.screen.discover.DiscoverSuggestionsState
import com.jellyscope.ui.screen.discover.DiscoverUiState
import com.jellyscope.ui.screen.find.FindResultsUi
import com.jellyscope.ui.screen.find.FindUiState
import com.jellyscope.ui.screen.find.PersonUi
import com.jellyscope.ui.screen.grid.GridSort
import com.jellyscope.ui.screen.grid.GridUiState
import com.jellyscope.ui.screen.home.HomeRow
import com.jellyscope.ui.screen.home.HomeUiState
import com.jellyscope.ui.screen.home.RowState
import com.jellyscope.ui.screen.library.LibraryBrowseUiState
import com.jellyscope.ui.screen.player.PlayerDebugInfo
import com.jellyscope.ui.screen.player.PlayerMediaMetadata
import com.jellyscope.ui.screen.player.PlayerPicker
import com.jellyscope.ui.screen.player.PlayerUiState
import com.jellyscope.ui.screen.settings.SettingsUiState
import com.jellyscope.ui.screen.settings.playerBackendChoices

internal object TvPreviewFixtures {
    private val playerBackendPolicy = androidPlayerBackendPolicy()

    val session =
        Session(
            serverUrl = "https://demo.jellyscope.local",
            serverId = "demo-server",
            serverName = "JellyScope Demo",
            userId = "user-1",
            userName = "Demo User",
            accessToken = "preview-token",
            deviceId = "preview-tv",
        )

    val serverInfo =
        ServerInfo(
            serverUrl = session.serverUrl,
            serverId = session.serverId,
            serverName = session.serverName,
            version = "10.10.0",
            productName = "Jellyfin",
        )

    val mediaCards =
        listOf(
            mediaCard(
                id = "movie-1",
                title = "The Long Signal",
                subtitle = "2025",
                kind = MediaCardKind.Movie,
                progressFraction = 0.64f,
                isFavorite = true,
                runtimeText = "2h 08m",
                metadataLine = "2025 · 2h 08m · 4K",
                overview = "A relay station hears a signal from the edge of known space.",
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
                overview = "Every course correction creates a new problem.",
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
                overview = "An expedition drama across Arctic research routes.",
                genres = listOf("Drama", "Adventure"),
            ),
            mediaCard(
                id = "movie-2",
                title = "Harbor City",
                subtitle = "2022",
                kind = MediaCardKind.Movie,
                watched = true,
                runtimeText = "1h 54m",
                metadataLine = "2022 · 1h 54m · HD",
                overview = "A compact crime story on the waterfront.",
                genres = listOf("Crime", "Thriller"),
            ),
        )

    val homeState =
        HomeUiState(
            featured = RowState.Content(mediaCards.take(3)),
            continueWatching = RowState.Content(mediaCards.take(2)),
            nextUp = RowState.Content(mediaCards.drop(1)),
            recentlyAdded = RowState.Content(mediaCards),
            favorites = RowState.Content(mediaCards.filter { item -> item.isFavorite }),
        )

    val findState =
        FindUiState(
            queryText = "signal",
            personSuggestions = listOf(PersonUi("person-1", "Mina Kapoor", null)),
            groupedResults =
                FindResultsUi(
                    movies = mediaCards.filter { item -> item.kind == MediaCardKind.Movie },
                    shows = mediaCards.filter { item -> item.kind == MediaCardKind.Series },
                    episodes = mediaCards.filter { item -> item.kind == MediaCardKind.Episode },
                ),
            recentSearches = listOf("space", "crime", "4k"),
        )

    val discoverState =
        DiscoverUiState(
            genres =
                DiscoverListState.Content(
                    listOf(
                        DiscoverFacetUi("genre-1", "Sci-Fi", null),
                        DiscoverFacetUi("genre-2", "Drama", null),
                        DiscoverFacetUi("genre-3", "Thriller", null),
                    ),
                ),
            studios =
                DiscoverListState.Content(
                    listOf(
                        DiscoverFacetUi("studio-1", "Northstar Pictures", null),
                        DiscoverFacetUi("studio-2", "Harbor Works", null),
                    ),
                ),
            collections = DiscoverListState.Content(mediaCards.take(3)),
            suggestions = DiscoverSuggestionsState.Content(seedItem = mediaCards.first(), items = mediaCards.drop(1)),
            upcoming = DiscoverListState.Content(mediaCards.filter { item -> item.kind == MediaCardKind.Episode }),
        )

    val libraryState =
        LibraryBrowseUiState(
            parentId = "movies",
            collectionType = LibraryCollectionType.Movies,
            filters = LibraryFilterSelection(),
            items = mediaCards,
            totalCount = 40,
            hasMore = true,
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
                        serverUrl = "https://living-room-jellyfin.example.net:8920/very/long/path",
                        serverId = "living-room-server",
                        serverName = "Living Room Jellyfin Server",
                        userId = "user-2",
                        userName = "Alexandra Longname",
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
            playbackPreferences =
                PlaybackPreferences(
                    defaultMaxBitrateBps = 20_000_000,
                    preferredAudioLanguage = "eng",
                    preferredSubtitleLanguage = "eng",
                    introSkip = SegmentSkipPolicy.AutoSkip,
                    outroSkip = SegmentSkipPolicy.Ask,
                    recapSkip = SegmentSkipPolicy.Ignore,
                    previewSkip = SegmentSkipPolicy.Ask,
                    commercialSkip = SegmentSkipPolicy.AutoSkip,
                ),
            playerDeviceSettings =
                PlayerDeviceSettings(
                    audioMode = PlayerAudioMode.PassthroughWhenSupported,
                    hdrMode = PlayerHdrMode.PreferSdr,
                    matchDisplayRefreshRate = true,
                ),
            playerDevicePolicy =
                EffectivePlayerDevicePolicy(
                    settings = PlayerDeviceSettings(),
                    capabilities =
                        DeviceDecodingCapabilities(
                            videoCodecs = listOf("hevc", "av1", "h264"),
                            audioCodecs = listOf("eac3", "ac3", "aac"),
                            supportsDolbyVision = true,
                            audioPassthroughCodecs = listOf("eac3", "ac3"),
                            maxAudioChannels = 8,
                            supportsHdr = true,
                        ),
                    effectiveAudioMode = PlayerAudioMode.PassthroughWhenSupported,
                    effectiveHdrMode = PlayerHdrMode.PreferSdr,
                    audioModeSupported = true,
                    hdrModeSupported = true,
                    audioCodecs = listOf("eac3", "ac3", "aac"),
                    maxAudioChannels = 8,
                    maxAudioChannelsByCodec =
                        mapOf(
                            "eac3" to 8,
                            "ac3" to 6,
                            "aac" to 8,
                        ),
                ),
            openSubtitlesApiKey = "preview-opensubtitles-key",
        )

    val playbackState =
        PlaybackState(
            status = PlaybackStatus.Paused,
            positionMs = 3_600_000L,
            durationMs = 7_680_000L,
            bufferedPositionMs = 4_800_000L,
        )

    val playerState =
        PlayerUiState.Content(
            playbackState = playbackState,
            metadata =
                PlayerMediaMetadata(
                    title = "The Long Signal",
                    productionYear = 2025,
                    runtimeMs = 7_680_000L,
                    qualityBadge = "4K HDR",
                    imageUrl = null,
                ),
            audioOptions =
                listOf(
                    AudioTrackOption(1, 0, "English - 5.1", "eng", isDefault = true),
                    AudioTrackOption(2, 1, "Commentary", "eng", isDefault = false),
                ),
            subtitleOptions =
                listOf(
                    SubtitleTrackOption(3, 0, "English SDH", "eng", isDefault = false, isExternal = false),
                ),
            qualityOptions =
                listOf(
                    QualityOption(maxBitrateBps = null, tier = QualityTier.High),
                    QualityOption(maxBitrateBps = 20_000_000L, tier = QualityTier.High, resolutionWidth = 3_840, resolutionHeight = 2_160),
                    QualityOption(maxBitrateBps = 8_000_000L, tier = QualityTier.Medium, resolutionWidth = 1_920, resolutionHeight = 1_080),
                ),
            selectedAudioStreamIndex = 1,
            selectedSubtitleStreamIndex = 3,
            pickerVisible = PlayerPicker.None,
            subtitleRenderInfo =
                SubtitleRenderInfo(
                    status = SubtitleRenderStatus.Active,
                    mode = SubtitleRenderMode.LocalEmbeddedText,
                    streamIndex = 3,
                    label = "English SDH",
                    language = "eng",
                    reason = "Embedded text",
                ),
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
