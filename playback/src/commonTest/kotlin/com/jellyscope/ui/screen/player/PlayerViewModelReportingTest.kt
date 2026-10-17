// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.playback.MediaSegment
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackInfo
import com.jellyscope.core.domain.playback.PlaybackMediaSourceInfo
import com.jellyscope.core.domain.playback.PlaybackProgressEvent
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.StreamMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlayerViewModelReportingTest {
    @Test
    fun reportsStartAndPeriodicProgressWhilePlaying() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                assertEquals<List<Report>>(listOf(Report.Start(positionMs = 1_000L)), fixture.reporter.reports)

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 11_000L)
                advanceTimeBy(10_000L)
                runCurrent()

                assertEquals(
                    Report.Progress(
                        positionMs = 11_000L,
                        isPaused = false,
                        eventName = PlaybackProgressEvent.TimeUpdate,
                    ),
                    fixture.reporter.reports.last(),
                )

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun positionTicksStayOnPlaybackStateFlowWithoutRepublishingContent() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(confirmInitialAudio = false)
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()
                val contentAfterStatus = fixture.viewModel.state.value as PlayerUiState.Content

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 2_000L)
                runCurrent()

                assertSame(contentAfterStatus, fixture.viewModel.state.value)
                assertEquals(2_000L, fixture.viewModel.playbackState.value.positionMs)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun reportsPauseUnpauseSeekAndCompletionEdges() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Paused, positionMs = 2_000L)
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 2_500L)
                runCurrent()
                fixture.viewModel.seekTo(5_000L)
                runCurrent()
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Completed, positionMs = 6_000L)
                runCurrent()

                assertEquals(5_000L, fixture.controller.seekPositions.single())
                assertEquals<List<Report>>(
                    listOf(
                        Report.Start(positionMs = 1_000L),
                        Report.Progress(
                            positionMs = 2_000L,
                            isPaused = true,
                            eventName = PlaybackProgressEvent.Pause,
                        ),
                        Report.Progress(
                            positionMs = 2_500L,
                            isPaused = false,
                            eventName = PlaybackProgressEvent.Unpause,
                        ),
                        Report.Progress(
                            positionMs = 5_000L,
                            isPaused = false,
                            eventName = PlaybackProgressEvent.TimeUpdate,
                        ),
                        Report.Stopped(positionMs = 6_000L),
                    ),
                    fixture.reporter.reports,
                )
            } finally {
            }
        }

    @Test
    fun usesPlaybackInfoTranscodePlanAndServerPlaySessionIdForReporting() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackInfo =
                            PlaybackInfo(
                                playSessionId = "server-play-session-1",
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
                                            mediaStreams = playbackStreams,
                                        ),
                                    ),
                            ),
                    )
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                assertEquals(StreamMode.Transcode, fixture.controller.preparedPlan?.streamMode)
                assertEquals(
                    "https://jellyfin.example/Videos/item-1/master.m3u8",
                    fixture.controller.preparedPlan?.streamUrl,
                )
                assertEquals<List<Report>>(
                    listOf(
                        Report.Start(
                            positionMs = 1_000L,
                            playSessionId = "server-play-session-1",
                            playMethod = "Transcode",
                        ),
                    ),
                    fixture.reporter.reports,
                )

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun playQueueItemReportsStopThenStartsSelectedId() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(queue = listOf("item-1", "item-2", "item-3"))
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 4_000L)
                runCurrent()

                fixture.viewModel.playQueueItem(2)
                runCurrent()

                assertEquals(Report.Stopped(positionMs = 4_000L), fixture.reporter.reports.last())
                assertEquals(
                    "item-3",
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
                assertEquals(2, fixture.controller.playCount)
            } finally {
            }
        }

    @Test
    fun autoSkipAtSessionStartReportsStartBeforeTheSkipTimeUpdate() =
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
                                    startTicks = 0L,
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

                // The very first reportable state is Playing inside the auto
                // segment: Start must be established (at the true pre-skip
                // position) before the skip's TimeUpdate serializes.
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 500L)
                runCurrent()

                assertEquals(listOf(3_000L), fixture.controller.seekPositions)
                val start =
                    fixture.reporter.reports
                        .filterIsInstance<Report.Start>()
                        .first()
                assertEquals(500L, start.positionMs)
                val startIndex = fixture.reporter.reports.indexOf(start)
                val skipUpdateIndex =
                    fixture.reporter.reports.indexOfFirst { report ->
                        report is Report.Progress && report.positionMs == 3_000L
                    }
                assertTrue(
                    skipUpdateIndex > startIndex,
                    "skip TimeUpdate must serialize after Start: ${fixture.reporter.reports}",
                )
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
                Dispatchers.resetMain()
            }
        }
}
