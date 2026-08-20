// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.AudioActivationTarget
import com.jellyscope.core.domain.playback.EmbeddedAudioSelection
import com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.PlannedEmbeddedTrack
import com.jellyscope.core.domain.playback.PlaybackHealthMeasurementCapabilities
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleActivationIdentity
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.jellyscope.core.domain.playback.VideoOutputMeasurementCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopLibVlcPlayerControllerTest {
    @Test
    fun retryReinitializesAfterInitializationFailureAndClearsTheError() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            var factoryCalls = 0
            val recoveredEngine = RecordingDesktopVlcEngine()
            val subtitleAsset =
                SubtitleAsset.JellyfinRemote(
                    url = "https://jellyfin.example/Videos/item/Subtitles/3/0/Stream.srt",
                    mimeType = "text/vtt",
                    label = "English",
                    language = "eng",
                )
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engineFactory = {
                        factoryCalls += 1
                        if (factoryCalls == 1) {
                            throw DesktopVlcInitializationException()
                        }
                        recoveredEngine
                    },
                    nativeDispatcher = dispatcher,
                )
            try {
                runCurrent()
                controller.prepare(
                    playbackPlan().copy(subtitleAsset = subtitleAsset),
                    subtitleAsset,
                )

                assertEquals(PlaybackStatus.Failed, controller.playbackState.value.status)
                assertEquals(
                    com.jellyscope.core.domain.playback.PlaybackError.UnsupportedMedia,
                    controller.playbackState.value.error,
                )

                controller.retry()
                runCurrent()

                assertEquals(2, factoryCalls)
                assertEquals(PlaybackStatus.Loading, controller.playbackState.value.status)
                assertNull(controller.playbackState.value.error)
                assertEquals(1, recoveredEngine.prepareCalls.size)
                val recoveredSubtitleUrl =
                    recoveredEngine.prepareCalls
                        .single()
                        .subtitleUrl
                assertTrue(recoveredSubtitleUrl?.contains("ApiKey=token") == true)
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun retryReappliesConfirmedAudioSelection() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val descriptor =
                PlannedEmbeddedTrack(
                    jellyfinStreamIndex = 2,
                    filteredContainerOrdinal = 1,
                    codec = "aac",
                    normalizedLanguage = "eng",
                    label = "English",
                )
            val otherDescriptor =
                descriptor.copy(
                    jellyfinStreamIndex = 1,
                    filteredContainerOrdinal = 0,
                    normalizedLanguage = "spa",
                    label = "Spanish",
                )
            val target = AudioActivationTarget(requestId = 1L, itemId = "item", streamIndex = 2)
            val engine =
                RecordingDesktopVlcEngine(
                    audioTrackDescriptions =
                        listOf(
                            DesktopVlcTrack(id = 1, label = "Track 1"),
                            DesktopVlcTrack(id = 2, label = "Track 2"),
                        ),
                    selectableAudioTrackIds = setOf(2),
                )
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(
                    playbackPlan().copy(
                        selectedAudioStreamIndex = 2,
                        embeddedAudioTracks = listOf(otherDescriptor, descriptor),
                        audioActivationTarget = target,
                    ),
                    null,
                )
                controller.selectEmbeddedAudio(EmbeddedAudioSelection(target, descriptor))
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                runCurrent()
                engine.emit(DesktopVlcEvent.Playing)
                runCurrent()
                testScheduler.advanceTimeBy(251L)
                runCurrent()

                assertEquals(AudioActivationState.Active(target), controller.playbackState.value.audioActivation)
                assertEquals(listOf(2), engine.selectedAudioTrackIds)

                controller.retry()
                runCurrent()
                engine.emit(DesktopVlcEvent.Playing)
                runCurrent()
                testScheduler.advanceTimeBy(251L)
                runCurrent()

                assertEquals(listOf(2, 2), engine.selectedAudioTrackIds)
                assertEquals(AudioActivationState.Active(target), controller.playbackState.value.audioActivation)
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun retryReappliesConfirmedSubtitleSelectionWhenItsTargetMatchesThePlan() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val descriptor =
                PlannedEmbeddedTrack(
                    jellyfinStreamIndex = 3,
                    filteredContainerOrdinal = 0,
                    codec = "srt",
                    normalizedLanguage = "eng",
                    label = "English",
                    responseAuthoritativeCohortSize = 1,
                )
            val target =
                SubtitleActivationTarget(
                    requestId = 2L,
                    itemId = "item",
                    identity = SubtitleActivationIdentity.JellyfinTrack(3),
                    kind = LocalSubtitleKind.EmbeddedText,
                )
            val engine =
                RecordingDesktopVlcEngine(
                    subtitleTrackDescriptions = listOf(DesktopVlcTrack(id = 3, label = "Track 1")),
                    selectableSubtitleTrackIds = setOf(3),
                )
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(
                    playbackPlan().copy(
                        subtitleActivationTarget = target,
                    ),
                    null,
                )
                controller.selectEmbeddedSubtitle(EmbeddedSubtitleSelection(target, descriptor))
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                runCurrent()
                engine.emit(DesktopVlcEvent.Playing)
                runCurrent()
                testScheduler.advanceTimeBy(251L)
                runCurrent()

                assertEquals(SubtitleActivationState.Active(target), controller.playbackState.value.subtitleActivation)
                assertEquals(listOf<Int?>(3), engine.selectedSubtitleTrackIds)

                controller.retry()
                runCurrent()
                engine.emit(DesktopVlcEvent.Playing)
                runCurrent()
                testScheduler.advanceTimeBy(251L)
                runCurrent()

                assertEquals(listOf<Int?>(3, 3), engine.selectedSubtitleTrackIds)
                assertEquals(SubtitleActivationState.Active(target), controller.playbackState.value.subtitleActivation)
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun retryDoesNotReapplyConfirmedSubtitleSelectionWhenItsTargetDiffersFromThePlan() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val plannedDescriptor =
                PlannedEmbeddedTrack(
                    jellyfinStreamIndex = 3,
                    filteredContainerOrdinal = 0,
                    codec = "srt",
                    normalizedLanguage = "eng",
                    label = "English",
                )
            val selectedDescriptor =
                plannedDescriptor.copy(
                    jellyfinStreamIndex = 4,
                    normalizedLanguage = "spa",
                    label = "Spanish",
                    responseAuthoritativeCohortSize = 1,
                )
            val plannedTarget =
                SubtitleActivationTarget(
                    requestId = 3L,
                    itemId = "item",
                    identity = SubtitleActivationIdentity.JellyfinTrack(3),
                    kind = LocalSubtitleKind.EmbeddedText,
                )
            val selectedTarget =
                plannedTarget.copy(
                    requestId = 4L,
                    identity = SubtitleActivationIdentity.JellyfinTrack(4),
                )
            val engine =
                RecordingDesktopVlcEngine(
                    subtitleTrackDescriptions = listOf(DesktopVlcTrack(id = 4, label = "Track 1")),
                    selectableSubtitleTrackIds = setOf(4),
                )
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(
                    playbackPlan().copy(
                        subtitleActivationTarget = plannedTarget,
                    ),
                    null,
                )
                controller.selectEmbeddedSubtitle(
                    EmbeddedSubtitleSelection(selectedTarget, selectedDescriptor),
                )
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                runCurrent()
                engine.emit(DesktopVlcEvent.Playing)
                runCurrent()
                testScheduler.advanceTimeBy(251L)
                runCurrent()

                assertEquals(SubtitleActivationState.Active(selectedTarget), controller.playbackState.value.subtitleActivation)
                assertEquals(listOf<Int?>(4), engine.selectedSubtitleTrackIds)

                controller.retry()
                runCurrent()
                engine.emit(DesktopVlcEvent.Playing)
                runCurrent()
                testScheduler.advanceTimeBy(251L)
                runCurrent()

                assertEquals(listOf<Int?>(4), engine.selectedSubtitleTrackIds)
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun retryReassertsExplicitSubtitleOffWhenThePlanHasNoSubtitleTarget() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine = RecordingDesktopVlcEngine()
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(playbackPlan().copy(subtitleActivationTarget = null), null)
                controller.selectEmbeddedSubtitle(null)
                runCurrent()
                val offCallsBeforeRetry = engine.selectedSubtitleTrackIds.count { it == null }

                controller.retry()
                runCurrent()

                assertTrue(engine.selectedSubtitleTrackIds.count { it == null } > offCallsBeforeRetry)
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun backwardSeekResetsBufferedPositionToTheSeekTarget() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine =
                RecordingDesktopVlcEngine(
                    snapshot =
                        DesktopVlcSnapshot(
                            positionMs = 10_000L,
                            durationMs = 100_000L,
                            seekable = true,
                            displayedPictures = null,
                            lostPictures = null,
                            width = null,
                            height = null,
                            frameRate = null,
                            nativeState = DesktopVlcNativeState.Playing,
                        ),
                )
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(playbackPlan(), null)
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                runCurrent()
                testScheduler.advanceTimeBy(251L)
                runCurrent()

                assertEquals(10_000L, controller.playbackState.value.bufferedPositionMs)

                controller.seekTo(5_000L)

                assertEquals(5_000L, controller.playbackState.value.bufferedPositionMs)
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun embeddedSubtitleWithoutExternalAssetRemainsPendingForNativeTrackSelection() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine = RecordingDesktopVlcEngine()
            val target =
                SubtitleActivationTarget(
                    requestId = 1L,
                    itemId = "item",
                    identity = SubtitleActivationIdentity.JellyfinTrack(3),
                    kind = LocalSubtitleKind.EmbeddedText,
                )
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(
                    plan = playbackPlan().copy(subtitleActivationTarget = target),
                    subtitleAsset = null,
                )
                runCurrent()

                assertEquals(
                    SubtitleActivationState.Pending(target),
                    controller.playbackState.value.subtitleActivation,
                )
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun prepareAndPlayBeforeInitializationAndSurfaceReplayExactlyOnce() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine =
                RecordingDesktopVlcEngine(
                    audioTrackDescriptions = listOf(DesktopVlcTrack(id = 7, label = "Track 1")),
                )
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(
                    plan =
                        playbackPlan(
                            streamUrl =
                                "https://jellyfin.example/Videos/item/stream" +
                                    "?foo=bar&api_key=stale",
                        ),
                    subtitleAsset =
                        SubtitleAsset.JellyfinRemote(
                            url = "https://jellyfin.example/Subtitles/1?api_key=old",
                            mimeType = "text/vtt",
                            label = "English",
                            language = "eng",
                        ),
                )
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))

                runCurrent()

                assertEquals(1, engine.prepareCalls.size)
                assertEquals(1, engine.playCount)
                assertEquals(42L, engine.currentDrawable)
                val prepareCall = engine.prepareCalls.single()
                assertTrue(prepareCall.mediaUrl.contains("foo=bar"))
                assertTrue(prepareCall.mediaUrl.contains("ApiKey=token"))
                assertFalse(prepareCall.mediaUrl.contains("stale"))
                assertTrue(prepareCall.subtitleUrl.orEmpty().contains("ApiKey=token"))
                assertFalse(prepareCall.subtitleUrl.orEmpty().contains("old"))
                assertEquals(0L, prepareCall.startPositionMs)
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun crossOriginMediaFailsClosedWithoutPreparingNativeMedia() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine = RecordingDesktopVlcEngine()
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(playbackPlan("https://untrusted.example/video"), null)
                runCurrent()

                assertTrue(engine.prepareCalls.isEmpty())
                assertEquals(PlaybackStatus.Failed, controller.playbackState.value.status)
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun releaseAcknowledgesDetachOnlyAfterDrawableAndEngineAreReleased() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine = RecordingDesktopVlcEngine()
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            val surface = requireNotNull(DesktopVlcSurfaceHandle.create(42L, 7L))
            controller.attachVideoSurface(surface)
            runCurrent()

            var detached = false
            controller.release()
            controller.detachVideoSurface(surface.generation) {
                engine.events += "detach-callback"
                detached = true
            }

            assertFalse(detached)
            runCurrent()

            assertTrue(detached)
            assertTrue(engine.events.indexOf("drawable:null") < engine.events.indexOf("release"))
            assertTrue(engine.events.indexOf("release") < engine.events.indexOf("detach-callback"))
            scope.cancel()
        }

    @Test
    fun libVlcLostPicturesMapOnlyToOutputDrops() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            var clockNanos = 1_000_000_000L
            val engine =
                RecordingDesktopVlcEngine(
                    snapshot =
                        DesktopVlcSnapshot(
                            positionMs = 1_000L,
                            durationMs = 10_000L,
                            seekable = true,
                            displayedPictures = 100L,
                            lostPictures = 3L,
                            width = 7_680,
                            height = 4_320,
                            frameRate = 60.0,
                        ),
                )
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                    monotonicTimeNanos = { clockNanos },
                )
            try {
                assertEquals(
                    PlaybackHealthMeasurementCapabilities(
                        hasReliableBufferingTransitions = true,
                        hasDroppedFrameMeasurements = true,
                        hasReliableFirstVideoOutput = true,
                    ),
                    controller.playbackHealthMeasurementCapabilities,
                )
                controller.prepare(playbackPlan(), null)
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                runCurrent()
                engine.emit(DesktopVlcEvent.Playing)
                runCurrent()
                testScheduler.advanceTimeBy(251L)
                runCurrent()

                val diagnostics = controller.runtimeDiagnostics.value
                assertEquals(3L, diagnostics.droppedVideoFrames)
                assertNull(diagnostics.decoderDroppedVideoFrames)
                assertEquals(3L, diagnostics.outputDroppedVideoFrames)
                assertEquals("LibVLC native NSView", diagnostics.presentationPath)

                // First poll establishes the displayed-picture baseline. A
                // later positive delta is the only event allowed to publish
                // Playing and begin dropped-frame measurement.
                engine.snapshot = engine.snapshot.copy(displayedPictures = 102L)
                testScheduler.advanceTimeBy(251L)
                runCurrent()
                assertEquals(PlaybackStatus.Playing, controller.playbackState.value.status)

                engine.snapshot = engine.snapshot.copy(lostPictures = 8L)
                clockNanos = 3_000_000_000L
                val measurement = async { controller.droppedFrameMeasurements.first() }
                runCurrent()
                testScheduler.advanceTimeBy(251L)
                runCurrent()

                assertEquals(5L, measurement.await().droppedFrames)
                assertEquals(2_000L, measurement.await().intervalMs)
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun aSinglePlannedAudioTrackConfirmsFromNativeStateWhenPlayingEventIsUnavailable() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine = RecordingDesktopVlcEngine()
            val descriptor =
                PlannedEmbeddedTrack(
                    jellyfinStreamIndex = 1,
                    filteredContainerOrdinal = 0,
                    codec = "aac",
                    normalizedLanguage = null,
                    label = null,
                )
            val target = AudioActivationTarget(requestId = 1L, itemId = "item", streamIndex = 1)
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(
                    playbackPlan().copy(
                        selectedAudioStreamIndex = 1,
                        embeddedAudioTracks = listOf(descriptor),
                        audioActivationTarget = target,
                    ),
                    null,
                )
                controller.selectEmbeddedAudio(EmbeddedAudioSelection(target, descriptor))
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                runCurrent()
                testScheduler.advanceTimeBy(251L)
                runCurrent()
                assertEquals(AudioActivationState.Pending(target), controller.playbackState.value.audioActivation)

                engine.snapshot = engine.snapshot.copy(nativeState = DesktopVlcNativeState.Playing)
                testScheduler.advanceTimeBy(251L)
                runCurrent()
                testScheduler.advanceTimeBy(251L)
                runCurrent()

                assertEquals(AudioActivationState.Active(target), controller.playbackState.value.audioActivation)
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun matchingNativeAndPlannedAudioCountsUseContainerOrdinalDespiteLabelDifferences() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine =
                RecordingDesktopVlcEngine(
                    audioTrackDescriptions =
                        listOf(
                            DesktopVlcTrack(id = 1, label = "Track 1"),
                            DesktopVlcTrack(id = 2, label = "Track 2"),
                        ),
                    selectableAudioTrackIds = setOf(2),
                )
            val descriptors =
                listOf(
                    PlannedEmbeddedTrack(
                        jellyfinStreamIndex = 1,
                        filteredContainerOrdinal = 0,
                        codec = "aac",
                        normalizedLanguage = "eng",
                        label = "English AAC stereo",
                    ),
                    PlannedEmbeddedTrack(
                        jellyfinStreamIndex = 2,
                        filteredContainerOrdinal = 1,
                        codec = "aac",
                        normalizedLanguage = "spa",
                        label = "Spanish AAC stereo",
                    ),
                )
            val target = AudioActivationTarget(requestId = 2L, itemId = "item", streamIndex = 2)
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(
                    playbackPlan().copy(
                        selectedAudioStreamIndex = 2,
                        embeddedAudioTracks = descriptors,
                        audioActivationTarget = target,
                    ),
                    null,
                )
                controller.selectEmbeddedAudio(EmbeddedAudioSelection(target, descriptors[1]))
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                runCurrent()
                engine.emit(DesktopVlcEvent.Playing)
                runCurrent()
                testScheduler.advanceTimeBy(251L)
                runCurrent()

                assertEquals(listOf(2), engine.selectedAudioTrackIds)
                assertEquals(AudioActivationState.Active(target), controller.playbackState.value.audioActivation)
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun repeatedTrackEventsDoNotRestartTheSelectionTimeout() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine =
                RecordingDesktopVlcEngine(
                    audioTrackDescriptions = listOf(DesktopVlcTrack(id = 1, label = "Unmatched")),
                )
            val descriptor =
                PlannedEmbeddedTrack(
                    jellyfinStreamIndex = 2,
                    filteredContainerOrdinal = 1,
                    codec = "aac",
                    normalizedLanguage = "spa",
                    label = "Spanish",
                )
            val target = AudioActivationTarget(requestId = 3L, itemId = "item", streamIndex = 2)
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(
                    playbackPlan().copy(
                        selectedAudioStreamIndex = 2,
                        embeddedAudioTracks =
                            listOf(
                                descriptor.copy(filteredContainerOrdinal = 0, jellyfinStreamIndex = 1),
                                descriptor,
                            ),
                        audioActivationTarget = target,
                    ),
                    null,
                )
                controller.selectEmbeddedAudio(EmbeddedAudioSelection(target, descriptor))
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                runCurrent()
                engine.emit(DesktopVlcEvent.Playing)
                runCurrent()

                repeat(30) {
                    testScheduler.advanceTimeBy(100L)
                    engine.emit(DesktopVlcEvent.TracksChanged)
                    runCurrent()
                }

                assertEquals(
                    AudioActivationState.Unavailable(target),
                    controller.playbackState.value.audioActivation,
                )
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun startPositionIsOneShotAcrossPauseResumeAndSeekableEvents() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine = RecordingDesktopVlcEngine()
            engine.snapshot = engine.snapshot.copy(seekable = true)
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(playbackPlan(startPositionMs = 12_000L), null)
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                runCurrent()
                testScheduler.advanceTimeBy(251L)
                runCurrent()

                assertEquals(listOf(12_000L), engine.seeks)

                controller.pause()
                controller.play()
                engine.emit(DesktopVlcEvent.SeekableChanged)
                runCurrent()
                testScheduler.advanceTimeBy(500L)
                runCurrent()

                assertEquals(listOf(12_000L), engine.seeks)
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun nativeStartPositionSkipsRedundantDecoderFlushWhenClockIsAlreadyNearTarget() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine = RecordingDesktopVlcEngine()
            engine.snapshot =
                engine.snapshot.copy(
                    positionMs = 12_250L,
                    seekable = true,
                )
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(playbackPlan(startPositionMs = 12_000L), null)
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                runCurrent()
                testScheduler.advanceTimeBy(251L)
                runCurrent()

                assertEquals(12_000L, engine.prepareCalls.single().startPositionMs)
                assertTrue(engine.seeks.isEmpty())
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun resumeKeepsBufferingAndAudioSuppressedUntilTargetFramesAdvance() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine =
                RecordingDesktopVlcEngine(
                    snapshot =
                        DesktopVlcSnapshot(
                            positionMs = 12_200L,
                            durationMs = 30_000L,
                            seekable = true,
                            displayedPictures = 1L,
                            lostPictures = 0L,
                            width = 7_680,
                            height = 4_320,
                            frameRate = 60.0,
                            nativeState = DesktopVlcNativeState.Playing,
                        ),
                )
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(playbackPlan(startPositionMs = 12_000L), null)
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                runCurrent()
                engine.emit(DesktopVlcEvent.Playing)
                runCurrent()

                assertEquals(PlaybackStatus.Buffering, controller.playbackState.value.status)
                assertFalse(controller.videoPresentationReady.value)
                assertEquals(true, engine.muteCalls.last())

                testScheduler.advanceTimeBy(251L)
                runCurrent()

                assertEquals(PlaybackStatus.Buffering, controller.playbackState.value.status)
                assertFalse(controller.videoPresentationReady.value)
                assertEquals(true, engine.muteCalls.last())

                // The first sample establishes the post-seek baseline; two
                // subsequently displayed frames make the presentation fact
                // trustworthy enough to unsuppress the resumed session.
                engine.snapshot = engine.snapshot.copy(displayedPictures = 3L)
                testScheduler.advanceTimeBy(251L)
                runCurrent()

                assertEquals(PlaybackStatus.Playing, controller.playbackState.value.status)
                assertTrue(controller.videoPresentationReady.value)
                assertEquals(false, engine.muteCalls.last())
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun unavailableDisplayedPictureCounterDoesNotInventFirstOutput() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine =
                RecordingDesktopVlcEngine(
                    snapshot =
                        DesktopVlcSnapshot(
                            positionMs = 1_000L,
                            durationMs = 30_000L,
                            seekable = true,
                            displayedPictures = null,
                            lostPictures = 0L,
                            width = 7_680,
                            height = 4_320,
                            frameRate = 60.0,
                            nativeState = DesktopVlcNativeState.Playing,
                        ),
                )
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(playbackPlan(), null)
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                runCurrent()
                engine.emit(DesktopVlcEvent.Playing)
                testScheduler.advanceTimeBy(251L)
                runCurrent()

                assertFalse(controller.videoPresentationReady.value)
                assertFalse(controller.playbackHealthMeasurementCapabilities.hasReliableFirstVideoOutput)
                assertEquals(VideoOutputMeasurementCapabilities.Unsupported, controller.videoOutputMeasurementCapabilities)
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun staleNativeClockDoesNotBouncePositionAfterUserSeek() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine =
                RecordingDesktopVlcEngine(
                    snapshot =
                        DesktopVlcSnapshot(
                            positionMs = 10_000L,
                            durationMs = 100_000L,
                            seekable = true,
                            displayedPictures = null,
                            lostPictures = null,
                            width = null,
                            height = null,
                            frameRate = null,
                            nativeState = DesktopVlcNativeState.Playing,
                        ),
                )
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(playbackPlan(), null)
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                runCurrent()
                testScheduler.advanceTimeBy(251L)
                runCurrent()

                controller.seekTo(80_000L)
                runCurrent()
                testScheduler.advanceTimeBy(251L)
                runCurrent()

                assertEquals(80_000L, controller.playbackState.value.positionMs)

                engine.snapshot = engine.snapshot.copy(positionMs = 80_500L)
                testScheduler.advanceTimeBy(251L)
                runCurrent()

                assertEquals(80_500L, controller.playbackState.value.positionMs)
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun playingEventDoesNotPromoteAStalePlannedSubtitleOverNewSelection() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val plannedTarget =
                SubtitleActivationTarget(
                    requestId = 10L,
                    itemId = "item",
                    identity = SubtitleActivationIdentity.JellyfinTrack(3),
                    kind = LocalSubtitleKind.EmbeddedText,
                )
            val selectedTarget =
                plannedTarget.copy(
                    requestId = 11L,
                    identity = SubtitleActivationIdentity.JellyfinTrack(4),
                )
            val selectedDescriptor =
                PlannedEmbeddedTrack(
                    jellyfinStreamIndex = 4,
                    filteredContainerOrdinal = 1,
                    codec = "srt",
                    normalizedLanguage = "spa",
                    label = "Spanish",
                )
            val engine = RecordingDesktopVlcEngine()
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(
                    playbackPlan().copy(subtitleActivationTarget = plannedTarget),
                    null,
                )
                controller.selectEmbeddedSubtitle(
                    EmbeddedSubtitleSelection(
                        target = selectedTarget,
                        descriptor = selectedDescriptor,
                    ),
                )
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                runCurrent()
                engine.emit(DesktopVlcEvent.Playing)
                runCurrent()

                assertEquals(
                    SubtitleActivationState.Pending(selectedTarget),
                    controller.playbackState.value.subtitleActivation,
                )
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun exactResponseCohortUsesOrdinalAndWaitsForNativeSubtitleReadback() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val target =
                SubtitleActivationTarget(
                    requestId = 12L,
                    itemId = "item",
                    identity = SubtitleActivationIdentity.JellyfinTrack(3),
                    kind = LocalSubtitleKind.EmbeddedText,
                )
            val descriptor =
                PlannedEmbeddedTrack(
                    jellyfinStreamIndex = 3,
                    filteredContainerOrdinal = 0,
                    codec = "srt",
                    normalizedLanguage = "eng",
                    label = "English",
                    responseAuthoritativeCohortSize = 40,
                )
            val engine =
                RecordingDesktopVlcEngine(
                    subtitleTrackDescriptions =
                        (3..42).map { id -> DesktopVlcTrack(id = id, label = "VLC Track ${id - 2}") },
                    selectableSubtitleTrackIds = setOf(3),
                    subtitleReadbackConfirmAfterAttempts = 2,
                )
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(playbackPlan().copy(subtitleActivationTarget = target), null)
                controller.selectEmbeddedSubtitle(
                    EmbeddedSubtitleSelection(target, descriptor),
                )
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                runCurrent()
                engine.emit(DesktopVlcEvent.Playing)
                runCurrent()

                testScheduler.advanceTimeBy(251L)
                runCurrent()

                assertEquals(listOf<Int?>(3), engine.selectedSubtitleTrackIds)
                assertEquals(SubtitleActivationState.Pending(target), controller.playbackState.value.subtitleActivation)

                testScheduler.advanceTimeBy(251L)
                runCurrent()

                assertEquals(listOf<Int?>(3, 3), engine.selectedSubtitleTrackIds)
                assertEquals(SubtitleActivationState.Active(target), controller.playbackState.value.subtitleActivation)
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun missingCountMismatchedCountAndNonDirectPlayRemainFailClosed() =
        runTest {
            val scenarios =
                listOf(
                    null to StreamMode.DirectPlay,
                    39 to StreamMode.DirectPlay,
                    40 to StreamMode.DirectStream,
                )

            scenarios.forEachIndexed { index, (cohortSize, streamMode) ->
                val dispatcher = StandardTestDispatcher(testScheduler)
                val scope = CoroutineScope(SupervisorJob() + dispatcher)
                val target =
                    SubtitleActivationTarget(
                        requestId = 20L + index,
                        itemId = "item",
                        identity = SubtitleActivationIdentity.JellyfinTrack(3),
                        kind = LocalSubtitleKind.EmbeddedText,
                    )
                val descriptor =
                    PlannedEmbeddedTrack(
                        jellyfinStreamIndex = 3,
                        filteredContainerOrdinal = 0,
                        codec = "srt",
                        normalizedLanguage = "eng",
                        label = "English",
                        responseAuthoritativeCohortSize = cohortSize,
                    )
                val engine =
                    RecordingDesktopVlcEngine(
                        subtitleTrackDescriptions =
                            (3..42).map { id -> DesktopVlcTrack(id = id, label = "VLC Track ${id - 2}") },
                        selectableSubtitleTrackIds = setOf(3),
                    )
                val controller =
                    DesktopLibVlcPlayerController(
                        session = session,
                        stateScope = scope,
                        engine = engine,
                        nativeDispatcher = dispatcher,
                    )
                try {
                    controller.prepare(
                        playbackPlan().copy(streamMode = streamMode, subtitleActivationTarget = target),
                        null,
                    )
                    controller.selectEmbeddedSubtitle(
                        EmbeddedSubtitleSelection(target, descriptor),
                    )
                    controller.play()
                    controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                    runCurrent()
                    engine.emit(DesktopVlcEvent.Playing)
                    runCurrent()
                    testScheduler.advanceTimeBy(251L)
                    runCurrent()

                    assertTrue(engine.selectedSubtitleTrackIds.isEmpty(), "scenario $index")
                    assertEquals(
                        SubtitleActivationState.Pending(target),
                        controller.playbackState.value.subtitleActivation,
                        "scenario $index",
                    )
                } finally {
                    controller.release()
                    runCurrent()
                    scope.cancel()
                }
            }
        }

    @Test
    fun exactResponseCohortFailsBoundedlyWhenNativeSubtitleReadbackNeverMatches() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val target =
                SubtitleActivationTarget(
                    requestId = 30L,
                    itemId = "item",
                    identity = SubtitleActivationIdentity.JellyfinTrack(3),
                    kind = LocalSubtitleKind.EmbeddedText,
                )
            val descriptor =
                PlannedEmbeddedTrack(
                    jellyfinStreamIndex = 3,
                    filteredContainerOrdinal = 0,
                    codec = "srt",
                    normalizedLanguage = "eng",
                    label = "English",
                    responseAuthoritativeCohortSize = 1,
                )
            val engine =
                RecordingDesktopVlcEngine(
                    subtitleTrackDescriptions = listOf(DesktopVlcTrack(id = 3, label = "VLC Track 1")),
                    selectableSubtitleTrackIds = setOf(3),
                    subtitleReadbackConfirmAfterAttempts = Int.MAX_VALUE,
                )
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(playbackPlan().copy(subtitleActivationTarget = target), null)
                controller.selectEmbeddedSubtitle(
                    EmbeddedSubtitleSelection(target, descriptor),
                )
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                runCurrent()
                engine.emit(DesktopVlcEvent.Playing)
                runCurrent()

                testScheduler.advanceTimeBy(2_600L)
                runCurrent()

                assertTrue(engine.selectedSubtitleTrackIds.isNotEmpty())
                assertEquals(
                    SubtitleActivationState.Unavailable(target),
                    controller.playbackState.value.subtitleActivation,
                )
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun completedStateRejectsTrailingEventsAndStopsPolling() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine =
                RecordingDesktopVlcEngine(
                    snapshot =
                        DesktopVlcSnapshot(
                            positionMs = 10_000L,
                            durationMs = 10_000L,
                            seekable = true,
                            displayedPictures = 600L,
                            lostPictures = 0L,
                            width = 7_680,
                            height = 4_320,
                            frameRate = 60.0,
                        ),
                )
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(playbackPlan(), null)
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                runCurrent()
                engine.emit(DesktopVlcEvent.Playing)
                runCurrent()
                testScheduler.advanceTimeBy(251L)
                runCurrent()
                // First observed counter is only the prepare baseline. A later
                // positive delta is the first presentation fact.
                engine.snapshot = engine.snapshot.copy(displayedPictures = 602L)
                testScheduler.advanceTimeBy(251L)
                runCurrent()
                engine.emit(DesktopVlcEvent.EndReached)
                runCurrent()

                assertEquals(PlaybackStatus.Completed, controller.playbackState.value.status)
                val snapshotsAtCompletion = engine.snapshotCount

                engine.emit(DesktopVlcEvent.Buffering)
                engine.emit(DesktopVlcEvent.Stopped)
                runCurrent()
                testScheduler.advanceTimeBy(1_000L)
                runCurrent()

                assertEquals(PlaybackStatus.Completed, controller.playbackState.value.status)
                assertEquals(snapshotsAtCompletion, engine.snapshotCount)
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun staleSurfaceOperationsCannotReplaceOrDetachTheCurrentSurface() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine = RecordingDesktopVlcEngine()
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(84L, 2L)))
                runCurrent()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                var staleDetachAcknowledged = false
                controller.detachVideoSurface(1L) { staleDetachAcknowledged = true }
                runCurrent()

                assertTrue(staleDetachAcknowledged)
                assertEquals(84L, engine.currentDrawable)
                assertFalse(engine.events.contains("drawable:null"))
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun fitCropAndResizeOnlyUpdateNativeVideoGeometry() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine = RecordingDesktopVlcEngine()
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 4L)))
                controller.setFillCrop(true)
                controller.resizeVideoSurface(generation = 4L, width = 1_920, height = 1_080)
                runCurrent()

                assertEquals(GeometryCall(crop = true, width = 1_920, height = 1_080), engine.geometryCalls.last())
                assertEquals(42L, engine.currentDrawable)
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun jellyfinSubripSidecarIsAttachedWithTheVlcRecognizedExtensionAndItsCredential() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine = RecordingDesktopVlcEngine()
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(
                    plan = playbackPlan(),
                    subtitleAsset =
                        SubtitleAsset.JellyfinRemote(
                            url = "https://jellyfin.example/Videos/item/source/Subtitles/3/0/Stream.subrip",
                            mimeType = "application/octet-stream",
                            label = "English",
                            language = "eng",
                        ),
                )
                runCurrent()

                val subtitleUrl =
                    engine.prepareCalls
                        .single()
                        .subtitleUrl
                        .orEmpty()
                assertTrue(subtitleUrl.substringBefore('?').endsWith("Stream.srt"))
                assertFalse(subtitleUrl.contains(".subrip"))
                assertTrue(subtitleUrl.contains("ApiKey=token"))
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun slaveAttachedPlanSubtitleConfirmsOnThePlayingEvent() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine = RecordingDesktopVlcEngine()
            val target =
                SubtitleActivationTarget(
                    requestId = 21L,
                    itemId = "item",
                    identity = SubtitleActivationIdentity.JellyfinTrack(3),
                    kind = LocalSubtitleKind.ExternalText,
                )
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(
                    plan = playbackPlan().copy(subtitleActivationTarget = target),
                    subtitleAsset =
                        SubtitleAsset.JellyfinRemote(
                            url = "https://jellyfin.example/Videos/item/source/Subtitles/3/0/Stream.subrip",
                            mimeType = "application/octet-stream",
                            label = "English",
                            language = "eng",
                        ),
                )
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                runCurrent()

                assertEquals(
                    SubtitleActivationState.Pending(target),
                    controller.playbackState.value.subtitleActivation,
                )

                engine.emit(DesktopVlcEvent.Playing)
                runCurrent()

                assertEquals(
                    SubtitleActivationState.Active(target),
                    controller.playbackState.value.subtitleActivation,
                )
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun stopCancelsTheArmedActivationDeadlineInsteadOfLeavingItToFire() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine =
                RecordingDesktopVlcEngine(
                    audioTrackDescriptions = listOf(DesktopVlcTrack(id = 1, label = "Unmatched")),
                )
            val descriptor =
                PlannedEmbeddedTrack(
                    jellyfinStreamIndex = 2,
                    filteredContainerOrdinal = 1,
                    codec = "aac",
                    normalizedLanguage = "spa",
                    label = "Spanish",
                )
            val target = AudioActivationTarget(requestId = 30L, itemId = "item", streamIndex = 2)
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(
                    playbackPlan().copy(
                        selectedAudioStreamIndex = 2,
                        embeddedAudioTracks =
                            listOf(
                                descriptor.copy(filteredContainerOrdinal = 0, jellyfinStreamIndex = 1),
                                descriptor,
                            ),
                        audioActivationTarget = target,
                    ),
                    null,
                )
                controller.selectEmbeddedAudio(EmbeddedAudioSelection(target, descriptor))
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                runCurrent()
                engine.emit(DesktopVlcEvent.Playing)
                runCurrent()

                controller.stop()
                runCurrent()
                testScheduler.advanceTimeBy(10_000L)
                runCurrent()

                assertEquals(AudioActivationState.None, controller.playbackState.value.audioActivation)
                assertEquals(PlaybackStatus.Idle, controller.playbackState.value.status)
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }

    @Test
    fun aNewerSelectionConfirmsAfterAnExhaustedOneAndIsNotDemotedLater() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine =
                RecordingDesktopVlcEngine(
                    audioTrackDescriptions =
                        listOf(
                            DesktopVlcTrack(id = 1, label = "Track 1"),
                            DesktopVlcTrack(id = 2, label = "Track 2"),
                        ),
                    selectableAudioTrackIds = setOf(2),
                )
            val descriptors =
                listOf(
                    PlannedEmbeddedTrack(
                        jellyfinStreamIndex = 1,
                        filteredContainerOrdinal = 0,
                        codec = "aac",
                        normalizedLanguage = "eng",
                        label = "English",
                    ),
                    PlannedEmbeddedTrack(
                        jellyfinStreamIndex = 2,
                        filteredContainerOrdinal = 1,
                        codec = "aac",
                        normalizedLanguage = "spa",
                        label = "Spanish",
                    ),
                )
            val rejectedTarget = AudioActivationTarget(requestId = 40L, itemId = "item", streamIndex = 1)
            val acceptedTarget = AudioActivationTarget(requestId = 41L, itemId = "item", streamIndex = 2)
            val controller =
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = scope,
                    engine = engine,
                    nativeDispatcher = dispatcher,
                )
            try {
                controller.prepare(
                    playbackPlan().copy(
                        selectedAudioStreamIndex = 1,
                        embeddedAudioTracks = descriptors,
                        audioActivationTarget = rejectedTarget,
                    ),
                    null,
                )
                controller.selectEmbeddedAudio(EmbeddedAudioSelection(rejectedTarget, descriptors[0]))
                controller.play()
                controller.attachVideoSurface(requireNotNull(DesktopVlcSurfaceHandle.create(42L, 1L)))
                runCurrent()
                engine.emit(DesktopVlcEvent.Playing)
                runCurrent()
                testScheduler.advanceTimeBy(2_600L)
                runCurrent()

                assertEquals(
                    AudioActivationState.Unavailable(rejectedTarget),
                    controller.playbackState.value.audioActivation,
                )

                controller.selectEmbeddedAudio(EmbeddedAudioSelection(acceptedTarget, descriptors[1]))
                runCurrent()
                testScheduler.advanceTimeBy(251L)
                runCurrent()

                assertEquals(
                    AudioActivationState.Active(acceptedTarget),
                    controller.playbackState.value.audioActivation,
                )

                // No deadline armed for either target may demote the confirmed one.
                testScheduler.advanceTimeBy(10_000L)
                runCurrent()

                assertEquals(
                    AudioActivationState.Active(acceptedTarget),
                    controller.playbackState.value.audioActivation,
                )
            } finally {
                controller.release()
                runCurrent()
                scope.cancel()
            }
        }
}

private class RecordingDesktopVlcEngine(
    var snapshot: DesktopVlcSnapshot =
        DesktopVlcSnapshot(
            positionMs = 0L,
            durationMs = null,
            seekable = false,
            displayedPictures = null,
            lostPictures = null,
            width = null,
            height = null,
            frameRate = null,
        ),
    private val audioTrackDescriptions: List<DesktopVlcTrack> = emptyList(),
    private val selectableAudioTrackIds: Set<Int> = emptySet(),
    private val subtitleTrackDescriptions: List<DesktopVlcTrack> = emptyList(),
    private val selectableSubtitleTrackIds: Set<Int> = emptySet(),
    private val subtitleReadbackConfirmAfterAttempts: Int = 1,
) : DesktopVlcEngine {
    val events = mutableListOf<String>()
    val prepareCalls = mutableListOf<PrepareCall>()
    val seeks = mutableListOf<Long>()
    val geometryCalls = mutableListOf<GeometryCall>()
    val selectedAudioTrackIds = mutableListOf<Int>()
    val selectedSubtitleTrackIds = mutableListOf<Int?>()
    val muteCalls = mutableListOf<Boolean>()
    var currentDrawable: Long? = null
    var playCount = 0
    var snapshotCount = 0
    private var listener: ((DesktopVlcEvent) -> Unit)? = null

    fun emit(event: DesktopVlcEvent) {
        listener?.invoke(event)
    }

    override fun setEventListener(listener: ((DesktopVlcEvent) -> Unit)?) {
        this.listener = listener
        events += if (listener == null) "listener:null" else "listener:set"
    }

    override fun prepare(
        authorizedMediaUrl: String,
        externalSubtitleResource: String?,
        startPositionMs: Long,
    ) {
        prepareCalls += PrepareCall(authorizedMediaUrl, externalSubtitleResource, startPositionMs)
        events += "prepare"
    }

    override fun setDrawable(surfaceAddress: Long?) {
        currentDrawable = surfaceAddress
        events += "drawable:${surfaceAddress ?: "null"}"
    }

    override fun play(): Boolean {
        playCount += 1
        events += "play"
        return true
    }

    override fun pause() = Unit

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
    }

    override fun stop() {
        events += "stop"
    }

    override fun setVolume(percent: Int) = Unit

    override fun setMuted(muted: Boolean) {
        muteCalls += muted
    }

    override fun setRate(speed: Float) = Unit

    override fun setFillCrop(
        crop: Boolean,
        width: Int,
        height: Int,
    ) {
        geometryCalls += GeometryCall(crop, width, height)
    }

    override fun audioTracks(): List<DesktopVlcTrack> = audioTrackDescriptions

    override fun subtitleTracks(): List<DesktopVlcTrack> = subtitleTrackDescriptions

    override fun selectAudioTrack(id: Int): Boolean {
        selectedAudioTrackIds += id
        return id in selectableAudioTrackIds
    }

    override fun selectSubtitleTrack(id: Int?): Boolean {
        selectedSubtitleTrackIds += id
        val attempts = selectedSubtitleTrackIds.count { selectedId -> selectedId == id }
        return id == null || (id in selectableSubtitleTrackIds && attempts >= subtitleReadbackConfirmAfterAttempts)
    }

    override fun snapshot(): DesktopVlcSnapshot {
        snapshotCount += 1
        return snapshot
    }

    override fun release() {
        events += "release"
    }
}

private data class GeometryCall(
    val crop: Boolean,
    val width: Int,
    val height: Int,
)

private data class PrepareCall(
    val mediaUrl: String,
    val subtitleUrl: String?,
    val startPositionMs: Long,
)

private fun playbackPlan(
    streamUrl: String = "https://jellyfin.example/Videos/item/stream",
    startPositionMs: Long = 0L,
): PlaybackPlan =
    PlaybackPlan(
        itemId = "item",
        mediaSourceId = "source",
        startPositionMs = startPositionMs,
        streamMode = StreamMode.DirectPlay,
        streamUrl = streamUrl,
        progressReportingPolicy = ProgressReportingPolicy(10_000L),
    )

private val session =
    Session(
        serverUrl = "https://jellyfin.example",
        serverId = "server",
        serverName = "Home",
        userId = "user",
        userName = "User",
        accessToken = "token",
        deviceId = "device",
    )
