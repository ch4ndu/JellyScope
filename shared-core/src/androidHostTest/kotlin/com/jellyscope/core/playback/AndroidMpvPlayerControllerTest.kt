// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import com.jellyscope.core.domain.model.PlaybackTimingKind
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.AudioActivationTarget
import com.jellyscope.core.domain.playback.EmbeddedAudioSelection
import com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.PlannedEmbeddedTrack
import com.jellyscope.core.domain.playback.PlaybackBackendConstructionStage
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleActivationIdentity
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.jellyscope.core.util.LogScrubber
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidMpvPlayerControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val session =
        Session(
            serverUrl = "https://jellyfin.example",
            serverId = "server",
            serverName = "Jellyfin",
            userId = "user",
            userName = "User",
            accessToken = "token",
            deviceId = "device",
        )

    @Test
    fun prepareStaysPausedUntilPlayAndCurrentEofCompletesUnboundedStream() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val controller = controller(engine, dispatcher)

            controller.prepare(plan(startPositionMs = 0L), null)
            runCurrent()
            assertEquals(PlaybackStatus.Loading, controller.playbackState.value.status)
            controller.play()

            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            runCurrent()
            assertEquals(PlaybackStatus.Paused, controller.playbackState.value.status)

            val surface = Surface(SurfaceTexture(1))
            controller.simulateSurfaceCreatedForTest(surface)
            runCurrent()
            assertEquals(PlaybackStatus.Buffering, controller.playbackState.value.status)
            engine.emit(AndroidMpvEvent.PropertyBoolean("pause", false))
            engine.emit(AndroidMpvEvent.PropertyDouble("time-pos", 0.1))
            runCurrent()
            assertEquals(PlaybackStatus.Playing, controller.playbackState.value.status)
            assertEquals(false, engine.booleanProperties["pause"])

            engine.emit(AndroidMpvEvent.PropertyBoolean("eof-reached", true))
            runCurrent()
            assertEquals(PlaybackStatus.Completed, controller.playbackState.value.status)
            assertEquals(null, controller.playbackState.value.durationMs)

            controller.release()
            runCurrent()
            assertTrue(engine.destroyed)
            surface.release()
        }

    @Test
    fun replacementSurfaceKeepsDimensionsAndIgnoresStaleReleaseCallbacks() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val controller = controller(engine, dispatcher)
            val firstView = controller.createSurfaceView(context)
            val secondView = controller.createSurfaceView(context)
            val firstSurface = Surface(SurfaceTexture(11))
            val secondSurface = Surface(SurfaceTexture(12))

            controller.prepare(plan(0L), null)
            runCurrent()
            controller.attachSurface(firstView)
            controller.simulateSurfaceCreatedForTest(firstSurface, width = 1_080, height = 2_400, owner = firstView)
            runCurrent()

            assertEquals("1080x2400", engine.stringProperties["android-surface-size"])
            assertEquals("yes", engine.optionValues.last { (name, _) -> name == "force-window" }.second)
            assertEquals(firstSurface, engine.attachedSurface)

            controller.attachSurface(secondView)
            controller.simulateSurfaceCreatedForTest(secondSurface, width = 2_400, height = 1_080, owner = secondView)
            runCurrent()
            val detachCountAfterReplacement = engine.detachSurfaceCount

            controller.detachSurface(firstView)
            controller.simulateSurfaceDestroyedForTest(owner = firstView)
            runCurrent()

            assertEquals(detachCountAfterReplacement, engine.detachSurfaceCount)
            assertEquals(secondSurface, engine.attachedSurface)
            assertEquals("2400x1080", engine.stringProperties["android-surface-size"])

            controller.release()
            runCurrent()
            firstSurface.release()
            secondSurface.release()
        }

    @Test
    fun qualityReplanRetainsNativeSurfaceAndDispatchesReplacementLoad() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val controller =
                controller(
                    engine = engine,
                    dispatcher = dispatcher,
                    initialCaBundlePath = "/tmp/jellyscope-test-trust.pem",
                )
            val surface = Surface(SurfaceTexture(13))

            controller.simulateSurfaceCreatedForTest(surface)
            runCurrent()
            controller.prepare(plan(0L), null)
            runCurrent()
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            runCurrent()

            val replacementPlan =
                plan(39_100L).copy(
                    streamMode = StreamMode.Transcode,
                    streamUrl = "https://jellyfin.example/videos/item/transcode.ts",
                )
            controller.prepare(replacementPlan, null)
            runCurrent()

            assertEquals(1, engine.attachSurfaceCount)
            assertEquals(
                listOf(
                    "https://jellyfin.example/videos/item",
                    "https://jellyfin.example/videos/item/transcode.ts",
                ),
                engine.commands
                    .filter { command -> command.firstOrNull() == "loadfile" }
                    .map { command -> command[1] },
            )

            controller.release()
            runCurrent()
            surface.release()
        }

    @Test
    fun trackSelectionWaitsForExactSelectorReadback() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val controller = controller(engine, dispatcher)
            val target = AudioActivationTarget(requestId = 1L, itemId = "item", streamIndex = 10)
            val track =
                PlannedEmbeddedTrack(
                    jellyfinStreamIndex = 10,
                    filteredContainerOrdinal = 0,
                    codec = "aac",
                    normalizedLanguage = "en",
                    label = "English",
                )

            controller.prepare(plan(startPositionMs = 0L, audioTarget = target), null)
            runCurrent()
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            engine.emit(
                AndroidMpvEvent.PropertyString(
                    "track-list",
                    "[{\"id\":4,\"type\":\"audio\",\"external\":false,\"ff-index\":10,\"codec\":\"aac\",\"lang\":\"en\",\"title\":\"English\"}]",
                ),
            )
            controller.selectEmbeddedAudio(EmbeddedAudioSelection(target, track))
            runCurrent()
            assertEquals(AudioActivationState.Pending(target), controller.playbackState.value.audioActivation)
            engine.emit(AndroidMpvEvent.PropertyLong("aid", 4L))
            runCurrent()
            assertEquals(AudioActivationState.Active(target), controller.playbackState.value.audioActivation)
            controller.release()
        }

    @Test
    fun alreadySelectedAudioTrackConfirmsWithoutDuplicateAidEvent() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val controller = controller(engine, dispatcher)
            val target = AudioActivationTarget(requestId = 2L, itemId = "item", streamIndex = 10)
            val track =
                PlannedEmbeddedTrack(
                    jellyfinStreamIndex = 10,
                    filteredContainerOrdinal = 0,
                    codec = "aac",
                    normalizedLanguage = "en",
                    label = "English",
                )

            controller.prepare(plan(startPositionMs = 0L, audioTarget = target), null)
            runCurrent()
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            engine.emit(
                AndroidMpvEvent.PropertyString(
                    "track-list",
                    "[{\"id\":4,\"type\":\"audio\",\"external\":false,\"selected\":true," +
                        "\"ff-index\":10,\"codec\":\"aac\",\"lang\":\"en\",\"title\":\"English\"}]",
                ),
            )

            controller.selectEmbeddedAudio(EmbeddedAudioSelection(target, track))
            runCurrent()

            assertEquals(AudioActivationState.Active(target), controller.playbackState.value.audioActivation)
            controller.release()
        }

    @Test
    fun alreadySelectedSubtitleTrackConfirmsWithoutDuplicateSidEvent() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val controller = controller(engine, dispatcher)
            val target =
                SubtitleActivationTarget(
                    requestId = 3L,
                    itemId = "item",
                    identity = SubtitleActivationIdentity.JellyfinTrack(streamIndex = 11),
                    kind = LocalSubtitleKind.EmbeddedText,
                )
            val track =
                PlannedEmbeddedTrack(
                    jellyfinStreamIndex = 11,
                    filteredContainerOrdinal = 0,
                    codec = "srt",
                    normalizedLanguage = "en",
                    label = "English",
                )

            controller.prepare(plan(startPositionMs = 0L, subtitleTarget = target), null)
            runCurrent()
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            engine.emit(
                AndroidMpvEvent.PropertyString(
                    "track-list",
                    "[{\"id\":4,\"type\":\"sub\",\"external\":false,\"selected\":true," +
                        "\"ff-index\":11,\"codec\":\"subrip\",\"lang\":\"en\",\"title\":\"English\"}]",
                ),
            )

            controller.selectEmbeddedSubtitle(EmbeddedSubtitleSelection(target, track))
            runCurrent()

            assertEquals(SubtitleActivationState.Active(target), controller.playbackState.value.subtitleActivation)
            controller.release()
        }

    @Test
    fun retryAfterSubtitleOffToEmbeddedSwitchDoesNotDisableTheSelectedTrack() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val controller = controller(engine, dispatcher)
            val target =
                SubtitleActivationTarget(
                    requestId = 2L,
                    itemId = "item",
                    identity = SubtitleActivationIdentity.JellyfinTrack(streamIndex = 11),
                    kind = LocalSubtitleKind.EmbeddedText,
                )
            val track =
                PlannedEmbeddedTrack(
                    jellyfinStreamIndex = 11,
                    filteredContainerOrdinal = 0,
                    codec = "srt",
                    normalizedLanguage = "en",
                    label = "English",
                )
            val trackList =
                "[{\"id\":4,\"type\":\"sub\",\"external\":false," +
                    "\"ff-index\":11,\"codec\":\"subrip\",\"lang\":\"en\",\"title\":\"English\"}]"

            controller.prepare(plan(startPositionMs = 0L), null)
            runCurrent()
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            engine.emit(AndroidMpvEvent.PropertyString("track-list", trackList))
            controller.selectEmbeddedSubtitle(EmbeddedSubtitleSelection(target, track))
            runCurrent()
            assertEquals(4L, engine.longProperties["sid"])

            controller.retry()
            runCurrent()
            engine.stringProperties.remove("sid")
            engine.longProperties.remove("sid")
            engine.emit(AndroidMpvEvent.PropertyString("track-list", trackList))
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            runCurrent()

            assertEquals(4L, engine.longProperties["sid"])
            assertEquals(null, engine.stringProperties["sid"])
            controller.release()
        }

    @Test
    fun eagerEngineAdmissionReusesSuccessAndDestroysFailure() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val success = FakeAndroidMpvEngine()
            val controller = controller(success, dispatcher, initialCaBundlePath = "/tmp/jellyscope-test-trust.pem")

            assertEquals(1, success.initializeCount)
            assertEquals(AndroidMpvLogRequestLevel.Error, success.requestedLogLevel)
            assertTrue(success.callOrder.indexOf("request-log:error") < success.callOrder.indexOf("initialize"))
            controller.prepare(plan(0L), null)
            runCurrent()
            assertEquals(1, success.initializeCount)
            controller.release()
            runCurrent()
            assertTrue(success.destroyed)

            val failure = FakeAndroidMpvEngine(initializeFailure = IllegalStateException("init"))
            assertFailsWith<IllegalStateException> {
                controller(failure, dispatcher, initialCaBundlePath = "/tmp/jellyscope-test-trust.pem")
            }
            assertTrue(failure.destroyed)
        }

    @Test
    fun rejectedNativeOptionReportsTheSafeOptionIdentityAndNativeCode() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val engine = FakeAndroidMpvEngine(rejectedOption = "config", rejectedOptionCode = -5)

            val failure =
                assertFailsWith<AndroidMpvInitializationException> {
                    controller(engine, dispatcher, initialCaBundlePath = "/tmp/jellyscope-test-trust.pem")
                }

            assertEquals(PlaybackBackendConstructionStage.ApplyNativeOption, failure.constructionStage)
            assertEquals("config", failure.configurationKey)
            assertEquals(-5, failure.nativeFailureCode)
            assertTrue(engine.destroyed)
        }

    @Test
    fun rejectedNativeLogRequestReportsTheSafeIdentityAndNativeCode() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val engine = FakeAndroidMpvEngine(rejectedLogRequestCode = -6)

            val failure =
                assertFailsWith<AndroidMpvInitializationException> {
                    controller(engine, dispatcher, initialCaBundlePath = "/tmp/jellyscope-test-trust.pem")
                }

            assertEquals(PlaybackBackendConstructionStage.ApplyNativeOption, failure.constructionStage)
            assertEquals("mpv-log-level", failure.configurationKey)
            assertEquals(-6, failure.nativeFailureCode)
            assertTrue(engine.destroyed)
        }

    @Test
    fun runtimeSurfaceOptionFailureSurvivesSanitizedCaptureBeforePrepare() {
        val diagnostic =
            androidMpvSurfaceOptionFailureDiagnostic(
                prepareSequence = null,
                nativeCode = -5,
            )

        assertTrue(diagnostic.contains("event=native-lifecycle"))
        assertTrue(diagnostic.contains("backendConfigurationKey=force-window"))
        assertTrue(diagnostic.contains("nativeCode=-5"))
        assertTrue(LogScrubber.capture("AndroidMpvPlayerController", diagnostic) != null)
    }

    @Test
    fun releaseDetachesNativeSurfaceWhenDisablingForceWindowThrows() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val engine = FakeAndroidMpvEngine()
            val controller = controller(engine, dispatcher, initialCaBundlePath = "/tmp/jellyscope-test-trust.pem")
            val surfaceTexture = SurfaceTexture(0)
            val surface = Surface(surfaceTexture)

            controller.simulateSurfaceCreatedForTest(surface)
            runCurrent()
            engine.throwOnForceWindowDisable = true
            controller.release()
            runCurrent()

            assertEquals(1, engine.detachSurfaceCount)
            surface.release()
            surfaceTexture.release()
        }

    @Test
    fun loadCommandPlacesPerFileOptionsAfterTheRequiredPlaylistIndex() {
        assertEquals(
            listOf(
                "loadfile",
                "https://jellyfin.example/videos/item",
                "replace",
                "-1",
                "start=97.309",
            ),
            androidMpvLoadCommand(
                streamUrl = "https://jellyfin.example/videos/item",
                startPositionMs = 97_309L,
            ).toList(),
        )
    }

    @Test
    fun replacementEndFileIsConsumedBeforeNewFileLoaded() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val controller = controller(engine, dispatcher)

            controller.prepare(plan(0L), null)
            runCurrent()
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            runCurrent()

            controller.prepare(plan(2_000L), null)
            runCurrent()
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.END_FILE))
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            runCurrent()

            assertEquals(PlaybackStatus.Paused, controller.playbackState.value.status)
            assertEquals(null, controller.playbackState.value.error)
            controller.release()
        }

    @Test
    fun replacementWhileFirstLoadIsOpeningConsumesOnlyTheAbortedLoadEnd() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val controller = controller(engine, dispatcher)

            controller.prepare(plan(0L), null)
            runCurrent()
            controller.prepare(plan(3_000L), null)
            runCurrent()

            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.END_FILE))
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            runCurrent()

            assertEquals(PlaybackStatus.Paused, controller.playbackState.value.status)
            assertEquals(null, controller.playbackState.value.error)
            controller.release()
        }

    @Test
    fun seekBurstCommitsOnlyTheNewestNativeTarget() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val controller = controller(engine, dispatcher)

            controller.prepare(plan(startPositionMs = 0L), null)
            runCurrent()
            controller.seekTo(1_000L)
            controller.seekTo(2_000L)
            runCurrent()
            assertTrue(engine.commands.none { command -> command.firstOrNull() == "seek" })

            advanceTimeBy(250L)
            runCurrent()
            assertEquals(
                listOf("seek", "2.0", "absolute+exact"),
                engine.commands.single { command -> command.firstOrNull() == "seek" },
            )
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            engine.emit(AndroidMpvEvent.PropertyDouble("time-pos", 0.0))
            runCurrent()
            assertEquals(PlaybackStatus.Buffering, controller.playbackState.value.status)
            assertEquals(2_000L, controller.playbackState.value.positionMs)
            engine.emit(AndroidMpvEvent.PropertyDouble("time-pos", 2.0))
            runCurrent()
            assertEquals(PlaybackStatus.Paused, controller.playbackState.value.status)
            controller.release()
        }

    @Test
    fun forwardSeekKeepsRequestedTargetAcrossStaleClockTick() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val controller = controller(engine, dispatcher)

            controller.prepare(plan(startPositionMs = 0L), null)
            runCurrent()
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            engine.emit(AndroidMpvEvent.PropertyDouble("time-pos", 10.0))
            runCurrent()

            controller.seekTo(20_000L)
            advanceTimeBy(250L)
            runCurrent()
            engine.emit(AndroidMpvEvent.PropertyDouble("time-pos", 10.1))
            runCurrent()

            assertEquals(20_000L, controller.playbackState.value.positionMs)
            assertEquals(PlaybackStatus.Buffering, controller.playbackState.value.status)

            engine.emit(AndroidMpvEvent.PropertyDouble("time-pos", 20.0))
            runCurrent()
            assertEquals(20_000L, controller.playbackState.value.positionMs)
            assertEquals(PlaybackStatus.Paused, controller.playbackState.value.status)
            controller.release()
        }

    @Test
    fun backwardSeekKeepsRequestedTargetAcrossStaleClockTick() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val controller = controller(engine, dispatcher)

            controller.prepare(plan(startPositionMs = 0L), null)
            runCurrent()
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            engine.emit(AndroidMpvEvent.PropertyDouble("time-pos", 20.0))
            runCurrent()

            controller.seekTo(10_000L)
            advanceTimeBy(250L)
            runCurrent()
            engine.emit(AndroidMpvEvent.PropertyDouble("time-pos", 19.9))
            runCurrent()

            assertEquals(10_000L, controller.playbackState.value.positionMs)
            assertEquals(PlaybackStatus.Buffering, controller.playbackState.value.status)

            engine.emit(AndroidMpvEvent.PropertyDouble("time-pos", 10.0))
            runCurrent()
            assertEquals(10_000L, controller.playbackState.value.positionMs)
            assertEquals(PlaybackStatus.Paused, controller.playbackState.value.status)
            controller.release()
        }

    @Test
    fun unconfirmedSeekReleasesSeekingStateAfterTheBoundedWatchdog() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val controller = controller(engine, dispatcher)

            controller.prepare(plan(startPositionMs = 0L), null)
            runCurrent()
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            engine.emit(AndroidMpvEvent.PropertyDouble("time-pos", 20.0))
            runCurrent()

            controller.seekTo(10_000L)
            advanceTimeBy(250L)
            runCurrent()
            engine.emit(AndroidMpvEvent.PropertyDouble("time-pos", 20.2))
            runCurrent()
            assertEquals(10_000L, controller.playbackState.value.positionMs)
            assertEquals(PlaybackStatus.Buffering, controller.playbackState.value.status)

            advanceTimeBy(5_000L)
            runCurrent()
            assertEquals(PlaybackStatus.Paused, controller.playbackState.value.status)

            engine.emit(AndroidMpvEvent.PropertyDouble("time-pos", 20.5))
            runCurrent()
            assertEquals(20_500L, controller.playbackState.value.positionMs)
            assertEquals(PlaybackStatus.Paused, controller.playbackState.value.status)
            controller.release()
        }

    @Test
    fun confirmedSeekCancelsTheWatchdogAndKeepsObservedPosition() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val controller = controller(engine, dispatcher)

            controller.prepare(plan(startPositionMs = 0L), null)
            runCurrent()
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            engine.emit(AndroidMpvEvent.PropertyDouble("time-pos", 20.0))
            runCurrent()

            controller.seekTo(10_000L)
            advanceTimeBy(250L)
            runCurrent()
            engine.emit(AndroidMpvEvent.PropertyDouble("time-pos", 10.0))
            runCurrent()
            assertEquals(PlaybackStatus.Paused, controller.playbackState.value.status)

            advanceTimeBy(5_000L)
            runCurrent()
            engine.emit(AndroidMpvEvent.PropertyDouble("time-pos", 10.5))
            runCurrent()
            assertEquals(10_500L, controller.playbackState.value.positionMs)
            assertEquals(PlaybackStatus.Paused, controller.playbackState.value.status)
            controller.release()
        }

    @Test
    fun explicitSubtitleOffWinsOverThePreparedSubtitleAsset() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val target = externalSubtitleTarget()
            val asset = remoteSubtitleAsset()
            val controller = controller(engine, dispatcher, subtitleResource = asset.url)

            controller.prepare(plan(0L, subtitleTarget = target, subtitleAsset = asset), asset)
            runCurrent()
            controller.selectEmbeddedSubtitle(null)
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            runCurrent()

            assertEquals("no", engine.stringProperties["sid"])
            assertTrue(engine.commands.none { command -> command.firstOrNull() == "sub-add" })
            assertEquals(SubtitleActivationState.None, controller.playbackState.value.subtitleActivation)
            controller.release()
        }

    @Test
    fun unresolvedEmbeddedSelectionsLeavePendingThroughTheBoundedTimeout() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val audioTarget = AudioActivationTarget(requestId = 7L, itemId = "item", streamIndex = 10)
            val subtitleTarget =
                SubtitleActivationTarget(
                    requestId = 8L,
                    itemId = "item",
                    identity = SubtitleActivationIdentity.JellyfinTrack(streamIndex = 11),
                    kind = LocalSubtitleKind.EmbeddedText,
                )
            val audioTrack =
                PlannedEmbeddedTrack(
                    jellyfinStreamIndex = 10,
                    filteredContainerOrdinal = 0,
                    codec = "aac",
                    normalizedLanguage = "en",
                    label = "English",
                )
            val subtitleTrack =
                PlannedEmbeddedTrack(
                    jellyfinStreamIndex = 11,
                    filteredContainerOrdinal = 0,
                    codec = "srt",
                    normalizedLanguage = "en",
                    label = "English",
                )
            val engine = FakeAndroidMpvEngine()
            val controller = controller(engine, dispatcher)

            controller.prepare(plan(0L, audioTarget = audioTarget, subtitleTarget = subtitleTarget), null)
            runCurrent()
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            engine.emit(AndroidMpvEvent.PropertyString("track-list", "[]"))
            controller.selectEmbeddedAudio(EmbeddedAudioSelection(audioTarget, audioTrack))
            controller.selectEmbeddedSubtitle(EmbeddedSubtitleSelection(subtitleTarget, subtitleTrack))
            runCurrent()

            assertEquals(AudioActivationState.Pending(audioTarget), controller.playbackState.value.audioActivation)
            assertEquals(SubtitleActivationState.Pending(subtitleTarget), controller.playbackState.value.subtitleActivation)
            advanceTimeBy(3_000L)
            runCurrent()
            assertEquals(AudioActivationState.Unavailable(audioTarget), controller.playbackState.value.audioActivation)
            assertEquals(SubtitleActivationState.Unavailable(subtitleTarget), controller.playbackState.value.subtitleActivation)
            controller.release()
        }

    @Test
    fun queuedOldGenerationFileLoadedCannotReadyTheReplacement() =
        runTest {
            val nativeDispatcher = UnconfinedTestDispatcher(testScheduler)
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            val engine = FakeAndroidMpvEngine()
            val controller = controller(engine, nativeDispatcher, mainDispatcher = mainDispatcher)

            controller.prepare(plan(0L), null)
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            controller.prepare(plan(5_000L), null)
            runCurrent()

            assertEquals(PlaybackStatus.Loading, controller.playbackState.value.status)
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            runCurrent()
            assertEquals(PlaybackStatus.Paused, controller.playbackState.value.status)
            controller.release()
            runCurrent()
        }

    @Test
    fun externalSubtitleRequiresExactSelectedTrackReadback() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val target = externalSubtitleTarget()
            val asset = remoteSubtitleAsset()
            val controller = controller(engine, dispatcher, subtitleResource = asset.url)

            controller.prepare(plan(0L, subtitleTarget = target, subtitleAsset = asset), asset)
            runCurrent()
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            runCurrent()
            val subtitleCommand = engine.commands.single { command -> command.firstOrNull() == "sub-add" }
            assertEquals(asset.url, subtitleCommand[1])
            val nativeTrackTitle = subtitleCommand[3]
            assertEquals("jellyscope-external-42-1", nativeTrackTitle)

            engine.emit(
                AndroidMpvEvent.PropertyString(
                    "track-list",
                    "[{\"id\":7,\"type\":\"sub\",\"external\":true,\"title\":\"$nativeTrackTitle\",\"selected\":false}]",
                ),
            )
            runCurrent()
            assertEquals(SubtitleActivationState.Pending(target), controller.playbackState.value.subtitleActivation)

            engine.emit(
                AndroidMpvEvent.PropertyString(
                    "track-list",
                    "[{\"id\":7,\"type\":\"sub\",\"external\":true,\"title\":\"$nativeTrackTitle\",\"selected\":true}]",
                ),
            )
            runCurrent()
            assertEquals(SubtitleActivationState.Active(target), controller.playbackState.value.subtitleActivation)
            controller.release()
        }

    @Test
    fun createOrInitFailureDestroysTheFakeEngineAndFailsTheGeneration() =
        runTest {
            val engine = FakeAndroidMpvEngine(initializeFailure = IllegalStateException("init"))
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val controller = controller(engine, dispatcher)

            controller.prepare(plan(0L), null)
            runCurrent()

            assertTrue(engine.destroyed)
            assertEquals(PlaybackStatus.Failed, controller.playbackState.value.status)
            controller.release()
        }

    @Test
    fun startupTimeoutFailsOnlyAnUnloadedGeneration() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val controller = controller(engine, dispatcher, startupTimeout = 1.milliseconds)

            controller.prepare(plan(0L), null)
            runCurrent()
            advanceTimeBy(1L)
            runCurrent()

            assertEquals(PlaybackStatus.Failed, controller.playbackState.value.status)
            controller.release()
        }

    @Test
    fun lateNativeEventsAfterExplicitStopCannotPublishCompletion() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val controller = controller(engine, dispatcher)

            controller.prepare(plan(0L), null)
            runCurrent()
            controller.stop()
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            engine.emit(AndroidMpvEvent.PropertyBoolean("eof-reached", true))
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.END_FILE))
            runCurrent()

            assertEquals(PlaybackStatus.Idle, controller.playbackState.value.status)
            controller.release()
        }

    @Test
    fun nativePropertiesProjectBufferAndCodecDiagnosticsWithoutFirstFrameClaims() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val controller = controller(engine, dispatcher)

            controller.prepare(plan(0L), null)
            runCurrent()
            engine.emit(AndroidMpvEvent.PropertyString("video-codec", "h264"))
            engine.emit(AndroidMpvEvent.PropertyString("hwdec-current", "mediacodec-copy"))
            engine.emit(AndroidMpvEvent.PropertyLong("width", 1_920L))
            engine.emit(AndroidMpvEvent.PropertyLong("height", 1_080L))
            engine.emit(AndroidMpvEvent.PropertyDouble("container-fps", 50.0))
            engine.emit(AndroidMpvEvent.PropertyDouble("time-pos", 4.0))
            engine.emit(AndroidMpvEvent.PropertyDouble("demuxer-cache-time", 10.0))
            engine.emit(AndroidMpvEvent.PropertyDouble("cache-speed", 125_000.0))
            runCurrent()

            val diagnostics = controller.runtimeDiagnostics.value
            assertEquals("h264", diagnostics.videoDecoderName)
            assertEquals(1_920, diagnostics.videoWidth)
            assertEquals(1_080, diagnostics.videoHeight)
            assertEquals(50.0, diagnostics.videoFrameRate)
            assertEquals(6_000L, diagnostics.bufferedAheadMs)
            assertEquals(10_000L, controller.playbackState.value.bufferedPositionMs)
            assertEquals(1_000_000L, diagnostics.bandwidthEstimateBps)
            assertEquals(false, controller.videoOutputMeasurementCapabilities.isSupported)
            controller.release()
        }

    @Test
    fun retryPreservesSpeedStyleAndBothSignTimingOffsets() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val controller = controller(engine, dispatcher)

            controller.prepare(plan(0L), null)
            runCurrent()
            controller.setPlaybackSpeed(1.5f)
            controller.timingController.setOffset(PlaybackTimingKind.Audio, 900L)
            controller.timingController.setOffset(PlaybackTimingKind.Subtitle, -700L)
            controller.retry()
            runCurrent()

            assertEquals(1.5f, controller.playbackState.value.playbackSpeed)
            assertEquals(900L, controller.timingController.timingState.value.audio.offsetMs)
            assertEquals(-700L, controller.timingController.timingState.value.subtitle.offsetMs)
            controller.release()
        }

    @Test
    fun tvSurfaceLossReloadsOnceAndCannotResurrectAfterTheNextLoss() =
        runTest {
            val engine = FakeAndroidMpvEngine()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val controller =
                controller(
                    engine = engine,
                    dispatcher = dispatcher,
                    enginePolicy = androidMpvEnginePolicy(AndroidMpvDeviceFacts(isTelevision = true, isEmulator = false)),
                )
            val firstSurface = Surface(SurfaceTexture(2))
            val secondSurface = Surface(SurfaceTexture(3))
            val thirdSurface = Surface(SurfaceTexture(4))

            controller.prepare(plan(0L), null)
            runCurrent()
            controller.simulateSurfaceCreatedForTest(firstSurface)
            controller.play()
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            engine.emit(AndroidMpvEvent.PropertyBoolean("pause", false))
            engine.emit(AndroidMpvEvent.PropertyDouble("time-pos", 1.0))
            runCurrent()
            assertEquals(PlaybackStatus.Playing, controller.playbackState.value.status)
            val firstLoadCount = engine.commands.count { command -> command.firstOrNull() == "loadfile" }

            controller.simulateSurfaceDestroyedForTest()
            controller.simulateSurfaceCreatedForTest(secondSurface)
            runCurrent()
            assertEquals(PlaybackStatus.Loading, controller.playbackState.value.status)
            assertEquals(firstLoadCount + 1, engine.commands.count { command -> command.firstOrNull() == "loadfile" })

            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.END_FILE))
            engine.emit(AndroidMpvEvent.NativeEvent(AndroidMpvNativeEventIds.FILE_LOADED))
            engine.emit(AndroidMpvEvent.PropertyBoolean("pause", false))
            engine.emit(AndroidMpvEvent.PropertyDouble("time-pos", 1.1))
            runCurrent()
            assertEquals(PlaybackStatus.Playing, controller.playbackState.value.status)

            controller.simulateSurfaceDestroyedForTest()
            controller.simulateSurfaceCreatedForTest(thirdSurface)
            runCurrent()
            assertEquals(PlaybackStatus.Loading, controller.playbackState.value.status)
            assertEquals(firstLoadCount + 2, engine.commands.count { command -> command.firstOrNull() == "loadfile" })

            controller.release()
            runCurrent()
            firstSurface.release()
            secondSurface.release()
            thirdSurface.release()
        }

    private fun controller(
        engine: FakeAndroidMpvEngine,
        dispatcher: CoroutineDispatcher,
        mainDispatcher: CoroutineDispatcher = dispatcher,
        subtitleResource: String? = null,
        startupTimeout: kotlin.time.Duration = 15.seconds,
        enginePolicy: AndroidMpvEnginePolicy = androidMpvEnginePolicy(AndroidMpvDeviceFacts(isTelevision = false, isEmulator = false)),
        initialCaBundlePath: String? = null,
    ) = AndroidMpvPlayerController(
        context = context,
        session = session,
        engineFactory = AndroidMpvEngineFactory { engine },
        nativeDispatcher = dispatcher,
        nativeDispatcherIsAlreadySerial = true,
        mainDispatcher = mainDispatcher,
        networkPolicy =
            object : AndroidMpvNetworkPolicyContract {
                override fun loadRequest(
                    plan: PlaybackPlan,
                    subtitleAsset: SubtitleAsset?,
                ) = AndroidMpvNetworkResult.Accepted(
                    AndroidMpvNetworkRequest(
                        url = plan.streamUrl,
                        authorizationHeader = "Authorization: MediaBrowser Token=\"token\"",
                        subtitleUrl = subtitleResource,
                        localSubtitlePath = null,
                    ),
                )

                override suspend fun ensureTrustBundle(): File = File("/tmp/jellyscope-test-trust.pem")
            },
        startupTimeout = startupTimeout,
        enginePolicy = enginePolicy,
        initialCaBundlePath = initialCaBundlePath,
    )

    private fun plan(
        startPositionMs: Long,
        audioTarget: AudioActivationTarget? = null,
        subtitleTarget: SubtitleActivationTarget? = null,
        subtitleAsset: SubtitleAsset? = null,
    ) = PlaybackPlan(
        itemId = "item",
        mediaSourceId = "source",
        startPositionMs = startPositionMs,
        streamMode = StreamMode.DirectPlay,
        streamUrl = "https://jellyfin.example/videos/item",
        progressReportingPolicy = ProgressReportingPolicy(reportIntervalMs = 10_000L),
        audioActivationTarget = audioTarget,
        subtitleActivationTarget = subtitleTarget,
        subtitleAsset = subtitleAsset,
    )

    private fun externalSubtitleTarget() =
        SubtitleActivationTarget(
            requestId = 42L,
            itemId = "item",
            identity = SubtitleActivationIdentity.JellyfinTrack(streamIndex = 3),
            kind = LocalSubtitleKind.ExternalText,
        )

    private fun remoteSubtitleAsset() =
        SubtitleAsset.JellyfinRemote(
            url = "https://jellyfin.example/subtitles/3.srt",
            mimeType = "application/x-subrip",
            label = "English",
            language = "en",
        )
}

private class FakeAndroidMpvEngine(
    private val initializeFailure: Throwable? = null,
    private val rejectedOption: String? = null,
    private val rejectedOptionCode: Int = -1,
    private val rejectedLogRequestCode: Int = 0,
) : AndroidMpvEngine {
    val commands = mutableListOf<List<String>>()
    val optionValues = mutableListOf<Pair<String, String>>()
    val booleanProperties = mutableMapOf<String, Boolean>()
    val longProperties = mutableMapOf<String, Long>()
    val stringProperties = mutableMapOf<String, String>()
    var destroyed = false
    var initializeCount = 0
    var attachedSurface: Surface? = null
    var attachSurfaceCount = 0
    var detachSurfaceCount = 0
    var requestedLogLevel: AndroidMpvLogRequestLevel? = null
    val callOrder = mutableListOf<String>()
    var throwOnForceWindowDisable = false
    private val observers = mutableListOf<AndroidMpvEngineObserver>()

    override fun setOptionString(
        name: String,
        value: String,
    ): Int {
        optionValues += name to value
        if (throwOnForceWindowDisable && name == "force-window" && value == "no") {
            error("force-window disable failed")
        }
        return if (name == rejectedOption) rejectedOptionCode else 0
    }

    override fun requestLogMessages(level: AndroidMpvLogRequestLevel): Int {
        requestedLogLevel = level
        callOrder += "request-log:${level.name.lowercase()}"
        return rejectedLogRequestCode
    }

    override fun initialize() {
        callOrder += "initialize"
        initializeCount += 1
        initializeFailure?.let { throw it }
    }

    override fun destroy() {
        destroyed = true
    }

    override fun command(arguments: Array<String>) {
        commands += arguments.toList()
    }

    override fun getPropertyBoolean(name: String): Boolean? = booleanProperties[name]

    override fun getPropertyLong(name: String): Long? = null

    override fun getPropertyDouble(name: String): Double? = null

    override fun getPropertyString(name: String): String? = null

    override fun setPropertyBoolean(
        name: String,
        value: Boolean,
    ) {
        booleanProperties[name] = value
    }

    override fun setPropertyLong(
        name: String,
        value: Long,
    ) {
        longProperties[name] = value
    }

    override fun setPropertyDouble(
        name: String,
        value: Double,
    ) = Unit

    override fun setPropertyString(
        name: String,
        value: String,
    ) {
        stringProperties[name] = value
    }

    override fun observeProperty(
        name: String,
        format: Int,
    ) = Unit

    override fun attachSurface(surface: Surface) {
        attachSurfaceCount += 1
        attachedSurface = surface
    }

    override fun detachSurface() {
        attachedSurface = null
        detachSurfaceCount += 1
    }

    override fun addObserver(observer: AndroidMpvEngineObserver) {
        observers += observer
    }

    override fun removeObserver(observer: AndroidMpvEngineObserver) {
        observers -= observer
    }

    fun emit(event: AndroidMpvEvent) {
        observers.toList().forEach { observer -> observer.onEvent(event) }
    }
}
