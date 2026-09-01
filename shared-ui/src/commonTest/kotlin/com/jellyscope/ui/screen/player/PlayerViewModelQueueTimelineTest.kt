// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaVersion
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.playback.MediaSegment
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackContentTimeline
import com.jellyscope.core.domain.playback.PlaybackContentTimelineSource
import com.jellyscope.core.domain.playback.PlaybackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PlayerViewModelQueueTimelineTest {
    @Test
    fun autoAdvanceWithStaleGenerationIsIgnored() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(queue = listOf("item-1", "item-2", "item-3"))
                runCurrent()

                val generation =
                    (fixture.viewModel.state.value as PlayerUiState.Content)
                        .autoplayPolicy.playbackGeneration

                // A stale countdown carrying an older generation must not advance.
                fixture.viewModel.playNext(auto = true, expectedGeneration = generation + 1L)
                runCurrent()

                assertEquals(1, fixture.controller.prepareCount)
                assertEquals(
                    0,
                    (fixture.viewModel.state.value as PlayerUiState.Content).playlist?.currentIndex,
                )

                // The matching generation advances normally.
                fixture.viewModel.playNext(auto = true, expectedGeneration = generation)
                runCurrent()

                assertEquals(2, fixture.controller.prepareCount)
                assertEquals(
                    "item-2",
                    fixture.repository.requests
                        .last()
                        .itemId,
                )
                assertEquals(
                    1,
                    (fixture.viewModel.state.value as PlayerUiState.Content).playlist?.currentIndex,
                )
            } finally {
            }
        }

    @Test
    fun playNextReportsWhetherTheQueueSwitchWasAccepted() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(queue = listOf("item-1", "item-2"))
                runCurrent()

                // Accepted: a next item exists. The UI dismisses Up Next on this.
                assertTrue(fixture.viewModel.playNext())
                runCurrent()

                // Refused: the queue is exhausted, so the card must stay on screen.
                assertFalse(fixture.viewModel.playNext())
            } finally {
            }
        }

    @Test
    fun playNextReportsRefusalForASingleItemQueue() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(queue = listOf("item-1"))
                runCurrent()

                assertFalse(fixture.viewModel.playNext())
                assertEquals(1, fixture.controller.prepareCount)
            } finally {
            }
        }

    @Test
    fun previousAtThresholdRestartsTheCurrentItemWithoutSwitchingQueueRows() =
        runPlayerViewModelTest {
            val fixture = playerFixture(queue = listOf("item-1", "item-2"))
            runCurrent()
            assertTrue(fixture.viewModel.playNext())
            runCurrent()
            fixture.controller.playbackStateFlow.value =
                playbackState(PlaybackStatus.Idle, positionMs = 5_000L)
            runCurrent()

            fixture.viewModel.playPrevious()
            runCurrent()

            assertEquals(listOf(0L), fixture.controller.seekPositions)
            assertEquals(2, fixture.controller.prepareCount)
            assertEquals(
                1,
                assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).playlist?.currentIndex,
            )
        }

    @Test
    fun previousBelowThresholdSwitchesToThePriorQueueRow() =
        runPlayerViewModelTest {
            val fixture = playerFixture(queue = listOf("item-1", "item-2"))
            runCurrent()
            assertTrue(fixture.viewModel.playNext())
            runCurrent()
            fixture.controller.playbackStateFlow.value =
                playbackState(PlaybackStatus.Idle, positionMs = 4_999L)
            runCurrent()

            fixture.viewModel.playPrevious()
            assertNull(assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).playbackItemId)
            runCurrent()

            assertEquals(
                "item-1",
                fixture.repository.requests
                    .last()
                    .itemId,
            )
            assertEquals(3, fixture.controller.prepareCount)
            assertTrue(fixture.controller.seekPositions.isEmpty())
        }

    @Test
    fun previousBelowThresholdOnTheFirstQueueRowIsANoOp() =
        runPlayerViewModelTest {
            val fixture = playerFixture(queue = listOf("item-1", "item-2"))
            runCurrent()
            fixture.controller.playbackStateFlow.value =
                playbackState(PlaybackStatus.Idle, positionMs = 4_999L)
            runCurrent()

            fixture.viewModel.playPrevious()
            runCurrent()

            assertEquals(1, fixture.controller.prepareCount)
            assertTrue(fixture.controller.seekPositions.isEmpty())
            assertEquals(
                0,
                assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).playlist?.currentIndex,
            )
        }

    @Test
    fun playNextReportsRefusalForAStaleAutoAdvanceGeneration() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(queue = listOf("item-1", "item-2", "item-3"))
                runCurrent()

                val generation =
                    (fixture.viewModel.state.value as PlayerUiState.Content)
                        .autoplayPolicy.playbackGeneration

                assertFalse(
                    fixture.viewModel.playNext(auto = true, expectedGeneration = generation + 1L),
                )
                runCurrent()

                assertTrue(
                    fixture.viewModel.playNext(auto = true, expectedGeneration = generation),
                )
                runCurrent()
            } finally {
            }
        }

    @Test
    fun playNextReportsRefusalWhenTheStillWatchingGateTakesOver() =
        runPlayerViewModelTest {
            try {
                val queue = (1..STILL_WATCHING_THRESHOLD + 2).map { index -> "item-$index" }
                val fixture = playerFixture(queue = queue)
                runCurrent()

                // Auto-advance until the gate arms; the advance that raises the
                // prompt is a refusal, because the prompt owns the continuation.
                var refusedAt = -1
                repeat(STILL_WATCHING_THRESHOLD) { attempt ->
                    val accepted = fixture.viewModel.playNext(auto = true)
                    runCurrent()
                    if (!accepted && refusedAt < 0) {
                        refusedAt = attempt
                    }
                }

                assertEquals(STILL_WATCHING_THRESHOLD - 1, refusedAt)
                assertTrue(
                    (fixture.viewModel.state.value as PlayerUiState.Content).stillWatchingPrompt,
                )
            } finally {
            }
        }

    @Test
    fun completedLastPlaylistItemEmitsPlaybackEndedAndKeepsFinishState() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(queue = listOf("item-1", "item-2"))
                val playbackEndedEvents = mutableListOf<Unit>()
                backgroundScope.launch {
                    fixture.viewModel.playbackEnded.collect {
                        playbackEndedEvents += Unit
                    }
                }
                runCurrent()

                fixture.viewModel.playQueueItem(1)
                runCurrent()

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Completed, positionMs = 6_000L)
                runCurrent()

                assertEquals(1, playbackEndedEvents.size)
                assertEquals(
                    PlaybackStatus.Completed,
                    (fixture.viewModel.state.value as PlayerUiState.Content).playbackState.status,
                )
            } finally {
            }
        }

    @Test
    fun completedPlaylistItemWaitsForUpNextActionBeforeAdvancing() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(queue = listOf("item-1", "item-2"))
                val playbackEndedEvents = mutableListOf<Unit>()
                backgroundScope.launch {
                    fixture.viewModel.playbackEnded.collect {
                        playbackEndedEvents += Unit
                    }
                }
                runCurrent()

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Completed, positionMs = 6_000L)
                runCurrent()

                assertEquals(0, playbackEndedEvents.size)
                assertEquals(1, fixture.controller.prepareCount)
                assertEquals(
                    PlaybackStatus.Completed,
                    (fixture.viewModel.state.value as PlayerUiState.Content).playbackState.status,
                )
                assertEquals(
                    "item-2",
                    (fixture.viewModel.state.value as PlayerUiState.Content).upNext?.itemId,
                )

                fixture.viewModel.playNext(auto = true)
                runCurrent()

                assertEquals(
                    "item-2",
                    fixture.repository.requests
                        .last()
                        .itemId,
                )
                assertEquals(
                    0L,
                    fixture.repository.requests
                        .last()
                        .startTimeTicks,
                )
                assertEquals(2, fixture.controller.prepareCount)
                assertEquals(
                    1,
                    (fixture.viewModel.state.value as PlayerUiState.Content).playlist?.currentIndex,
                )
            } finally {
            }
        }

    @Test
    fun queueSwitchPublishesUnstableIdentitySynchronouslyThenNewIdentityAfterPlanning() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(queue = listOf("item-1", "item-2", "item-3"))
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 4_000L)
                runCurrent()
                assertEquals(
                    "item-1",
                    (fixture.viewModel.state.value as PlayerUiState.Content).playbackItemId,
                )

                fixture.viewModel.playQueueItem(2)
                // BEFORE the switch coroutine runs: identity is already
                // unstable, so a pending hold-to-seek session cancels instead
                // of racing the next item's plan install.
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).playbackItemId)

                runCurrent()
                assertEquals(
                    "item-3",
                    (fixture.viewModel.state.value as PlayerUiState.Content).playbackItemId,
                )
            } finally {
            }
        }

    @Test
    fun playNextPublishesUnstableIdentitySynchronously() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(queue = listOf("item-1", "item-2"))
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 2_000L)
                runCurrent()

                fixture.viewModel.playNext()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).playbackItemId)

                runCurrent()
                assertEquals(
                    "item-2",
                    (fixture.viewModel.state.value as PlayerUiState.Content).playbackItemId,
                )
            } finally {
            }
        }

    @Test
    fun queueMetadataIsReorderedToQueueOrder() =
        runPlayerViewModelTest {
            try {
                val queue = listOf("item-1", "item-2", "item-3")
                val fixture =
                    playerFixture(
                        queue = queue,
                        queueItems =
                            listOf(
                                mediaItem("item-3", "Third"),
                                mediaItem("item-1", "First"),
                                mediaItem(
                                    id = "item-2",
                                    name = "Second",
                                    primaryTag = "primary-2",
                                    seasonNumber = 2,
                                    episodeNumber = 8,
                                ),
                            ),
                    )
                runCurrent()

                val playlist = (fixture.viewModel.state.value as PlayerUiState.Content).playlist
                assertEquals(
                    queue,
                    fixture.repository.itemIdRequests.single(),
                )
                assertEquals(queue, playlist?.items?.map { item -> item.id })
                assertEquals(listOf("First", "Second", "Third"), playlist?.items?.map { item -> item.title })
                assertEquals(
                    "https://jellyfin.example/Items/item-2/Images/Primary?tag=primary-2&maxWidth=300&quality=90",
                    playlist?.items?.first { item -> item.id == "item-2" }?.imageUrl,
                )
                assertEquals(2, playlist?.items?.first { item -> item.id == "item-2" }?.seasonNumber)
                assertEquals(8, playlist?.items?.first { item -> item.id == "item-2" }?.episodeNumber)
            } finally {
            }
        }

    @Test
    fun queueMetadataFallsBackToItemDetailForBatchMisses() =
        runPlayerViewModelTest {
            try {
                val queue = listOf("item-1", "item-2", "item-3")
                val fixture =
                    playerFixture(
                        queue = queue,
                        queueItems = listOf(mediaItem("item-1", "First")),
                        detailItems =
                            mapOf(
                                "item-2" to mediaItem("item-2", "Second", primaryTag = "primary-2"),
                                "item-3" to mediaItem("item-3", "Third", primaryTag = "primary-3"),
                            ),
                    )
                runCurrent()

                val playlist = (fixture.viewModel.state.value as PlayerUiState.Content).playlist
                assertEquals(listOf("First", "Second", "Third"), playlist?.items?.map { item -> item.title })
                assertEquals(
                    "https://jellyfin.example/Items/item-3/Images/Primary?tag=primary-3&maxWidth=300&quality=90",
                    playlist?.items?.first { item -> item.id == "item-3" }?.imageUrl,
                )
            } finally {
            }
        }

    @Test
    fun unresolvableQueueMetadataDoesNotExposeRawIdAsTitle() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2"),
                        queueItems = listOf(mediaItem("item-1", "First")),
                        detailFailures = setOf("item-2"),
                    )
                runCurrent()

                val playlist = (fixture.viewModel.state.value as PlayerUiState.Content).playlist
                assertEquals("", playlist?.items?.first { item -> item.id == "item-2" }?.title)
            } finally {
            }
        }

    @Test
    fun shuffleQueueKeepsCurrentItemFirstAndPreservesQueueItems() =
        runPlayerViewModelTest {
            try {
                val queue = listOf("item-1", "item-2", "item-3", "item-4")
                val fixture =
                    playerFixture(
                        queue = queue,
                        queueItems =
                            listOf(
                                mediaItem("item-1", "First"),
                                mediaItem("item-2", "Second"),
                                mediaItem("item-3", "Third"),
                                mediaItem("item-4", "Fourth"),
                            ),
                    )
                runCurrent()

                fixture.viewModel.playQueueItem(2)
                runCurrent()
                fixture.viewModel.shuffleQueue()

                val playlist = (fixture.viewModel.state.value as PlayerUiState.Content).playlist
                assertEquals("item-3", playlist?.items?.firstOrNull()?.id)
                assertEquals(0, playlist?.currentIndex)
                assertEquals(queue.toSet(), playlist?.items?.map { item -> item.id }?.toSet())
                assertEquals(queue.size, playlist?.items?.size)
            } finally {
            }
        }

    @Test
    fun upNextUsesOutroWindowAndQueueMetadata() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2"),
                        queueItems =
                            listOf(
                                mediaItem("item-1", "First"),
                                mediaItem("item-2", "Second", primaryTag = "primary-2"),
                            ),
                        mediaSegments =
                            listOf(
                                MediaSegment(
                                    type = MediaSegmentType.Outro,
                                    startTicks = 20_000_000L,
                                    endTicks = 55_000_000L,
                                ),
                            ),
                    ).also { fixtureRef = it }
                runCurrent()

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_900L)
                runCurrent()
                assertEquals<UpNextInfo?>(null, (fixture.viewModel.state.value as PlayerUiState.Content).upNext)

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 2_000L)
                runCurrent()

                assertEquals<UpNextInfo?>(
                    UpNextInfo(
                        itemId = "item-2",
                        title = "Second",
                        imageUrl = "https://jellyfin.example/Items/item-2/Images/Primary?tag=primary-2&maxWidth=300&quality=90",
                        index = 1,
                    ),
                    (fixture.viewModel.state.value as PlayerUiState.Content).upNext,
                )
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun upNextUsesThresholdWindowAndClearsOutsideOrWithoutNextItem() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            var noNextFixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2"),
                        queueItems =
                            listOf(
                                mediaItem("item-1", "First"),
                                mediaItem("item-2", "Second"),
                            ),
                    ).also { fixtureRef = it }
                runCurrent()

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 29_999L)
                runCurrent()
                assertEquals<UpNextInfo?>(null, (fixture.viewModel.state.value as PlayerUiState.Content).upNext)

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 30_000L)
                runCurrent()
                assertEquals<UpNextInfo?>(
                    UpNextInfo(
                        itemId = "item-2",
                        title = "Second",
                        imageUrl = null,
                        index = 1,
                    ),
                    (fixture.viewModel.state.value as PlayerUiState.Content).upNext,
                )

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 10_000L)
                runCurrent()
                assertEquals<UpNextInfo?>(null, (fixture.viewModel.state.value as PlayerUiState.Content).upNext)

                val noNextFixture = playerFixture().also { noNextFixtureRef = it }
                runCurrent()
                noNextFixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 30_000L)
                runCurrent()

                assertEquals<UpNextInfo?>(null, (noNextFixture.viewModel.state.value as PlayerUiState.Content).upNext)
            } finally {
                fixtureRef?.viewModel?.dispose()
                noNextFixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun singletonEpisodePlaybackDerivesUpNextQueueFromChronologicalEpisodes() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                val currentEpisode =
                    mediaItem(
                        id = "episode-1",
                        name = "Episode 1",
                        kind = MediaKind.Episode,
                        seriesId = "series-1",
                        seasonNumber = 1,
                        episodeNumber = 1,
                    )
                val nextEpisode =
                    mediaItem(
                        id = "episode-2",
                        name = "Episode 2",
                        primaryTag = "primary-2",
                        kind = MediaKind.Episode,
                        seriesId = "series-1",
                        seasonNumber = 1,
                        episodeNumber = 2,
                    )
                val seasonTwoEpisode =
                    mediaItem(
                        id = "episode-3",
                        name = "Episode 3",
                        kind = MediaKind.Episode,
                        seriesId = "series-1",
                        seasonNumber = 2,
                        episodeNumber = 1,
                    )
                val fixture =
                    playerFixture(
                        itemId = "episode-1",
                        detailItems =
                            mapOf(
                                "episode-1" to currentEpisode,
                                "episode-2" to nextEpisode,
                                "episode-3" to seasonTwoEpisode,
                            ),
                        seasons =
                            listOf(
                                mediaItem("season-1", "Season 1", kind = MediaKind.Other, episodeNumber = 1),
                                mediaItem("season-2", "Season 2", kind = MediaKind.Other, episodeNumber = 2),
                            ),
                        episodesBySeasonId =
                            mapOf(
                                "season-1" to listOf(currentEpisode, nextEpisode),
                                "season-2" to listOf(seasonTwoEpisode),
                            ),
                    ).also { fixtureRef = it }
                advanceUntilIdle()

                val content = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(listOf("series-1"), fixture.repository.seasonSeriesIds)
                assertEquals(
                    listOf(EpisodeRequest("series-1", "season-1", 1), EpisodeRequest("series-1", "season-2", 2)),
                    fixture.repository.episodeRequests,
                )
                assertEquals(
                    listOf("episode-1", "episode-2", "episode-3"),
                    content.playlist?.items?.map { item -> item.id },
                )

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 30_000L)
                runCurrent()

                assertEquals<UpNextInfo?>(
                    UpNextInfo(
                        itemId = "episode-2",
                        title = "Episode 2",
                        imageUrl = "https://jellyfin.example/Items/episode-2/Images/Primary?tag=primary-2&maxWidth=300&quality=90",
                        index = 1,
                        // Carried through from the queue item so the Up Next card
                        // and queue rows can show the episode number.
                        seasonNumber = 1,
                        episodeNumber = 2,
                    ),
                    (fixture.viewModel.state.value as PlayerUiState.Content).upNext,
                )

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Completed, positionMs = 60_000L)
                advanceUntilIdle()

                assertEquals(
                    "episode-1",
                    fixture.repository.requests
                        .last()
                        .itemId,
                )

                fixture.viewModel.playNext(auto = true)
                advanceUntilIdle()

                assertEquals(
                    "episode-2",
                    fixture.repository.requests
                        .last()
                        .itemId,
                )
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun explicitQueueIsNotOverriddenByChronologicalEpisodeDerivation() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                val currentEpisode =
                    mediaItem(
                        id = "episode-1",
                        name = "Episode 1",
                        kind = MediaKind.Episode,
                        seriesId = "series-1",
                        seasonNumber = 1,
                        episodeNumber = 1,
                    )
                val derivedEpisode =
                    mediaItem(
                        id = "episode-derived",
                        name = "Derived Episode",
                        kind = MediaKind.Episode,
                        seriesId = "series-1",
                        seasonNumber = 1,
                        episodeNumber = 2,
                    )
                val fixture =
                    playerFixture(
                        itemId = "episode-1",
                        queue = listOf("episode-1", "episode-explicit"),
                        queueItems =
                            listOf(
                                mediaItem("episode-1", "Episode 1"),
                                mediaItem("episode-explicit", "Explicit Episode"),
                            ),
                        detailItems = mapOf("episode-1" to currentEpisode),
                        seasons =
                            listOf(
                                mediaItem("season-1", "Season 1", kind = MediaKind.Other, episodeNumber = 1),
                            ),
                        episodesBySeasonId = mapOf("season-1" to listOf(currentEpisode, derivedEpisode)),
                    ).also { fixtureRef = it }
                advanceUntilIdle()

                val content = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(emptyList<String>(), fixture.repository.seasonSeriesIds)
                assertEquals(emptyList<EpisodeRequest>(), fixture.repository.episodeRequests)
                assertEquals(listOf("episode-1", "episode-explicit"), content.playlist?.items?.map { item -> item.id })
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun emptyOrFailingChronologicalEpisodeDerivationLeavesSingletonEpisodeWithoutPlaylist() =
        runPlayerViewModelTest {
            var emptyFixtureRef: PlayerFixture? = null
            var lastEpisodeFixtureRef: PlayerFixture? = null
            var failingFixtureRef: PlayerFixture? = null
            try {
                val episode =
                    mediaItem(
                        id = "episode-1",
                        name = "Episode 1",
                        kind = MediaKind.Episode,
                        seriesId = "series-1",
                        seasonNumber = 1,
                        episodeNumber = 1,
                    )
                val emptyFixture =
                    playerFixture(
                        itemId = "episode-1",
                        detailItems = mapOf("episode-1" to episode),
                    ).also { emptyFixtureRef = it }
                advanceUntilIdle()

                assertEquals(null, (emptyFixture.viewModel.state.value as PlayerUiState.Content).playlist)

                val lastEpisodeFixture =
                    playerFixture(
                        itemId = "episode-1",
                        detailItems = mapOf("episode-1" to episode),
                        seasons =
                            listOf(
                                mediaItem("season-1", "Season 1", kind = MediaKind.Other, episodeNumber = 1),
                            ),
                        episodesBySeasonId = mapOf("season-1" to listOf(episode)),
                    ).also { lastEpisodeFixtureRef = it }
                advanceUntilIdle()

                assertEquals(null, (lastEpisodeFixture.viewModel.state.value as PlayerUiState.Content).playlist)

                val failingFixture =
                    playerFixture(
                        itemId = "episode-1",
                        detailItems = mapOf("episode-1" to episode),
                        seasonsFailure = true,
                    ).also { failingFixtureRef = it }
                advanceUntilIdle()

                assertEquals(null, (failingFixture.viewModel.state.value as PlayerUiState.Content).playlist)
            } finally {
                emptyFixtureRef?.viewModel?.dispose()
                lastEpisodeFixtureRef?.viewModel?.dispose()
                failingFixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun skipCurrentSegmentSeeksToSegmentEnd() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        mediaSegments =
                            listOf(
                                MediaSegment(
                                    type = MediaSegmentType.Intro,
                                    startTicks = 10_000_000L,
                                    endTicks = 30_000_000L,
                                ),
                            ),
                    ).also { fixtureRef = it }
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_500L)
                runCurrent()

                fixture.viewModel.skipCurrentSegment()
                runCurrent()

                assertEquals(3_000L, fixture.controller.seekPositions.single())
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun autoSkipPolicyFiresOncePerSegmentWhilePlayingAndNeverWhilePaused() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        mediaSegments =
                            listOf(
                                MediaSegment(
                                    type = MediaSegmentType.Intro,
                                    startTicks = 10_000_000L,
                                    endTicks = 30_000_000L,
                                ),
                            ),
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(introSkip = SegmentSkipPolicy.AutoSkip),
                            ),
                        workDispatcher = dispatcher,
                    ).also { fixtureRef = it }
                runCurrent()

                // Paused inside the segment: no auto-skip.
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Paused, positionMs = 1_500L)
                runCurrent()
                assertTrue(fixture.controller.seekPositions.isEmpty())

                // Playing inside (resume-into-segment): fires exactly once.
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_500L)
                runCurrent()
                assertEquals(listOf(3_000L), fixture.controller.seekPositions)

                // Still inside on the next tick: no repeat.
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_600L)
                runCurrent()
                // Seeking back into the already-skipped segment: no re-fire.
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 2_000L)
                runCurrent()
                assertEquals(1, fixture.controller.seekPositions.size)
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun skipPromptSegmentFollowsPerTypePolicy() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            var fixtureRef: PlayerFixture? = null
            try {
                val outroSegment =
                    MediaSegment(
                        type = MediaSegmentType.Outro,
                        startTicks = 50_000_000L,
                        endTicks = 70_000_000L,
                    )
                val fixture =
                    playerFixture(
                        mediaSegments =
                            listOf(
                                MediaSegment(
                                    type = MediaSegmentType.Intro,
                                    startTicks = 10_000_000L,
                                    endTicks = 30_000_000L,
                                ),
                                outroSegment,
                            ),
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(introSkip = SegmentSkipPolicy.Ignore),
                            ),
                        workDispatcher = dispatcher,
                    ).also { fixtureRef = it }
                runCurrent()

                // Ignored type: the segment is current but never prompts.
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_500L)
                runCurrent()
                val ignored = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(MediaSegmentType.Intro, ignored.currentSegment?.type)
                assertNull(ignored.skipPromptSegment)
                assertTrue(fixture.controller.seekPositions.isEmpty())

                // Default Ask type keeps prompting as today.
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 5_500L)
                runCurrent()
                val asked = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(outroSegment, asked.skipPromptSegment)
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun autoSkipSessionMemoryResetsOnQueueSwitch() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2"),
                        mediaSegments =
                            listOf(
                                MediaSegment(
                                    type = MediaSegmentType.Intro,
                                    startTicks = 10_000_000L,
                                    endTicks = 30_000_000L,
                                ),
                            ),
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(introSkip = SegmentSkipPolicy.AutoSkip),
                            ),
                        workDispatcher = dispatcher,
                    ).also { fixtureRef = it }
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_500L)
                runCurrent()
                assertEquals(listOf(3_000L), fixture.controller.seekPositions)

                fixture.viewModel.playQueueItem(1)
                runCurrent()

                // The same-shaped segment on the next item auto-skips again:
                // the once-per-session memory cleared on the new playback start.
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_450L)
                runCurrent()
                assertEquals(listOf(3_000L, 3_000L), fixture.controller.seekPositions)
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun interimControllerEmissionsDuringQueueSwitchCannotRepublishTheOutgoingIdentity() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(mainDispatcher)
            // A separate scheduler keeps the switch's planning suspended while
            // the main dispatcher processes interim controller emissions (an
            // explicit scheduler: dispatchers created after setMain would
            // otherwise inherit Main's scheduler and run eagerly).
            val workDispatcher = StandardTestDispatcher(TestCoroutineScheduler())

            // Planning hops between the two dispatchers several times, so a
            // single alternation leaves it mid-flight.
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2", "item-3"),
                        workDispatcher = workDispatcher,
                    ).also { fixtureRef = it }
                drainPlayerViewModelSchedulers(workDispatcher)
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 4_000L)
                runCurrent()
                assertEquals(
                    "item-1",
                    (fixture.viewModel.state.value as PlayerUiState.Content).playbackItemId,
                )

                fixture.viewModel.playQueueItem(2)
                runCurrent()
                // The stop() analog: an interim Idle emission arrives while
                // planning is suspended. Identity must stay null.
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Idle)
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).playbackItemId)

                drainPlayerViewModelSchedulers(workDispatcher)
                assertEquals(
                    "item-3",
                    (fixture.viewModel.state.value as PlayerUiState.Content).playbackItemId,
                )
            } finally {
                fixtureRef?.viewModel?.dispose()
                drainPlayerViewModelSchedulers(workDispatcher)
                Dispatchers.resetMain()
            }
        }

    @Test
    fun queueSwitchCancelsAnInFlightReplanSoItCannotInstallAStalePlan() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(mainDispatcher)
            val workDispatcher = StandardTestDispatcher(TestCoroutineScheduler())

            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2"),
                        workDispatcher = workDispatcher,
                    ).also { fixtureRef = it }
                drainPlayerViewModelSchedulers(workDispatcher)
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 10_000L)
                runCurrent()
                val prepareCountBefore = fixture.controller.prepareCount

                // The quality replan suspends on the work scheduler...
                fixture.viewModel.selectQuality(maxBitrateBps = 8_000_000L)
                runCurrent()
                // ...and the queue switch begins before it completes. The
                // stale replan targets the OUTGOING item and must be
                // cancelled, not left to install its plan mid-switch.
                fixture.viewModel.playQueueItem(1)
                drainPlayerViewModelSchedulers(workDispatcher)

                assertEquals(prepareCountBefore + 1, fixture.controller.prepareCount)
                assertEquals(
                    "item-2",
                    (fixture.viewModel.state.value as PlayerUiState.Content).playbackItemId,
                )
            } finally {
                fixtureRef?.viewModel?.dispose()
                drainPlayerViewModelSchedulers(workDispatcher)
                Dispatchers.resetMain()
            }
        }

    @Test
    fun queueSwitchRevokesInstalledSeekabilityBeforeReplacementPlanning() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(mainDispatcher)
            val workDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
            val boundedItem =
                MediaItem(
                    id = "item-1",
                    name = "Item",
                    kind = MediaKind.Movie,
                    versions =
                        listOf(
                            MediaVersion(
                                id = "source-1",
                                name = "Source",
                                mediaStreams = playbackStreams,
                                runtime = 90.seconds,
                            ),
                        ),
                )
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2"),
                        detailItems = mapOf("item-1" to boundedItem),
                        workDispatcher = workDispatcher,
                    ).also { fixtureRef = it }
                drainPlayerViewModelSchedulers(workDispatcher)
                assertTrue(assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).isSeekable)

                fixture.viewModel.playQueueItem(1)

                val switching = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertFalse(switching.isSeekable)
                assertNull(switching.playbackItemId)
            } finally {
                fixtureRef?.viewModel?.dispose()
                drainPlayerViewModelSchedulers(workDispatcher)
                Dispatchers.resetMain()
            }
        }

    @Test
    fun returnedMediaSourceOwnsTheBoundedTimelineInsteadOfTheRequestedSourceOrItemFallback() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            var fixtureRef: PlayerFixture? = null
            try {
                val detailItem =
                    MediaItem(
                        id = "item-1",
                        name = "Item",
                        kind = MediaKind.Movie,
                        runtime = 120.seconds,
                        versions =
                            listOf(
                                MediaVersion(
                                    id = "source-1",
                                    name = "Requested",
                                    mediaStreams = playbackStreams,
                                    runtime = 60.seconds,
                                ),
                                MediaVersion(
                                    id = "source-2",
                                    name = "Resolved",
                                    mediaStreams = playbackStreams,
                                    runtime = 90.seconds,
                                ),
                            ),
                    )
                val resolvedPlaybackInfo =
                    directPlayPlaybackInfo.copy(
                        mediaSources =
                            listOf(
                                directPlayPlaybackInfo.mediaSources.single().copy(id = "source-2"),
                            ),
                    )
                val fixture =
                    playerFixture(
                        playbackInfo = resolvedPlaybackInfo,
                        detailItems = mapOf("item-1" to detailItem),
                        workDispatcher = dispatcher,
                    ).also { fixtureRef = it }
                runCurrent()

                val timeline = assertIs<PlaybackContentTimeline.BoundedVod>(fixture.controller.preparedPlan?.contentTimeline)
                assertEquals(90.seconds.inWholeMilliseconds, timeline.durationMs)
                assertEquals(PlaybackContentTimelineSource.SelectedMediaSource, timeline.source)
                assertTrue(assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).isSeekable)
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun unknownOrUnboundedInstalledTimelineIsNotPublishedAsSeekable() =
        runPlayerViewModelTest {
            val fixture = playerFixture()
            runCurrent()

            assertEquals(
                PlaybackContentTimeline.UnknownOrUnbounded,
                fixture.controller.preparedPlan?.contentTimeline,
            )
            assertFalse(assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).isSeekable)

            fixture.viewModel.dispose()
        }

    @Test
    fun autoSkippedOutroSeeksInItemWithoutAdvancingTheQueue() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2"),
                        mediaSegments =
                            listOf(
                                MediaSegment(
                                    type = MediaSegmentType.Outro,
                                    startTicks = 50_000_000L,
                                    endTicks = 70_000_000L,
                                ),
                            ),
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(outroSkip = SegmentSkipPolicy.AutoSkip),
                            ),
                        workDispatcher = dispatcher,
                    ).also { fixtureRef = it }
                runCurrent()
                val requestsBefore = fixture.repository.requests.size
                val playsBefore = fixture.controller.playCount

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 5_500L)
                runCurrent()

                // Auto-skip is a plain in-item seek: queue advancement stays
                // owned by the normal Completed path and the Still Watching gate.
                assertEquals(listOf(7_000L), fixture.controller.seekPositions)
                assertEquals(requestsBefore, fixture.repository.requests.size)
                assertEquals(playsBefore, fixture.controller.playCount)
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun stillWatchingPromptBlocksThirdAutoAdvanceAndConfirmContinues() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2", "item-3", "item-4"),
                        queueItems =
                            listOf(
                                mediaItem("item-1", "First"),
                                mediaItem("item-2", "Second"),
                                mediaItem("item-3", "Third"),
                                mediaItem("item-4", "Fourth"),
                            ),
                    ).also { fixtureRef = it }
                runCurrent()

                fixture.viewModel.playNext(auto = true)
                runCurrent()
                fixture.viewModel.playNext(auto = true)
                runCurrent()
                fixture.viewModel.playNext(auto = true)
                runCurrent()

                val promptContent = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(true, promptContent.stillWatchingPrompt)
                assertEquals(
                    "item-3",
                    fixture.repository.requests
                        .last()
                        .itemId,
                )

                fixture.viewModel.confirmStillWatching()
                runCurrent()

                val confirmedContent = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(false, confirmedContent.stillWatchingPrompt)
                assertEquals(
                    "item-4",
                    fixture.repository.requests
                        .last()
                        .itemId,
                )
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun stillWatchingPromptNeverInterruptsWhenThePreferenceIsOff() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2", "item-3", "item-4"),
                        queueItems =
                            listOf(
                                mediaItem("item-1", "First"),
                                mediaItem("item-2", "Second"),
                                mediaItem("item-3", "Third"),
                                mediaItem("item-4", "Fourth"),
                            ),
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(stillWatchingPrompt = false),
                            ),
                    ).also { fixtureRef = it }
                runCurrent()

                // Three consecutive automatic advances: the same count that trips the
                // gate when the preference is on must sail straight through here.
                fixture.viewModel.playNext(auto = true)
                runCurrent()
                fixture.viewModel.playNext(auto = true)
                runCurrent()
                fixture.viewModel.playNext(auto = true)
                runCurrent()

                val content = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(false, content.stillWatchingPrompt)
                assertEquals(
                    "item-4",
                    fixture.repository.requests
                        .last()
                        .itemId,
                )
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun naturalCompletionKeepsPlayerOpenForStillWatchingAndEndsAfterConfirmation() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2", "item-3", "item-4"),
                        queueItems =
                            listOf(
                                mediaItem("item-1", "First"),
                                mediaItem("item-2", "Second"),
                                mediaItem("item-3", "Third"),
                                mediaItem("item-4", "Fourth"),
                            ),
                    ).also { fixtureRef = it }
                val playbackEndedEvents = mutableListOf<Unit>()
                backgroundScope.launch {
                    fixture.viewModel.playbackEnded.collect {
                        playbackEndedEvents += Unit
                    }
                }
                runCurrent()

                repeat(3) { index ->
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Playing, positionMs = index * 1_000L)
                    runCurrent()
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Completed, positionMs = (index + 1) * 1_000L)
                    runCurrent()
                    fixture.viewModel.playNext(auto = true)
                    runCurrent()
                }

                val promptContent = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(true, promptContent.stillWatchingPrompt)
                assertEquals(PlaybackStatus.Completed, promptContent.playbackState.status)
                assertEquals(0, playbackEndedEvents.size)
                assertEquals(
                    "item-3",
                    fixture.repository.requests
                        .last()
                        .itemId,
                )

                fixture.viewModel.confirmStillWatching()
                runCurrent()
                assertEquals(
                    "item-4",
                    fixture.repository.requests
                        .last()
                        .itemId,
                )

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 4_000L)
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Completed, positionMs = 5_000L)
                runCurrent()

                assertEquals(1, playbackEndedEvents.size)
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }
}
