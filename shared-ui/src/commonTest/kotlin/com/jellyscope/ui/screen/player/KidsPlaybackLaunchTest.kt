// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaVersion
import com.jellyscope.core.domain.playback.PlaybackInfo
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KidsPlaybackLaunchTest {
    @Test
    fun laterKidsLaunchKeepsExplicitIntentThroughPlanningFailureAndRetry() =
        runPlayerViewModelTest {
            val playbackInfoResults = ArrayDeque<Result<PlaybackInfo>>()
            val fixture =
                playerFixture(
                    playbackInfoResults = playbackInfoResults,
                    detailItems =
                        mapOf(
                            "item-2" to
                                MediaItem(
                                    id = "item-2",
                                    name = "Second",
                                    kind = MediaKind.Movie,
                                    versions =
                                        listOf(
                                            MediaVersion(id = "source-a", name = "Default", mediaStreams = playbackStreams),
                                            MediaVersion(id = "source-b", name = "Chosen", mediaStreams = playbackStreams),
                                        ),
                                ),
                        ),
                    launchPolicy = PlayerLaunchPolicy.KidsSingleAsset,
                )
            runCurrent()
            // A failed PlaybackInfo request falls back to a local DirectPlay plan; a response with no
            // playable stream is the planning failure that actually surfaces as a retryable error.
            playbackInfoResults.addLast(Result.success(noSupportedPlaybackInfo))
            val options =
                PlayerLaunchOptions(
                    startPositionTicks = 300_000_000L,
                    mediaSourceId = "source-b",
                    initialAudioStreamIndex = 3,
                    initialSubtitleSelection = SubtitleSelectionIntent.fromWireIndex(4),
                    launchPolicy = PlayerLaunchPolicy.KidsSingleAsset,
                )

            assertTrue(fixture.viewModel.acceptKidsLaunch("item-2", options))
            runCurrent()
            assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
            val failedLaunchRequests = fixture.repository.requests.count { request -> request.itemId == "item-2" }
            fixture.viewModel.retry()
            runCurrent()

            val launchRequests = fixture.repository.requests.filter { request -> request.itemId == "item-2" }
            assertTrue(failedLaunchRequests > 0 && launchRequests.size > failedLaunchRequests)
            launchRequests.forEach { request ->
                assertEquals(
                    listOf<Any?>("source-b", 300_000_000L, 3, 4),
                    listOf(request.mediaSourceId, request.startTimeTicks, request.audioStreamIndex, request.subtitleStreamIndex),
                )
            }
            fixture.viewModel.dispose()
            runCurrent()
        }
}
