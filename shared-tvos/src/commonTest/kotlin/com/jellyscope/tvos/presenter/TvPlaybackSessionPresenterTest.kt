// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.jellyscope.core.data.local.PlaybackSelectionStore
import com.jellyscope.core.domain.action.SavePlaybackSelectionAction
import com.jellyscope.core.domain.action.SaveSubtitleSelectionAction
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.PlaybackSelection
import com.jellyscope.core.domain.model.PlaybackSelectionKey
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.DirectPlayPlanner
import com.jellyscope.core.domain.playback.MediaSegment
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlannedSubtitle
import com.jellyscope.core.domain.playback.PlaybackAction
import com.jellyscope.core.domain.playback.PlaybackActionNoticeReason
import com.jellyscope.core.domain.playback.PlaybackClientTrigger
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackHealthGuidancePolicy
import com.jellyscope.core.domain.playback.PlaybackHealthGuidanceReason
import com.jellyscope.core.domain.playback.PlaybackHealthMeasurementCapabilities
import com.jellyscope.core.domain.playback.PlaybackInfoPlanner
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackProgressEvent
import com.jellyscope.core.domain.playback.PlaybackQualityCapOrigin
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleDeliveryMethod
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.millisecondsToTicks
import com.jellyscope.core.domain.usecase.GetChronologicalEpisodeQueueUseCase
import com.jellyscope.core.domain.usecase.GetItemDetailUseCase
import com.jellyscope.core.domain.usecase.GetMediaSegmentsUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackLaunchContextUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackPreferencesUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackSelectionUseCase
import com.jellyscope.core.domain.usecase.GetSeasonEpisodesUseCase
import com.jellyscope.core.domain.usecase.GetSeriesSeasonsUseCase
import com.jellyscope.core.domain.usecase.GetSubtitleSelectionUseCase
import com.jellyscope.core.playback.PlaybackDiagnosticsContext
import com.jellyscope.core.playback.PlaybackReportingQueue
import com.jellyscope.core.playback.PlaybackReportingSession
import com.jellyscope.core.playback.PlaybackStopSettlementRegistry
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.LogScrubber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvPlaybackSessionPresenterTest {
    @Test
    fun factoryIsDeferredUntilStartAndConstructsOnceOnWork() =
        runTest {
            val workDispatcher = TvRecordingDispatcher(StandardTestDispatcher(testScheduler))
            val installedController = FakeTvPlayerController()
            var factoryCount = 0
            val fixture =
                fixture(
                    workDispatcher = workDispatcher,
                    playerControllerFactory = {
                        check(workDispatcher.running)
                        factoryCount += 1
                        installedController
                    },
                )

            assertEquals(0, factoryCount)
            assertNull(fixture.presenter.platformPlayer)
            assertFalse(fixture.presenter.state.value.playerInstalled)
            assertTrue(fixture.repository.planRequests.isEmpty())

            fixture.presenter.start()
            runCurrent()

            assertEquals(1, factoryCount)
            assertTrue(fixture.presenter.state.value.playerInstalled)
            assertEquals(1, installedController.preparedPlans.size)
            fixture.presenter.start()
            runCurrent()
            assertEquals(1, factoryCount)
            fixture.presenter.close()
        }

    @Test
    fun factoryFailurePublishesCreationFailureWithoutPlanningOrReporting() =
        runTest {
            var factoryCount = 0
            val fixture =
                fixture(
                    playerControllerFactory = {
                        factoryCount += 1
                        error("factory failure")
                    },
                )

            fixture.presenter.start()
            runCurrent()

            assertEquals(1, factoryCount)
            assertFalse(fixture.presenter.state.value.playerInstalled)
            assertEquals(TvPlaybackPhase.Failed, fixture.presenter.state.value.phase)
            assertEquals(PlaybackError.Unknown, fixture.presenter.state.value.error)
            assertTrue(fixture.repository.planRequests.isEmpty())
            assertTrue(fixture.reporter.reports.isEmpty())
            fixture.presenter.start()
            runCurrent()
            assertEquals(1, factoryCount)
            fixture.presenter.close()
        }

    @Test
    fun closeLosingLateCandidateIsReleasedOnceWithoutReporting() =
        runTest {
            val workerScheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
            val lateCandidate = FakeTvPlayerController()
            val fixture =
                fixture(
                    workDispatcher = StandardTestDispatcher(workerScheduler),
                    playerControllerFactory = { lateCandidate },
                )

            fixture.presenter.start()
            runCurrent()
            workerScheduler.runCurrent()
            fixture.presenter.close()
            runCurrent()

            assertFalse(fixture.presenter.state.value.playerInstalled)
            assertEquals(1, lateCandidate.releaseCount)
            assertTrue(lateCandidate.preparedPlans.isEmpty())
            assertEquals(0, lateCandidate.playCount)
            assertTrue(fixture.repository.planRequests.isEmpty())
            assertTrue(fixture.reporter.reports.isEmpty())
            fixture.presenter.close()
            assertEquals(1, lateCandidate.releaseCount)
            val rejectedStart = fixture.reportingQueue.enqueueStart(postCloseReportingSession(), positionMs = 0L)
            assertTrue(rejectedStart.isCompleted)
            assertFalse(rejectedStart.await())
            runCurrent()
            assertTrue(fixture.reporter.reports.isEmpty())
        }

    @Test
    fun prepareFailureAfterTransferRetainsInstalledControllerForClose() =
        runTest {
            val installedController =
                FakeTvPlayerController().apply {
                    prepareFailure = IllegalStateException("prepare failure")
                }
            val fixture = fixture(playerControllerFactory = { installedController })

            fixture.presenter.start()
            runCurrent()

            assertTrue(fixture.presenter.state.value.playerInstalled)
            assertEquals(TvPlaybackPhase.Failed, fixture.presenter.state.value.phase)
            assertEquals(PlaybackError.Unknown, fixture.presenter.state.value.error)
            assertEquals(0, installedController.releaseCount)
            assertEquals(1, installedController.preparedPlans.size)

            fixture.presenter.close()
            runCurrent()

            assertEquals(1, installedController.releaseCount)
            assertTrue(fixture.reporter.reports.isEmpty())
        }

    @Test
    fun diagnosticsProducerIsAdmittedCorrelatedAndRecordsControllerFailure() =
        runTest {
            val captured = mutableListOf<Triple<Severity, String, Throwable?>>()
            val writer =
                object : LogWriter() {
                    override fun log(
                        severity: Severity,
                        message: String,
                        tag: String,
                        throwable: Throwable?,
                    ) {
                        if (tag == DiagnosticTag.TvPlaybackSessionPresenter.wireValue) {
                            captured += Triple(severity, message, throwable)
                        }
                    }
                }
            val diagnosticsContext = PlaybackDiagnosticsContext()
            try {
                Logger.setLogWriters(listOf(writer))
                val streams = testStreamsWithSubtitle.withResponseSubtitleDelivery("Embed")
                val fixture =
                    fixture(
                        playbackInfos =
                            listOf(
                                Result.success(directPlayInfo(streams = streams)),
                                Result.success(directPlayInfo(streams = streams)),
                            ),
                        detail = Result.success(testDetail(streams = streams)),
                        storedSubtitle = SubtitleSelectionIntent.Track(2),
                        playbackDiagnosticsContext = diagnosticsContext,
                    )
                fixture.presenter.start()
                runCurrent()

                assertTrue(captured.any { (_, message, _) -> message.contains("event=prepare-requested") })
                assertTrue(captured.any { (_, message, _) -> message.contains("event=track-state") })
                assertTrue(captured.any { (severity, message, _) -> severity == Severity.Info && message.contains("sessionSequence=1") })
                val audioTrack =
                    captured
                        .map { (_, message, _) -> message }
                        .first { message -> message.contains("event=track-state") && message.contains("trackKind=audio") }
                assertTrue(audioTrack.contains("trackRequestedState=Selected"))
                assertTrue(audioTrack.contains("trackConfirmedState=Selected"))
                assertTrue(audioTrack.contains("trackMatchesRequest=true"))
                assertTrue(audioTrack.contains("trackActivation=None"))

                val subtitleTarget =
                    assertNotNull(
                        fixture.controller.preparedPlans
                            .single()
                            .subtitleActivationTarget,
                    )
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 1_000L).copy(
                        subtitleActivation = SubtitleActivationState.Active(subtitleTarget),
                    )
                runCurrent()
                val subtitleTrack =
                    captured
                        .map { (_, message, _) -> message }
                        .last { message -> message.contains("event=track-state") && message.contains("trackKind=subtitle") }
                assertTrue(subtitleTrack.contains("trackRequestedState=Selected"))
                assertTrue(subtitleTrack.contains("trackConfirmedState=Selected"))
                assertTrue(subtitleTrack.contains("trackMatchesRequest=true"))
                assertTrue(subtitleTrack.contains("trackActivation=Active"))
                assertTrue(subtitleTrack.contains("subtitleRenderMode=LocalEmbeddedText"))
                assertTrue(subtitleTrack.contains("subtitleRenderStatus=Active"))
                assertTrue(subtitleTrack.contains("subtitleStyleable=true"))
                val trackCountBeforeRepeat = captured.count { (_, message, _) -> message.contains("event=track-state") }
                fixture.controller.playbackStateFlow.value =
                    fixture.controller.playbackStateFlow.value
                        .copy(positionMs = 2_000L)
                runCurrent()
                assertEquals(trackCountBeforeRepeat, captured.count { (_, message, _) -> message.contains("event=track-state") })

                val runtimeState = fixture.controller.runtimeDiagnostics as MutableStateFlow<PlaybackRuntimeDiagnostics>
                runtimeState.value =
                    runtimeState.value.copy(
                        prepareEpoch = 7L,
                        allocatedBufferBytes = 8_192L,
                        bufferedAheadMs = 4_000L,
                        libVlcCachePercent = 37.5f,
                    )
                fixture.controller.playbackStateFlow.value =
                    fixture.controller.playbackStateFlow.value
                        .copy(positionMs = 3_000L)
                runCurrent()
                assertTrue(
                    captured.any { (_, message, _) ->
                        message.contains("event=track-state") &&
                            message.contains("prepareSequence=7") &&
                            message.contains("sessionSequence=1")
                    },
                )
                captured.forEach { (_, message, throwable) ->
                    assertNull(throwable)
                    assertEquals(
                        message,
                        LogScrubber.capture(DiagnosticTag.TvPlaybackSessionPresenter.wireValue, message),
                    )
                    assertFalse(message.contains("itemId="))
                    assertFalse(message.contains("streamIndex="))
                    assertFalse(message.contains("target="))
                    assertFalse(message.contains("serverId="))
                    assertFalse(message.contains("userId="))
                    assertFalse(message.contains("playSessionId="))
                    assertFalse(message.contains("https://"))
                    assertFalse(message.contains("message="))
                    assertFalse(message.contains("stack="))
                }

                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Failed, error = PlaybackError.Network)
                runCurrent()
                assertNotNull(diagnosticsContext.snapshot())
                assertTrue(
                    captured.any { (severity, message, _) ->
                        severity == Severity.Warn &&
                            message.contains("event=terminal-error") &&
                            message.contains("terminalOutcome=RetryScheduled") &&
                            message.contains("sessionSequence=1") &&
                            message.contains("prepareSequence=7") &&
                            message.contains("allocatedBufferBytes=8192") &&
                            message.contains("bufferedAheadMs=4000") &&
                            message.contains("libVlcCachePercent=37.5")
                    },
                )
                val terminalCountBeforeNewPrepare =
                    captured.count { (_, message, _) -> message.contains("event=terminal-error") }
                runtimeState.value = runtimeState.value.copy(prepareEpoch = 8L)
                fixture.controller.playbackStateFlow.value =
                    fixture.controller.playbackStateFlow.value.copy(
                        status = PlaybackStatus.Failed,
                        positionMs = 1L,
                        error = PlaybackError.Network,
                    )
                runCurrent()
                assertTrue(
                    captured.any { (severity, message, _) ->
                        severity == Severity.Warn &&
                            message.contains("event=terminal-error") &&
                            message.contains("sessionSequence=1") &&
                            message.contains("prepareSequence=8")
                    },
                )
                assertTrue(
                    captured.count { (_, message, _) -> message.contains("event=terminal-error") } >
                        terminalCountBeforeNewPrepare,
                )
                captured
                    .filter { (_, message, _) -> message.contains("event=terminal-error") }
                    .forEach { (_, message, throwable) ->
                        assertNull(throwable)
                        assertEquals(
                            message,
                            LogScrubber.capture(DiagnosticTag.TvPlaybackSessionPresenter.wireValue, message),
                        )
                        assertFalse(message.contains("itemId="))
                        assertFalse(message.contains("streamIndex="))
                        assertFalse(message.contains("target="))
                        assertFalse(message.contains("message="))
                        assertFalse(message.contains("stack="))
                    }
                fixture.presenter.close()
            } finally {
                Logger.setLogWriters(emptyList())
            }
        }

    @Test
    fun startPlansPreparesAndPlays() =
        runTest {
            val fixture = fixture()

            fixture.presenter.start()
            runCurrent()

            assertEquals(1, fixture.controller.preparedPlans.size)
            assertEquals(
                StreamMode.DirectPlay,
                fixture.controller.preparedPlans
                    .single()
                    .streamMode,
            )
            assertEquals(1, fixture.controller.playCount)

            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
            runCurrent()
            assertEquals(TvPlaybackPhase.Active, fixture.presenter.state.value.phase)
            assertEquals("Item item-1", fixture.presenter.state.value.title)
            fixture.presenter.close()
        }

    @Test
    fun reportsStartOnceThenPauseUnpauseEdges() =
        runTest {
            val fixture = fixture()
            fixture.presenter.start()
            runCurrent()

            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
            runCurrent()
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Paused, positionMs = 2_000L)
            runCurrent()
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 2_000L)
            runCurrent()

            assertEquals(
                listOf<TvReport>(
                    TvReport.Start(1_000L, "psid-1"),
                    TvReport.Progress(2_000L, true, PlaybackProgressEvent.Pause, "psid-1"),
                    TvReport.Progress(2_000L, false, PlaybackProgressEvent.Unpause, "psid-1"),
                ),
                fixture.reporter.reports,
            )
            fixture.presenter.close()
        }

    @Test
    fun periodicTimeUpdatesFollowPlanInterval() =
        runTest {
            val fixture = fixture()
            fixture.presenter.start()
            runCurrent()
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
            runCurrent()

            val intervalMs =
                fixture.controller.preparedPlans
                    .single()
                    .progressReportingPolicy.reportIntervalMs
            advanceTimeBy(intervalMs)
            runCurrent()

            assertTrue(
                fixture.reporter.reports.any { report ->
                    report is TvReport.Progress && report.eventName == PlaybackProgressEvent.TimeUpdate
                },
            )
            fixture.presenter.close()
        }

    @Test
    fun runtimeNetworkFailureRetriesOnceAtPositionWithTypedCause() =
        runTest {
            val fixture =
                fixture(
                    playbackInfos = List(2) { Result.success(directPlayInfo()) },
                )
            fixture.presenter.start()
            runCurrent()
            fixture.controller.playbackStateFlow.value =
                playbackState(PlaybackStatus.Failed, positionMs = 7_000L).copy(error = PlaybackError.Network)
            runCurrent()

            assertEquals(2, fixture.repository.planRequests.size)
            val retry = fixture.repository.planRequests.last()
            assertEquals(millisecondsToTicks(7_000L), retry.startTimeTicks)
            assertEquals(true, retry.policy.enableDirectPlay)
            assertEquals(true, retry.policy.enableDirectStream)
            assertEquals(PlaybackClientTrigger.NetworkRetry, retry.policy.clientTrigger)
            assertEquals(2, fixture.controller.preparedPlans.size)

            fixture.controller.playbackStateFlow.value =
                playbackState(PlaybackStatus.Failed, positionMs = 8_000L).copy(error = PlaybackError.Network)
            runCurrent()
            assertEquals(2, fixture.repository.planRequests.size)
            fixture.presenter.close()
        }

    @Test
    fun runtimeAudioActivationFailurePreservesPausedPositionAndCarriesTypedCause() =
        runTest {
            val fixture =
                fixture(
                    playbackInfos =
                        listOf(
                            Result.success(directPlayInfo()),
                            Result.success(transcodeInfo()),
                        ),
                )
            fixture.presenter.start()
            runCurrent()
            val target =
                fixture.controller.preparedPlans
                    .single()
                    .audioActivationTarget ?: error("Missing target")

            fixture.controller.playbackStateFlow.value =
                playbackState(PlaybackStatus.Paused, positionMs = 6_000L).copy(
                    audioActivation = AudioActivationState.Unavailable(target),
                )
            runCurrent()

            val retry = fixture.repository.planRequests.last()
            assertEquals(millisecondsToTicks(6_000L), retry.startTimeTicks)
            assertFalse(retry.policy.enableDirectPlay)
            assertEquals(PlaybackClientTrigger.AudioActivationFallback, retry.policy.clientTrigger)
            assertEquals(2, fixture.controller.preparedPlans.size)
            assertEquals(1, fixture.controller.pauseCount)
            fixture.presenter.close()
        }

    @Test
    fun advisoryHealthGuidanceUsesTheSharedCoordinatorWithoutPlayerActions() =
        runTest {
            val fixture =
                fixture(
                    preferences =
                        com.jellyscope.core.domain.model.PlaybackPreferences(
                            playbackWarningsEnabled = true,
                        ),
                )
            fixture.controller.playbackHealthMeasurementCapabilities =
                PlaybackHealthMeasurementCapabilities.BufferingOnly

            fixture.presenter.start()
            runCurrent()
            advanceTimeBy(10_000L)
            runCurrent()

            val guidance = assertNotNull(fixture.presenter.state.value.playbackGuidance)
            assertEquals(PlaybackHealthGuidanceReason.SlowStartup, guidance.reason)
            assertFalse(guidance.canReduceQuality)
            assertFalse(guidance.canOpenPlaybackSettings)
            fixture.presenter.close()
        }

    @Test
    fun autoQualityRecoveryNoticeWaitsUntilTheReplacementStreamPlays() =
        runTest {
            val fixture =
                fixture(
                    playbackInfos = List(2) { Result.success(directPlayInfo()) },
                    preferences =
                        com.jellyscope.core.domain.model.PlaybackPreferences(
                            playbackWarningsEnabled = true,
                        ),
                    playbackHealthGuidancePolicy = PlaybackHealthGuidancePolicy.Actionable,
                )
            try {
                fixture.controller.playbackHealthMeasurementCapabilities =
                    PlaybackHealthMeasurementCapabilities.BufferingOnly
                fixture.presenter.start()
                runCurrent()
                fixture.presenter.selectQuality(PlaybackQualityPolicy.Auto)
                runCurrent()
                // An explicit selection replans. Recovery evidence begins only after
                // that plan's short lifecycle exclusion window.
                advanceTimeBy(2_000L)
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()
                val requestCountBeforeRecovery = fixture.repository.planRequests.size

                repeat(3) { index ->
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Buffering, positionMs = 2_000L + index)
                    runCurrent()
                    if (index < 2) {
                        fixture.controller.playbackStateFlow.value =
                            playbackState(PlaybackStatus.Playing, positionMs = 2_000L + index)
                        runCurrent()
                    }
                }

                // The explicit Auto rung replan performs the normal bounded second
                // request when the first response still advertises the over-cap source.
                assertEquals(requestCountBeforeRecovery + 2, fixture.repository.planRequests.size)
                assertEquals(null, fixture.presenter.state.value.playbackActionNotice)

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 3_000L)
                runCurrent()

                assertEquals(
                    PlaybackActionNoticeReason.QualityRecoveryApplied,
                    fixture.presenter.state.value.playbackActionNotice
                        ?.reason,
                )
                assertEquals(
                    setOf(
                        PlaybackAction.KeepCurrentQuality,
                        PlaybackAction.TryHigherQuality,
                        PlaybackAction.ChooseLowerQuality,
                        PlaybackAction.Dismiss,
                    ),
                    fixture.presenter.state.value.playbackActions
                        .toSet(),
                )
                fixture.presenter.handlePlaybackAction(PlaybackAction.TryHigherQuality)
                runCurrent()
                assertTrue(
                    fixture.presenter.state.value.qualityChoices
                        .first { choice -> choice.mode == PlaybackQualityMode.Auto && !choice.inheritsPlaybackDefault }
                        .selected,
                )
                assertEquals(
                    false,
                    fixture.presenter.state.value.qualityChoices
                        .first { choice -> choice.inheritsPlaybackDefault }
                        .selected,
                )
            } finally {
                fixture.presenter.close()
                runCurrent()
            }
        }

    @Test
    fun inheritedAutoDefaultDoesNotReplanAfterActionableBufferingEvidence() =
        runTest {
            val fixture =
                fixture(
                    playbackInfos = List(2) { Result.success(directPlayInfo()) },
                    preferences =
                        com.jellyscope.core.domain.model.PlaybackPreferences(
                            defaultQualityPolicy = PlaybackQualityPolicy.Auto,
                            playbackWarningsEnabled = true,
                        ),
                    playbackHealthGuidancePolicy = PlaybackHealthGuidancePolicy.Actionable,
                )
            fixture.controller.playbackHealthMeasurementCapabilities =
                PlaybackHealthMeasurementCapabilities.BufferingOnly
            fixture.presenter.start()
            runCurrent()
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
            runCurrent()
            val initialRequestCount = fixture.repository.planRequests.size

            repeat(3) { index ->
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Buffering, positionMs = 2_000L + index)
                runCurrent()
                if (index < 2) {
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Playing, positionMs = 2_000L + index)
                    runCurrent()
                }
            }

            assertEquals(initialRequestCount, fixture.repository.planRequests.size)
            assertEquals(
                setOf(
                    PlaybackAction.ChooseLowerQuality,
                    PlaybackAction.TryHigherQuality,
                    PlaybackAction.TryOriginal,
                    PlaybackAction.Dismiss,
                ),
                fixture.presenter.state.value.playbackActions
                    .toSet(),
            )
            fixture.presenter.close()
        }

    @Test
    fun fixedQualityChoicesCarryDistinctBitrateAndResolutionFacts() =
        runTest {
            val fixture = fixture()

            fixture.presenter.start()
            runCurrent()

            val fixedChoices =
                fixture.presenter.state.value.qualityChoices.filter { choice ->
                    choice.maxBitrateBps != null
                }
            val fullHdChoices = fixedChoices.filter { choice -> choice.resolutionHeight == 1080 }
            assertEquals(setOf(12_000_000L, 8_000_000L), fullHdChoices.mapNotNull { choice -> choice.maxBitrateBps }.toSet())
            assertEquals(
                fixedChoices.size,
                fixedChoices.map { choice -> "${choice.mode.name}:${choice.maxBitrateBps}" }.toSet().size,
            )
            fixture.presenter.close()
        }

    @Test
    fun completionReportsStoppedOnce() =
        runTest {
            val fixture = fixture()
            fixture.presenter.start()
            runCurrent()
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
            runCurrent()

            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Completed, positionMs = 59_000L)
            runCurrent()
            advanceUntilIdle()

            assertEquals(
                1,
                fixture.reporter.reports
                    .filterIsInstance<TvReport.Stopped>()
                    .size,
            )
            assertEquals(TvPlaybackPhase.Completed, fixture.presenter.state.value.phase)
            fixture.presenter.close()
        }

    @Test
    fun failedStartSuppressesProgressAndStopThenRetriesOnNextReadySample() =
        runTest {
            val fixture = fixture()
            fixture.reporter.failingStarts = 1
            fixture.presenter.start()
            runCurrent()

            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
            runCurrent()
            // First start attempt failed; edges must be suppressed.
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Paused, positionMs = 1_500L)
            runCurrent()

            assertEquals(2, fixture.reporter.startAttempts)
            // The retry Start succeeds on the Paused sample; its Pause edge then
            // serializes behind the Start (same ordering as PlayerViewModel).
            assertEquals(
                listOf<TvReport>(
                    TvReport.Start(1_500L, "psid-1"),
                    TvReport.Progress(1_500L, true, PlaybackProgressEvent.Pause, "psid-1"),
                ),
                fixture.reporter.reports,
            )
            fixture.presenter.close()
        }

    @Test
    fun neverStartedSessionNeverReportsStopped() =
        runTest {
            val fixture = fixture()
            fixture.presenter.start()
            runCurrent()

            // Still Loading — never reached a ready state.
            fixture.presenter.close()
            advanceUntilIdle()

            assertTrue(
                fixture.reporter.reports
                    .filterIsInstance<TvReport.Stopped>()
                    .isEmpty(),
            )
        }

    @Test
    fun closeDrainsFinalStopPastPresenterCancellation() =
        runTest {
            val fixture = fixture()
            fixture.presenter.start()
            runCurrent()
            assertTrue(fixture.presenter.state.value.playerInstalled)
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 5_000L)
            runCurrent()

            fixture.presenter.close()
            fixture.presenter.close()
            advanceUntilIdle()

            assertEquals(
                listOf(TvReport.Stopped(5_000L, "psid-1")),
                fixture.reporter.reports.filterIsInstance<TvReport.Stopped>(),
            )
            assertEquals(1, fixture.controller.releaseCount)
        }

    @Test
    fun outOfWindowTranscodeSeekRestartsTranscodeWithSessionBoundary() =
        runTest {
            val fixture =
                fixture(
                    playbackInfos = listOf(Result.success(transcodeInfo()), Result.success(transcodeInfo())),
                )
            fixture.controller.transcodeSeekRestartsStream = true
            fixture.presenter.start()
            runCurrent()
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 5_000L)
            runCurrent()

            // Buffered ahead only to 5s; seeking to 40s falls outside the window.
            fixture.presenter.seekTo(40_000L)
            runCurrent()
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 40_000L)
            runCurrent()

            assertTrue(fixture.controller.seekPositions.isEmpty())
            assertEquals(2, fixture.controller.preparedPlans.size)
            assertEquals(
                millisecondsToTicks(40_000L),
                fixture.repository.planRequests
                    .last()
                    .startTimeTicks,
            )
            // Old session Stop drains before the replacement session's Start,
            // and the replacement uses a fresh play session id.
            assertEquals(
                listOf<TvReport>(
                    TvReport.Start(5_000L, "psid-1"),
                    TvReport.Stopped(40_000L, "psid-1"),
                    TvReport.Start(40_000L, "psid-2"),
                ),
                fixture.reporter.reports,
            )
            fixture.presenter.close()
        }

    @Test
    fun inWindowTranscodeSeekSeeksInStream() =
        runTest {
            val fixture = fixture(playbackInfos = listOf(Result.success(transcodeInfo())))
            fixture.controller.transcodeSeekRestartsStream = true
            fixture.presenter.start()
            runCurrent()
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 30_000L)
            runCurrent()

            fixture.presenter.seekTo(10_000L)
            runCurrent()

            assertEquals(listOf(10_000L), fixture.controller.seekPositions)
            assertEquals(1, fixture.controller.preparedPlans.size)
            fixture.presenter.close()
        }

    @Test
    fun eligibleFailureUsesOneCompatibilityRecoveryThenFails() =
        runTest {
            val fixture =
                fixture(
                    playbackInfos =
                        listOf(
                            Result.success(directPlayInfo()),
                            Result.success(transcodeInfo()),
                        ),
                    preferences =
                        com.jellyscope.core.domain.model.PlaybackPreferences(
                            playbackWarningsEnabled = true,
                        ),
                )
            fixture.presenter.start()
            runCurrent()
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 3_000L)
            runCurrent()

            fixture.controller.playbackStateFlow.value =
                playbackState(PlaybackStatus.Failed, positionMs = 3_000L, error = PlaybackError.Decoder)
            runCurrent()
            fixture.controller.playbackStateFlow.value =
                playbackState(PlaybackStatus.Failed, positionMs = 3_000L, error = PlaybackError.Decoder)
            runCurrent()
            fixture.controller.playbackStateFlow.value =
                playbackState(PlaybackStatus.Failed, positionMs = 3_000L, error = PlaybackError.Decoder)
            runCurrent()

            val policies = fixture.repository.planRequests.map { request -> request.policy }
            assertEquals(2, policies.size)
            assertTrue(policies[0].enableDirectPlay)
            assertEquals(false, policies[1].enableDirectPlay)
            assertEquals(false, policies[1].enableDirectStream)
            assertEquals(TvPlaybackPhase.Failed, fixture.presenter.state.value.phase)
            assertEquals(PlaybackError.Decoder, fixture.presenter.state.value.error)
            assertTrue(fixture.presenter.state.value.playbackActionNotice != null)
            assertTrue(PlaybackAction.ChooseLowerQuality in fixture.presenter.state.value.playbackActions)
            assertEquals(false, PlaybackAction.OpenPlaybackSettings in fixture.presenter.state.value.playbackActions)
            assertTrue(PlaybackAction.Dismiss in fixture.presenter.state.value.playbackActions)
            fixture.presenter.close()
        }

    @Test
    fun detailFetchFailureSurfacesNetworkNotUnsupported() =
        runTest {
            val fixture = fixture()
            fixture.repository.detail = Result.failure(IllegalStateException("down"))

            fixture.presenter.start()
            runCurrent()

            assertEquals(TvPlaybackPhase.Failed, fixture.presenter.state.value.phase)
            assertEquals(PlaybackError.Network, fixture.presenter.state.value.error)
            fixture.presenter.close()
        }

    @Test
    fun detailWithoutUsableVersionSurfacesUnsupportedMedia() =
        runTest {
            val fixture = fixture()
            fixture.repository.detail =
                Result.success(testDetail(item = mediaItem(id = "item-1")).copy(versions = emptyList()))

            fixture.presenter.start()
            runCurrent()

            assertEquals(PlaybackError.UnsupportedMedia, fixture.presenter.state.value.error)
            fixture.presenter.close()
        }

    @Test
    fun transcodePlanExposesLinearPlaybackGating() =
        runTest {
            val fixture = fixture(playbackInfos = listOf(Result.success(transcodeInfo())))
            fixture.presenter.start()
            runCurrent()
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
            runCurrent()

            assertTrue(fixture.presenter.state.value.isTranscode)
            fixture.presenter.close()
        }

    @Test
    fun replanCarriesRequestedAudioSubtitleAndBitrate() =
        runTest {
            val streams = testStreamsWithSubtitle
            val fixture =
                fixture(
                    playbackInfos =
                        listOf(
                            Result.success(directPlayInfo(streams = streams.withResponseSubtitleDelivery("Embed"))),
                            Result.success(directPlayInfo(streams = streams.withResponseSubtitleDelivery("Embed"))),
                            Result.success(transcodeInfo(streams = streams.withResponseSubtitleDelivery("Embed"))),
                        ),
                    storedSubtitle = SubtitleSelectionIntent.Track(2),
                    detail = Result.success(testDetail(streams = streams)),
                )
            fixture.presenter.start()
            runCurrent()
            assertEquals(1, fixture.repository.planRequests[0].audioStreamIndex)
            assertEquals(2, fixture.repository.planRequests[0].subtitleStreamIndex)

            fixture.presenter.selectQuality(4_000_000L)
            runCurrent()

            val replan = fixture.repository.planRequests[1]
            assertEquals(1, replan.audioStreamIndex)
            assertEquals(2, replan.subtitleStreamIndex)
            assertEquals(4_000_000L, replan.maxStreamingBitrate)
            assertEquals(1_280, replan.policy.qualityResolutionCap?.maxWidth)
            assertEquals(720, replan.policy.qualityResolutionCap?.maxHeight)
            assertEquals(PlaybackQualityCapOrigin.ExplicitSessionChoice, replan.policy.qualityCapOrigin)
            assertEquals(
                PlaybackQualityCapOrigin.ExplicitSessionChoice,
                fixture.controller.preparedPlans
                    .last()
                    .qualityCapOrigin,
            )
            fixture.presenter.close()
        }

    @Test
    fun settingsDefaultQualityCapCarriesItsOriginIntoTheInstalledPlan() =
        runTest {
            val fixture =
                fixture(
                    preferences =
                        com.jellyscope.core.domain.model.PlaybackPreferences(
                            defaultMaxBitrateBps = 6_000_000L,
                        ),
                )

            fixture.presenter.start()
            runCurrent()

            val installedPlan = fixture.controller.preparedPlans.single()
            assertEquals(6_000_000L, installedPlan.maxStreamingBitrate)
            assertEquals(PlaybackQualityCapOrigin.SettingsDefault, installedPlan.qualityCapOrigin)
            fixture.presenter.close()
        }

    @Test
    fun inheritedOffLadderQualityCarriesTheExactBitrateAndSourceIdentity() =
        runTest {
            val fixture =
                fixture(
                    preferences =
                        com.jellyscope.core.domain.model.PlaybackPreferences(
                            defaultQualityPolicy = PlaybackQualityPolicy.fixed(7_500_000L),
                        ),
                )

            fixture.presenter.start()
            runCurrent()

            val inherited =
                fixture.presenter.state.value.qualityChoices
                    .single { choice -> choice.inheritsPlaybackDefault }
            assertEquals(TvQualityDefaultSource.PlaybackSettings, inherited.defaultSource)
            assertEquals(7_500_000L, fixture.presenter.state.value.inheritedQualityPolicy.maxBitrateBps)
            fixture.presenter.close()
        }

    @Test
    fun selectingQualityDoesNotPersistAutomaticallyResolvedAudio() =
        runTest {
            val selectionStore = FakeTvPlaybackSelectionStore()
            val fixture = fixture(selectionStore = selectionStore)
            fixture.presenter.start()
            runCurrent()

            // The initial audio is the automatic language/default resolution,
            // not a user choice. Changing only quality must not freeze it as a
            // per-title audio override.
            fixture.presenter.selectQuality(PlaybackQualityPolicy.fixed(4_000_000L))
            advanceUntilIdle()

            assertNull(
                selectionStore.values[
                    PlaybackSelectionKey("server-1", "user-1", "item-1", "source-1"),
                ],
            )
            fixture.presenter.close()
        }

    @Test
    fun selectingQualityPreservesAnExplicitAudioChoice() =
        runTest {
            val selectionStore = FakeTvPlaybackSelectionStore()
            val fixture = fixture(selectionStore = selectionStore)
            fixture.presenter.start()
            runCurrent()

            // Stream 1 also happens to be the automatic default. The explicit
            // select call is still user intent and must remain durable.
            fixture.presenter.selectAudio(1)
            fixture.presenter.selectQuality(PlaybackQualityPolicy.fixed(4_000_000L))
            advanceUntilIdle()

            val saved =
                selectionStore.values[
                    PlaybackSelectionKey("server-1", "user-1", "item-1", "source-1"),
                ]
            assertNotNull(saved)
            assertEquals(1, saved.audioStreamIndex)
            fixture.presenter.close()
        }

    @Test
    fun invalidStoredSubtitleIntentIsDeletedAndFallsBack() =
        runTest {
            val fixture =
                fixture(
                    playbackInfos = listOf(Result.success(directPlayInfo(streams = testStreamsWithSubtitle))),
                    storedSubtitle = SubtitleSelectionIntent.Track(9),
                    detail = Result.success(testDetail(streams = testStreamsWithSubtitle)),
                )
            fixture.presenter.start()
            runCurrent()

            assertEquals(1, fixture.subtitleStore.deleteCount)
            // No preferred language and no default track -> Off (wire index -1).
            assertEquals(-1, fixture.repository.planRequests[0].subtitleStreamIndex)
            fixture.presenter.close()
        }

    @Test
    fun embeddedSubtitleOffSwitchesLocallyAndPersistsOff() =
        runTest {
            val streams = testStreamsWithSubtitle
            val fixture =
                fixture(
                    playbackInfos =
                        listOf(Result.success(directPlayInfo(streams = streams.withResponseSubtitleDelivery("Embed")))),
                    storedSubtitle = SubtitleSelectionIntent.Track(2),
                    detail = Result.success(testDetail(streams = streams)),
                )
            fixture.presenter.start()
            runCurrent()
            val plansBefore = fixture.repository.planRequests.size

            fixture.presenter.selectSubtitle(null)
            runCurrent()

            assertEquals(plansBefore, fixture.repository.planRequests.size)
            assertEquals(null, fixture.controller.embeddedSubtitleSelections.last())
            assertEquals(
                SubtitleSelectionIntent.Off,
                fixture.subtitleStore.savedSelections
                    .last()
                    .second,
            )
            fixture.presenter.close()
        }

    @Test
    fun subtitleSelectionOnTranscodeReplans() =
        runTest {
            val streams = testStreamsWithSubtitle
            val fixture =
                fixture(
                    playbackInfos =
                        List(2) {
                            Result.success(transcodeInfo(streams = streams.withResponseSubtitleDelivery("Encode")))
                        },
                    detail = Result.success(testDetail(streams = streams)),
                )
            fixture.presenter.start()
            runCurrent()
            val plansBefore = fixture.repository.planRequests.size

            fixture.presenter.selectSubtitle(2)
            runCurrent()

            assertEquals(plansBefore + 1, fixture.repository.planRequests.size)
            assertEquals(
                2,
                fixture.repository.planRequests
                    .last()
                    .subtitleStreamIndex,
            )
            fixture.presenter.close()
        }

    @Test
    fun missingInitialResponseSubtitleUsesDetailFormatAndAcceptsExactEncodeOnce() =
        runTest {
            val fixture =
                fixture(
                    playbackInfos =
                        listOf(
                            Result.success(directPlayInfo(streams = testStreams)),
                            Result.success(transcodeInfo(streams = testStreamsWithSubtitle.withResponseSubtitleDelivery("Encode"))),
                        ),
                    storedSubtitle = SubtitleSelectionIntent.Track(2),
                    detail = Result.success(testDetail(streams = testStreamsWithSubtitle)),
                )

            fixture.presenter.start()
            runCurrent()

            assertEquals(2, fixture.repository.planRequests.size)
            val fallbackRequest = fixture.repository.planRequests.last()
            assertFalse(fallbackRequest.policy.enableDirectPlay)
            assertFalse(fallbackRequest.policy.enableDirectStream)
            assertEquals(2, fallbackRequest.policy.forceEncodeSubtitle?.streamIndex)
            assertEquals("srt", fallbackRequest.policy.forceEncodeSubtitle?.normalizedFormat)
            val installed =
                fixture.controller.preparedPlans
                    .last()
                    .plannedSubtitle as PlannedSubtitle.Track
            assertEquals(
                StreamMode.Transcode,
                fixture.controller.preparedPlans
                    .last()
                    .streamMode,
            )
            assertEquals(2, installed.streamIndex)
            assertEquals(SubtitleDeliveryMethod.Encode, installed.deliveryMethod)
            fixture.presenter.close()
        }

    @Test
    fun runtimeSubtitleFailureForcesTranscodeAndPreservesPausedPositionAndIntent() =
        runTest {
            val streams = testStreamsWithSubtitle
            val fixture =
                fixture(
                    playbackInfos =
                        listOf(
                            Result.success(directPlayInfo(streams = streams.withResponseSubtitleDelivery("Embed"))),
                            Result.success(transcodeInfo(streams = streams.withResponseSubtitleDelivery("Encode"))),
                        ),
                    storedSubtitle = SubtitleSelectionIntent.Track(2),
                    detail = Result.success(testDetail(streams = streams)),
                )
            fixture.presenter.start()
            runCurrent()

            val target =
                fixture.controller.preparedPlans
                    .single()
                    .subtitleActivationTarget ?: error("Missing target")
            fixture.controller.playbackStateFlow.value =
                playbackState(PlaybackStatus.Paused, positionMs = 7_000L).copy(
                    subtitleActivation = SubtitleActivationState.Unavailable(target),
                )
            runCurrent()

            val fallbackRequest = fixture.repository.planRequests.last()
            assertEquals(millisecondsToTicks(7_000L), fallbackRequest.startTimeTicks)
            assertFalse(fallbackRequest.policy.enableDirectPlay)
            assertFalse(fallbackRequest.policy.enableDirectStream)
            assertEquals(2, fallbackRequest.policy.forceEncodeSubtitle?.streamIndex)
            assertEquals("srt", fallbackRequest.policy.forceEncodeSubtitle?.normalizedFormat)
            assertEquals(PlaybackClientTrigger.SubtitleActivationFallback, fallbackRequest.policy.clientTrigger)
            assertEquals(2, fixture.controller.preparedPlans.size)
            assertEquals(
                StreamMode.Transcode,
                fixture.controller.preparedPlans
                    .last()
                    .streamMode,
            )
            assertEquals(
                7_000L,
                fixture.controller.preparedPlans
                    .last()
                    .startPositionMs,
            )
            assertEquals(1, fixture.controller.pauseCount)
            assertEquals(
                SubtitleDeliveryMethod.Encode,
                (
                    fixture.controller.preparedPlans
                        .last()
                        .plannedSubtitle as PlannedSubtitle.Track
                ).deliveryMethod,
            )
            fixture.presenter.close()
        }

    @Test
    fun runtimeSubtitleFailurePreservesPausedPositionAndRejectsWrongIndexEncode() =
        runTest {
            val embedded = directPlayInfo(streams = testStreamsWithSubtitle.withResponseSubtitleDelivery("Embed"))
            val wrongIndexStreams =
                testStreamsWithSubtitle.map { stream ->
                    if (stream.index == 2) stream.copy(index = 3, deliveryMethod = "Encode") else stream
                }
            val wrongIndexInfo =
                transcodeInfo(streams = wrongIndexStreams).let { info ->
                    info.copy(mediaSources = info.mediaSources.map { source -> source.copy(defaultSubtitleStreamIndex = 3) })
                }
            val fixture =
                fixture(
                    playbackInfos =
                        listOf(
                            Result.success(embedded),
                            Result.success(wrongIndexInfo),
                        ),
                    storedSubtitle = SubtitleSelectionIntent.Track(2),
                    detail = Result.success(testDetail(streams = testStreamsWithSubtitle)),
                )
            fixture.presenter.start()
            runCurrent()
            val target =
                fixture.controller.preparedPlans
                    .single()
                    .subtitleActivationTarget ?: error("Missing target")
            fixture.controller.playbackStateFlow.value =
                playbackState(PlaybackStatus.Paused, positionMs = 7_000L).copy(
                    subtitleActivation = SubtitleActivationState.Unavailable(target),
                )
            runCurrent()

            assertEquals(
                millisecondsToTicks(7_000L),
                fixture.repository.planRequests
                    .last()
                    .startTimeTicks,
            )
            assertEquals(1, fixture.controller.preparedPlans.size)
            assertEquals(0, fixture.controller.pauseCount)
            assertTrue(
                fixture.presenter.state.value.subtitleTracks
                    .none { track -> track.selected },
            )
            fixture.presenter.close()
        }

    @Test
    fun stopInvalidatesSuspendedTvOsSubtitleFallbackBeforePrepareOrResume() =
        runTest {
            val fixture =
                fixture(
                    playbackInfos =
                        listOf(
                            Result.success(directPlayInfo(streams = testStreamsWithSubtitle.withResponseSubtitleDelivery("Embed"))),
                            Result.success(transcodeInfo(streams = testStreamsWithSubtitle.withResponseSubtitleDelivery("Encode"))),
                        ),
                    storedSubtitle = SubtitleSelectionIntent.Track(2),
                    detail = Result.success(testDetail(streams = testStreamsWithSubtitle)),
                )
            fixture.presenter.start()
            runCurrent()
            val gate = CompletableDeferred<Unit>()
            fixture.repository.subtitleFallbackGate = gate
            val target =
                fixture.controller.preparedPlans
                    .single()
                    .subtitleActivationTarget ?: error("Missing target")
            fixture.controller.playbackStateFlow.value =
                playbackState(PlaybackStatus.Playing, positionMs = 6_000L).copy(
                    subtitleActivation = SubtitleActivationState.Unavailable(target),
                )
            runCurrent()
            assertEquals(2, fixture.repository.planRequests.size)

            fixture.presenter.stop()
            gate.complete(Unit)
            runCurrent()

            assertEquals(1, fixture.controller.preparedPlans.size)
            assertEquals(1, fixture.controller.playCount)
            assertEquals(1, fixture.controller.stopCount)
            fixture.presenter.close()
        }

    @Test
    fun preferredAudioLanguageDrivesInitialSelection() =
        runTest {
            val frenchAudio =
                testStreams[1].copy(index = 3, language = "fre", displayTitle = "French", isDefault = false)
            val streams = testStreams + frenchAudio
            val fixture =
                fixture(
                    playbackInfos = listOf(Result.success(directPlayInfo(streams = streams))),
                    preferences =
                        com.jellyscope.core.domain.model
                            .PlaybackPreferences(preferredAudioLanguage = "fre"),
                    detail = Result.success(testDetail(streams = streams)),
                )
            fixture.presenter.start()
            runCurrent()

            assertEquals(3, fixture.repository.planRequests[0].audioStreamIndex)
            fixture.presenter.close()
        }

    @Test
    fun resumePositionIsAlwaysHonoured() =
        runTest {
            val fixture = fixture(startTicks = 50_000_000L)
            fixture.presenter.start()
            runCurrent()

            assertEquals(50_000_000L, fixture.repository.planRequests[0].startTimeTicks)
            fixture.presenter.close()
        }

    @Test
    fun autoSkipSegmentFiresOnceWhilePlayingOnly() =
        runTest {
            val fixture =
                fixture(
                    preferences =
                        com.jellyscope.core.domain.model.PlaybackPreferences(
                            introSkip = SegmentSkipPolicy.AutoSkip,
                        ),
                )
            fixture.repository.mediaSegments =
                listOf(
                    MediaSegment(
                        type = MediaSegmentType.Intro,
                        startTicks = 0L,
                        endTicks = millisecondsToTicks(30_000L),
                    ),
                )
            fixture.presenter.start()
            runCurrent()

            // Paused inside the segment: no skip.
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Paused, positionMs = 5_000L)
            runCurrent()
            assertTrue(fixture.controller.seekPositions.isEmpty())

            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 5_000L)
            runCurrent()
            assertEquals(listOf(30_000L), fixture.controller.seekPositions)

            // Seeking back into the fired segment never re-fires it.
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 6_000L)
            runCurrent()
            assertEquals(listOf(30_000L), fixture.controller.seekPositions)
            fixture.presenter.close()
        }

    @Test
    fun askPolicySurfacesSegmentWithoutSkipping() =
        runTest {
            val fixture = fixture()
            fixture.repository.mediaSegments =
                listOf(
                    MediaSegment(
                        type = MediaSegmentType.Outro,
                        startTicks = millisecondsToTicks(40_000L),
                        endTicks = millisecondsToTicks(55_000L),
                    ),
                )
            fixture.presenter.start()
            runCurrent()

            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 45_000L)
            runCurrent()

            val segment = requireNotNull(fixture.presenter.state.value.activeSegment)
            assertEquals(MediaSegmentType.Outro, segment.type)
            assertTrue(segment.askUser)
            assertTrue(fixture.controller.seekPositions.isEmpty())

            fixture.presenter.skipActiveSegment()
            assertEquals(listOf(55_000L), fixture.controller.seekPositions)
            fixture.presenter.close()
        }

    @Test
    fun verifiedEndAutoAdvancesWithSessionBoundariesAndNoCompletedPhase() =
        runTest {
            val episode1 = mediaItem(id = "ep-1", kind = MediaKind.Episode, seriesId = "series-1", seasonId = "season-1", indexNumber = 1)
            val episode2 = mediaItem(id = "ep-2", kind = MediaKind.Episode, seriesId = "series-1", seasonId = "season-1", indexNumber = 2)
            val fixture =
                fixture(
                    detail = Result.success(testDetail(item = episode1)),
                    itemId = "ep-1",
                )
            fixture.repository.detailsById =
                mapOf(
                    "ep-1" to Result.success(testDetail(item = episode1)),
                    "ep-2" to Result.success(testDetail(item = episode2)),
                )
            fixture.repository.seasons = Result.success(listOf(mediaItem(id = "season-1", name = "Season 1")))
            fixture.repository.episodesBySeasonId = mapOf("season-1" to listOf(episode1, episode2))

            val phases = mutableListOf<TvPlaybackPhase>()
            val handle = fixture.presenter.watchState { state -> phases += state.phase }
            fixture.presenter.start()
            runCurrent()
            assertEquals(
                "ep-2",
                fixture.presenter.state.value.nextEpisode
                    ?.id,
            )

            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
            runCurrent()
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Completed, positionMs = 59_600L)
            runCurrent()

            assertEquals("ep-2", fixture.presenter.state.value.itemId)
            assertTrue(TvPlaybackPhase.Completed !in phases)
            assertEquals(
                listOf<TvReport>(
                    TvReport.Start(1_000L, "psid-1"),
                    TvReport.Stopped(59_600L, "psid-1"),
                ),
                fixture.reporter.reports,
            )

            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 0L)
            runCurrent()
            assertEquals(
                TvReport.Start(0L, "psid-2"),
                fixture.reporter.reports.last(),
            )
            handle.close()
            fixture.presenter.close()
        }

    @Test
    fun unverifiedEndPublishesTerminalCompletedWithoutAdvance() =
        runTest {
            val episode1 = mediaItem(id = "ep-1", kind = MediaKind.Episode, seriesId = "series-1", seasonId = "season-1", indexNumber = 1)
            val episode2 = mediaItem(id = "ep-2", kind = MediaKind.Episode, seriesId = "series-1", seasonId = "season-1", indexNumber = 2)
            val fixture =
                fixture(
                    detail = Result.success(testDetail(item = episode1)),
                    itemId = "ep-1",
                )
            fixture.repository.seasons = Result.success(listOf(mediaItem(id = "season-1", name = "Season 1")))
            fixture.repository.episodesBySeasonId = mapOf("season-1" to listOf(episode1, episode2))
            fixture.presenter.start()
            runCurrent()
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
            runCurrent()

            // Far from the known duration: spurious completion, no advance.
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Completed, positionMs = 10_000L)
            runCurrent()

            assertEquals("ep-1", fixture.presenter.state.value.itemId)
            assertEquals(TvPlaybackPhase.Completed, fixture.presenter.state.value.phase)
            fixture.presenter.close()
        }

    @Test
    fun upNextWindowSurfacesNearTheEnd() =
        runTest {
            val episode1 = mediaItem(id = "ep-1", kind = MediaKind.Episode, seriesId = "series-1", seasonId = "season-1", indexNumber = 1)
            val episode2 = mediaItem(id = "ep-2", kind = MediaKind.Episode, seriesId = "series-1", seasonId = "season-1", indexNumber = 2)
            val fixture =
                fixture(
                    detail = Result.success(testDetail(item = episode1)),
                    itemId = "ep-1",
                )
            fixture.repository.seasons = Result.success(listOf(mediaItem(id = "season-1", name = "Season 1")))
            fixture.repository.episodesBySeasonId = mapOf("season-1" to listOf(episode1, episode2))
            fixture.presenter.start()
            runCurrent()

            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 10_000L)
            runCurrent()
            assertTrue(!fixture.presenter.state.value.upNextVisible)

            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 45_000L)
            runCurrent()
            assertTrue(fixture.presenter.state.value.upNextVisible)
            fixture.presenter.close()
        }

    @Test
    fun outgoingCallbacksDuringAdvanceCannotSpawnFallbackReplans() =
        runTest {
            val episode1 = mediaItem(id = "ep-1", kind = MediaKind.Episode, seriesId = "series-1", seasonId = "season-1", indexNumber = 1)
            val episode2 = mediaItem(id = "ep-2", kind = MediaKind.Episode, seriesId = "series-1", seasonId = "season-1", indexNumber = 2)
            val fixture =
                fixture(
                    detail = Result.success(testDetail(item = episode1)),
                    itemId = "ep-1",
                )
            fixture.repository.detailsById =
                mapOf(
                    "ep-1" to Result.success(testDetail(item = episode1)),
                    "ep-2" to Result.success(testDetail(item = episode2)),
                )
            fixture.repository.seasons = Result.success(listOf(mediaItem(id = "season-1", name = "Season 1")))
            fixture.repository.episodesBySeasonId = mapOf("season-1" to listOf(episode1, episode2))
            fixture.presenter.start()
            runCurrent()
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
            runCurrent()

            // Hold the advance open at the next item's detail fetch, then let
            // the OUTGOING item publish a fallback-eligible failure sample.
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            fixture.repository.detailGate = gate
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Completed, positionMs = 59_600L)
            runCurrent()
            val plansDuringAdvance = fixture.repository.planRequests.size
            fixture.controller.playbackStateFlow.value =
                playbackState(PlaybackStatus.Failed, positionMs = 59_600L, error = PlaybackError.Decoder)
            runCurrent()
            assertEquals(plansDuringAdvance, fixture.repository.planRequests.size)
            assertTrue(
                fixture.presenter.state.value.audioTracks
                    .isEmpty(),
            )

            fixture.repository.detailGate = null
            gate.complete(Unit)
            runCurrent()
            assertEquals("ep-2", fixture.presenter.state.value.itemId)
            fixture.presenter.close()
        }

    @Test
    fun synchronousNewPlanFailureStillEntersRecovery() =
        runTest {
            val fixture =
                fixture(
                    playbackInfos = List(2) { Result.success(directPlayInfo()) },
                )
            // The controller fails synchronously inside play() — while the
            // startup gate is up — so recovery must run via the post-gate
            // reprocess of the newest sample.
            fixture.controller.failOnNextPlay = PlaybackError.Decoder
            fixture.presenter.start()
            runCurrent()

            assertEquals(2, fixture.repository.planRequests.size)
            // Exactly one compatibility recovery: the new policy disables
            // source video copy in a single bounded attempt.
            val policy =
                fixture.repository.planRequests
                    .last()
                    .policy
            assertEquals(false, policy.enableDirectPlay)
            assertEquals(false, policy.enableDirectStream)
            fixture.presenter.close()
        }

    @Test
    fun queueAdvanceSynchronousFailureEntersRecoveryOnceAtFirstRung() =
        runTest {
            val episode1 = mediaItem(id = "ep-1", kind = MediaKind.Episode, seriesId = "series-1", seasonId = "season-1", indexNumber = 1)
            val episode2 = mediaItem(id = "ep-2", kind = MediaKind.Episode, seriesId = "series-1", seasonId = "season-1", indexNumber = 2)
            val fixture =
                fixture(
                    playbackInfos = List(3) { Result.success(directPlayInfo()) },
                    detail = Result.success(testDetail(item = episode1)),
                    itemId = "ep-1",
                )
            fixture.repository.detailsById =
                mapOf(
                    "ep-1" to Result.success(testDetail(item = episode1)),
                    "ep-2" to Result.success(testDetail(item = episode2)),
                )
            fixture.repository.seasons = Result.success(listOf(mediaItem(id = "season-1", name = "Season 1")))
            fixture.repository.episodesBySeasonId = mapOf("season-1" to listOf(episode1, episode2))
            fixture.presenter.start()
            runCurrent()
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
            runCurrent()

            // The NEXT item fails synchronously inside play() during the
            // gated advance window.
            fixture.controller.failOnNextPlay = PlaybackError.Decoder
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Completed, positionMs = 59_600L)
            runCurrent()

            assertEquals("ep-2", fixture.presenter.state.value.itemId)
            val recoveryRequest = fixture.repository.planRequests.last()
            assertEquals("ep-2", recoveryRequest.itemId)
            // Exactly the one bounded compatibility recovery.
            assertEquals(false, recoveryRequest.policy.enableDirectPlay)
            assertEquals(false, recoveryRequest.policy.enableDirectStream)
            fixture.presenter.close()
        }

    @Test
    fun chaptersAndPlanEpochPublish() =
        runTest {
            val fixture =
                fixture(
                    playbackInfos =
                        listOf(
                            Result.success(directPlayInfo()),
                            Result.success(directPlayInfo()),
                            Result.success(transcodeInfo()),
                        ),
                )
            fixture.presenter.start()
            runCurrent()

            val epochBefore = fixture.presenter.state.value.planEpoch
            assertTrue(epochBefore > 0)

            fixture.presenter.selectQuality(4_000_000L)
            runCurrent()
            assertTrue(fixture.presenter.state.value.planEpoch > epochBefore)
            fixture.presenter.close()
        }

    private class Fixture(
        val presenter: TvPlaybackSessionPresenter,
        val controller: FakeTvPlayerController,
        val reporter: RecordingProgressReporter,
        val repository: FakeTvMediaRepository,
        val preferencesStore: FakePlaybackPreferencesStore,
        val subtitleStore: FakeSubtitleSelectionStore,
        val reportingQueue: PlaybackReportingQueue,
    )

    private fun TestScope.fixture(
        playbackInfos: List<Result<com.jellyscope.core.domain.playback.PlaybackInfo>> = emptyList(),
        preferences: com.jellyscope.core.domain.model.PlaybackPreferences =
            com.jellyscope.core.domain.model
                .PlaybackPreferences(),
        storedSubtitle: SubtitleSelectionIntent? = null,
        detail: Result<com.jellyscope.core.domain.model.MediaItemDetail> = Result.success(testDetail()),
        itemId: String = "item-1",
        startTicks: Long = 0L,
        playbackHealthGuidancePolicy: PlaybackHealthGuidancePolicy = PlaybackHealthGuidancePolicy.Advisory,
        selectionStore: FakeTvPlaybackSelectionStore? = null,
        playbackDiagnosticsContext: PlaybackDiagnosticsContext? = null,
        playerControllerFactory: (() -> com.jellyscope.core.domain.playback.PlayerController)? = null,
        workDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
    ): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository =
            FakeTvMediaRepository(
                playbackInfoQueue = ArrayDeque(playbackInfos),
                detail = detail,
            )
        val controller = FakeTvPlayerController()
        val reporter = RecordingProgressReporter()
        val reportingQueue = PlaybackReportingQueue(reporter, dispatcher, PlaybackStopSettlementRegistry())
        val preferencesStore = FakePlaybackPreferencesStore(preferences)
        val subtitleStore = FakeSubtitleSelectionStore(storedSubtitle)
        val presenter =
            TvPlaybackSessionPresenter(
                session = testSession(),
                initialItemId = itemId,
                requestedMediaSourceId = "source-1",
                initialStartPositionTicks = startTicks,
                playerControllerFactory = playerControllerFactory ?: { controller },
                playbackInfoPlanner =
                    PlaybackInfoPlanner(
                        mediaRepository = repository,
                        directPlayPlanner = DirectPlayPlanner(),
                        playbackDiagnosticsContext = playbackDiagnosticsContext,
                    ),
                reportingQueue = reportingQueue,
                getItemDetail = GetItemDetailUseCase(repository),
                getMediaSegments = GetMediaSegmentsUseCase(repository),
                getChronologicalEpisodeQueue =
                    GetChronologicalEpisodeQueueUseCase(
                        getSeriesSeasonsUseCase = GetSeriesSeasonsUseCase(repository),
                        getSeasonEpisodesUseCase = GetSeasonEpisodesUseCase(repository),
                    ),
                getPlaybackLaunchContext =
                    GetPlaybackLaunchContextUseCase(
                        getPlaybackPreferences = GetPlaybackPreferencesUseCase(preferencesStore),
                        getPlaybackSelection = selectionStore?.let(::GetPlaybackSelectionUseCase),
                        getSubtitleSelection = GetSubtitleSelectionUseCase(subtitleStore),
                    ),
                saveSubtitleSelection = SaveSubtitleSelectionAction(subtitleStore, backgroundScope),
                savePlaybackSelection =
                    selectionStore?.let { store ->
                        SavePlaybackSelectionAction(store, backgroundScope, dispatcher)
                    },
                imageUrlBuilder = JellyfinImageUrlBuilder(),
                deviceInfoProvider = FakeTvDeviceInfoProvider(),
                dispatchers = TvosDispatchers(main = dispatcher, work = workDispatcher ?: dispatcher),
                playbackDiagnosticsContext = playbackDiagnosticsContext,
                playbackHealthGuidancePolicy = playbackHealthGuidancePolicy,
                monotonicTimeMs = { testScheduler.currentTime },
            )
        return Fixture(presenter, controller, reporter, repository, preferencesStore, subtitleStore, reportingQueue)
    }

    private fun postCloseReportingSession() =
        PlaybackReportingSession(
            generation = 999L,
            session = testSession(),
            plan =
                PlaybackPlan(
                    itemId = "post-close-item",
                    mediaSourceId = "post-close-source",
                    startPositionMs = 0L,
                    streamMode = StreamMode.DirectPlay,
                    streamUrl = "",
                    progressReportingPolicy = ProgressReportingPolicy(reportIntervalMs = 1_000L),
                ),
            playSessionId = "post-close-session",
        )

    private class FakeTvPlaybackSelectionStore : PlaybackSelectionStore {
        val values = mutableMapOf<PlaybackSelectionKey, PlaybackSelection>()

        override suspend fun get(key: PlaybackSelectionKey): PlaybackSelection? = values[key]

        override suspend fun save(
            key: PlaybackSelectionKey,
            selection: PlaybackSelection,
        ) {
            values[key] = selection
        }

        override suspend fun delete(key: PlaybackSelectionKey) {
            values.remove(key)
        }

        override suspend fun clearServerScoped() {
            values.clear()
        }

        override suspend fun clearServerScoped(serverId: String) {
            values.keys.removeAll { key -> key.serverId == serverId }
        }

        override suspend fun clearAccount(
            serverId: String,
            userId: String,
        ) {
            values.keys.removeAll { key -> key.serverId == serverId && key.userId == userId }
        }
    }
}

private class TvRecordingDispatcher(
    private val delegate: kotlinx.coroutines.CoroutineDispatcher,
) : kotlinx.coroutines.CoroutineDispatcher() {
    var running = false
        private set

    override fun dispatch(
        context: kotlin.coroutines.CoroutineContext,
        block: Runnable,
    ) {
        delegate.dispatch(context) {
            running = true
            try {
                block.run()
            } finally {
                running = false
            }
        }
    }
}
