// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.data.local.DesktopPlayerVolumeStore
import com.jellyscope.core.data.local.SerializedLatestValueWriter
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.AudioActivationTarget
import com.jellyscope.core.domain.playback.EmbeddedAudioSelection
import com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.PlannedEmbeddedTrack
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerVolumeState
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleActivationIdentity
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MpvPlayerControllerHardeningTest {
    @Test
    fun secureTlsIsDefaultAndScopedExceptionOmitsTheCaBundle() {
        val rootDirectory = Files.createTempDirectory("jellyscope-mpv-tls").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val insecureLib = HardeningLibMpv()
        val secureLib = HardeningLibMpv()
        val policy = DesktopMpvNetworkPolicy(rootDirectory)
        val insecure =
            MpvPlayerController(
                testSession,
                scope,
                insecureLib,
                allowInsecureDesktopTls = true,
                desktopMpvNetworkPolicy = policy,
            )
        try {
            assertTrue("tls-verify=no" in insecureLib.optionWrites)
            assertFalse(insecureLib.optionWrites.any { write -> write.startsWith("tls-ca-file=") })
            assertFalse(rootDirectory.resolve("mpv/trust-bundle.pem").exists())

            val secure =
                MpvPlayerController(
                    testSession,
                    scope,
                    secureLib,
                    desktopMpvNetworkPolicy = policy,
                )
            try {
                assertTrue("tls-verify=yes" in secureLib.optionWrites)
                assertTrue(secureLib.optionWrites.any { write -> write.startsWith("tls-ca-file=") })
            } finally {
                secure.release()
            }
        } finally {
            insecure.release()
            scope.cancel()
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun prepareInvalidatesInFlightPollBeforeLoadedSelectionOrStateCommit() {
        val lib = HardeningLibMpv(tracks = listOf(videoTrack, audioTrack))
        withController(lib) { controller ->
            controller.prepare(playbackPlan(itemId = "first"))
            val firstEntry = lib.currentPlaylistEntryId
            lib.enqueueStart(firstEntry)
            lib.enqueueFileLoaded()
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Paused }

            val pollThreadName = "prepare-interleaving-poll"
            val pollInterlock = lib.blockNextPropertyRead("demuxer-cache-time", pollThreadName)
            val pollThread = Thread({ controller.pause() }, pollThreadName).also(Thread::start)
            pollInterlock.awaitEntered()

            val target = AudioActivationTarget(requestId = 11L, itemId = "second", streamIndex = 7)
            val selection =
                EmbeddedAudioSelection(
                    target = target,
                    descriptor =
                        PlannedEmbeddedTrack(
                            jellyfinStreamIndex = target.streamIndex,
                            filteredContainerOrdinal = 0,
                            codec = "aac",
                            normalizedLanguage = "eng",
                            label = "English",
                        ),
                )
            controller.prepare(playbackPlan(itemId = "second", audioTarget = target))
            controller.selectEmbeddedAudio(selection)
            val audioWritesBeforeStaleCommit = lib.propertyWrites.count { write -> write.startsWith("property:aid:") }
            assertEquals(PlaybackStatus.Loading, controller.playbackState.value.status)

            pollInterlock.release()
            pollThread.join(TEST_INTERLEAVING_TIMEOUT_MS)

            assertFalse(pollThread.isAlive)
            assertEquals(PlaybackStatus.Loading, controller.playbackState.value.status)
            assertEquals(
                audioWritesBeforeStaleCommit,
                lib.propertyWrites.count { write -> write.startsWith("property:aid:") },
            )
        }
    }

    @Test
    fun stopInvalidatesInFlightPollBeforeLoadedOrStateCommit() {
        val lib = HardeningLibMpv(tracks = listOf(videoTrack))
        withController(lib) { controller ->
            controller.prepare(playbackPlan())
            val entry = lib.currentPlaylistEntryId
            lib.enqueueStart(entry)
            lib.enqueueFileLoaded()
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Paused }

            val pollThreadName = "stop-interleaving-poll"
            val pollInterlock = lib.blockNextPropertyRead("demuxer-cache-time", pollThreadName)
            val pollThread = Thread({ controller.pause() }, pollThreadName).also(Thread::start)
            pollInterlock.awaitEntered()

            controller.stop()
            assertEquals(PlaybackStatus.Idle, controller.playbackState.value.status)

            pollInterlock.release()
            pollThread.join(TEST_INTERLEAVING_TIMEOUT_MS)

            assertFalse(pollThread.isAlive)
            assertEquals(PlaybackStatus.Idle, controller.playbackState.value.status)
        }
    }

    @Test
    fun releaseAndEngineTeardownInvalidateInFlightPollBeforeStateCommit() {
        val lib = HardeningLibMpv(tracks = listOf(videoTrack))
        withController(lib) { controller ->
            controller.prepare(playbackPlan())
            val entry = lib.currentPlaylistEntryId
            lib.enqueueStart(entry)
            lib.enqueueFileLoaded()
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Paused }

            val pollThreadName = "release-interleaving-poll"
            lib.controlledPollThreadName = pollThreadName
            lib.controlledPollPositionSeconds = 30.0
            val pollInterlock = lib.blockNextPropertyRead("demuxer-cache-time", pollThreadName)
            val pollThread = Thread({ controller.pause() }, pollThreadName).also(Thread::start)
            pollInterlock.awaitEntered()
            assertEquals(0L, controller.playbackState.value.positionMs)

            controller.release()
            pollInterlock.release()
            pollThread.join(TEST_INTERLEAVING_TIMEOUT_MS)
            waitUntil { lib.terminateCount.get() > 0 }

            assertFalse(pollThread.isAlive)
            assertEquals(0L, controller.playbackState.value.positionMs)
        }
    }

    @Test
    fun unresolvedEntryRequiresExplicitFalseAcrossTrueNullTrue() {
        val lib = HardeningLibMpv(tracks = listOf(videoTrack))
        lib.playlistEntryAvailable.set(false)
        lib.eofReached.set(true)
        withController(lib) { controller ->
            controller.prepare(playbackPlan())
            lib.enqueueFileLoaded()

            waitForAdditionalReads(lib.eofReadCount, 1)
            assertEquals(PlaybackStatus.Loading, controller.playbackState.value.status)

            lib.eofReached.set(null)
            waitForAdditionalReads(lib.eofReadCount, 1)
            lib.eofReached.set(true)
            waitForAdditionalReads(lib.eofReadCount, 1)

            assertEquals(PlaybackStatus.Loading, controller.playbackState.value.status)

            lib.eofReached.set(false)
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Paused }
            lib.eofReached.set(true)
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Completed }
        }
    }

    @Test
    fun reusedContextIgnoresLatchedEofAndStaleEventsUntilReplacementLoads() {
        val lib = HardeningLibMpv(tracks = listOf(videoTrack))
        withController(lib) { controller ->
            controller.prepare(playbackPlan(itemId = "first"))
            val firstEntry = lib.currentPlaylistEntryId
            lib.enqueueStart(firstEntry)
            lib.enqueueFileLoaded()
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Paused }

            lib.eofReached.set(true)
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Completed }

            controller.prepare(playbackPlan(itemId = "second"))
            val secondEntry = lib.currentPlaylistEntryId
            Thread.sleep(STATE_POLL_INTERVAL_MS + 100L)
            assertEquals(PlaybackStatus.Loading, controller.playbackState.value.status)

            val deliveredBeforeStaleEvents = lib.lifecycleEventsDelivered.get()
            lib.enqueueStart(firstEntry)
            lib.enqueueFileLoaded()
            waitUntil { lib.lifecycleEventsDelivered.get() >= deliveredBeforeStaleEvents + 2 }
            Thread.sleep(STATE_POLL_INTERVAL_MS + 100L)
            assertEquals(PlaybackStatus.Loading, controller.playbackState.value.status)

            lib.eofReached.set(false)
            lib.enqueueStart(secondEntry)
            lib.enqueueFileLoaded()
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Paused }

            lib.eofReached.set(true)
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Completed }
        }
    }

    @Test
    fun rapidPreparesArmCompletionForNewestPlaylistEntryOnly() {
        val lib = HardeningLibMpv(tracks = listOf(videoTrack))
        withController(lib) { controller ->
            controller.prepare(playbackPlan(itemId = "superseded"))
            val supersededEntry = lib.currentPlaylistEntryId
            controller.prepare(playbackPlan(itemId = "current"))
            val currentEntry = lib.currentPlaylistEntryId

            lib.enqueueStart(supersededEntry)
            lib.enqueueFileLoaded()
            waitUntil { lib.lifecycleEventsDelivered.get() >= 2 }
            Thread.sleep(STATE_POLL_INTERVAL_MS + 100L)
            assertEquals(PlaybackStatus.Loading, controller.playbackState.value.status)

            lib.enqueueStart(currentEntry)
            lib.enqueueFileLoaded()
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Paused }
            lib.eofReached.set(true)
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Completed }
        }
    }

    @Test
    fun stopAndReleaseInvalidateCompletionReadiness() {
        val lib = HardeningLibMpv(tracks = listOf(videoTrack))
        withController(lib) { controller ->
            controller.prepare(playbackPlan())
            val entry = lib.currentPlaylistEntryId
            lib.enqueueStart(entry)
            lib.enqueueFileLoaded()
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Paused }

            controller.stop()
            lib.eofReached.set(true)
            lib.enqueueFileLoaded()
            Thread.sleep(STATE_POLL_INTERVAL_MS + 100L)
            assertNotEquals(PlaybackStatus.Completed, controller.playbackState.value.status)

            controller.release()
            lib.enqueueStart(entry)
            lib.enqueueFileLoaded()
            Thread.sleep(STATE_POLL_INTERVAL_MS + 100L)
            assertNotEquals(PlaybackStatus.Completed, controller.playbackState.value.status)
        }
    }

    @Test
    fun endFileErrorWithoutSubtitleAttachmentFailsWithNativeClassification() {
        val lib = HardeningLibMpv(tracks = listOf(videoTrack))
        withController(lib) { controller ->
            controller.prepare(playbackPlan())
            val entryId = lib.currentPlaylistEntryId
            lib.enqueueStart(entryId)
            lib.enqueueFileLoaded()
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Paused }

            lib.enqueueEnd(entryId, LibMpv.END_FILE_REASON_ERROR, LibMpv.MPV_ERROR_LOADING_FAILED)
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Failed }

            assertEquals(PlaybackError.Network, controller.playbackState.value.error)
        }
    }

    @Test
    fun endFileNativeUnsupportedCodesReachUnsupportedMedia() {
        listOf(
            LibMpv.MPV_ERROR_NOTHING_TO_PLAY,
            LibMpv.MPV_ERROR_UNKNOWN_FORMAT,
            LibMpv.MPV_ERROR_UNSUPPORTED,
        ).forEach { nativeCode ->
            val lib = HardeningLibMpv(tracks = listOf(videoTrack))
            withController(lib) { controller ->
                controller.prepare(playbackPlan())
                val entryId = lib.currentPlaylistEntryId
                lib.enqueueStart(entryId)
                lib.enqueueFileLoaded()
                waitUntil { controller.playbackState.value.status == PlaybackStatus.Paused }

                lib.enqueueEnd(entryId, LibMpv.END_FILE_REASON_ERROR, nativeCode)
                waitUntil { controller.playbackState.value.status == PlaybackStatus.Failed }

                assertEquals(PlaybackError.UnsupportedMedia, controller.playbackState.value.error)
            }
        }
    }

    @Test
    fun staleStopAndRedirectEndFilesDoNotFailTheCurrentPlayback() {
        val lib = HardeningLibMpv(tracks = listOf(videoTrack))
        withController(lib) { controller ->
            controller.prepare(playbackPlan(itemId = "first"))
            val firstEntry = lib.currentPlaylistEntryId
            controller.prepare(playbackPlan(itemId = "second"))
            val secondEntry = lib.currentPlaylistEntryId
            lib.enqueueStart(secondEntry)
            lib.enqueueFileLoaded()
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Paused }

            lib.enqueueEnd(firstEntry, LibMpv.END_FILE_REASON_ERROR, LibMpv.MPV_ERROR_LOADING_FAILED)
            waitUntil { lib.lifecycleEventsDelivered.get() >= 3 }
            assertNotEquals(PlaybackStatus.Failed, controller.playbackState.value.status)

            controller.prepare(playbackPlan(itemId = "stopped"))
            val stoppedEntry = lib.currentPlaylistEntryId
            lib.enqueueStart(stoppedEntry)
            lib.enqueueEnd(stoppedEntry, LibMpv.END_FILE_REASON_STOP, LibMpv.MPV_ERROR_LOADING_FAILED)
            waitUntil { lib.lifecycleEventsDelivered.get() >= 5 }
            assertNotEquals(PlaybackStatus.Failed, controller.playbackState.value.status)

            controller.prepare(playbackPlan(itemId = "redirected"))
            val redirectedEntry = lib.currentPlaylistEntryId
            lib.enqueueStart(redirectedEntry)
            lib.enqueueEnd(redirectedEntry, LibMpv.END_FILE_REASON_REDIRECT, LibMpv.MPV_ERROR_LOADING_FAILED)
            waitUntil { lib.lifecycleEventsDelivered.get() >= 7 }
            assertNotEquals(PlaybackStatus.Failed, controller.playbackState.value.status)
        }
    }

    @Test
    fun coreIdleIsBufferingOnlyForActiveUnpausedPlayback() {
        val lib = HardeningLibMpv(tracks = listOf(videoTrack), coreIdle = true)
        withController(lib) { controller ->
            controller.prepare(playbackPlan())
            val entryId = lib.currentPlaylistEntryId
            lib.paused.set(false)
            lib.enqueueStart(entryId)
            lib.enqueueFileLoaded()
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Buffering }

            lib.coreIdle.set(false)
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Playing }

            lib.paused.set(true)
            lib.coreIdle.set(true)
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Paused }
        }
    }

    @Test
    fun trackAutoSelectionIsDisabledAndFileLoadedSelectsFirstTracks() {
        val lib = HardeningLibMpv(tracks = listOf(videoTrack, audioTrack, alternateAudioTrack))
        withController(lib) { controller ->
            controller.prepare(playbackPlan())
            val entryId = lib.currentPlaylistEntryId
            lib.enqueueStart(entryId)
            lib.enqueueFileLoaded()
            waitUntil { lib.propertyWrites.contains("property:aid:${audioTrack.id}") }

            assertTrue(lib.optionWrites.contains("track-auto-selection=no"))
            assertTrue(lib.propertyWrites.contains("property:vid:${videoTrack.id}"))
            assertTrue(lib.propertyWrites.contains("property:aid:${audioTrack.id}"))
            assertTrue(lib.propertyWrites.contains("property:sid:no"))
        }
    }

    @Test
    fun fileLoadedPrefersResolvedPendingAudioAndSkipsVideoForAudioOnly() {
        val target = AudioActivationTarget(requestId = 2L, itemId = "audio", streamIndex = 7)
        val descriptor =
            PlannedEmbeddedTrack(
                jellyfinStreamIndex = target.streamIndex,
                filteredContainerOrdinal = 0,
                codec = "aac",
                normalizedLanguage = "eng",
                label = "English",
            )
        val selection = EmbeddedAudioSelection(target, descriptor)
        val pendingLib = HardeningLibMpv(tracks = listOf(videoTrack, alternateAudioTrack, audioTrack))
        withController(pendingLib) { controller ->
            controller.prepare(playbackPlan(itemId = "audio", audioTarget = target))
            controller.selectEmbeddedAudio(selection)
            val entryId = pendingLib.currentPlaylistEntryId
            pendingLib.enqueueStart(entryId)
            pendingLib.enqueueFileLoaded()
            waitUntil { pendingLib.propertyWrites.contains("property:aid:${audioTrack.id}") }

            assertFalse(pendingLib.propertyWrites.contains("property:aid:${alternateAudioTrack.id}"))
            assertTrue(pendingLib.propertyWrites.contains("property:vid:${videoTrack.id}"))
        }

        val audioOnlyLib = HardeningLibMpv(tracks = listOf(audioTrack))
        withController(audioOnlyLib) { controller ->
            controller.prepare(playbackPlan(itemId = "audio-only"))
            val entryId = audioOnlyLib.currentPlaylistEntryId
            audioOnlyLib.enqueueStart(entryId)
            audioOnlyLib.enqueueFileLoaded()
            waitUntil { audioOnlyLib.propertyWrites.contains("property:aid:${audioTrack.id}") }

            assertTrue(audioOnlyLib.propertyWrites.none { write -> write.startsWith("property:vid:") })
        }
    }

    @Test
    fun retryRetainsAudioAndExactSubtitleSelection() {
        val subtitleTarget = embeddedSubtitleTarget(requestId = 11L)
        val subtitleSelection = embeddedSubtitleSelection(subtitleTarget)
        val audioSelection = embeddedAudioSelection()
        val lib = HardeningLibMpv(tracks = listOf(videoTrack, audioTrack, subtitleTrack))
        withController(lib) { controller ->
            prepareLoaded(controller, lib, playbackPlan(subtitleTarget = subtitleTarget))
            controller.selectEmbeddedAudio(audioSelection)
            controller.selectEmbeddedSubtitle(subtitleSelection)
            lib.propertyWrites.clear()

            controller.retry()
            finishLoad(controller, lib)

            assertTrue(lib.propertyWrites.contains("property:aid:${audioTrack.id}"))
            assertTrue(lib.propertyWrites.contains("property:sid:${subtitleTrack.id}"))
            assertEquals(
                SubtitleActivationState.Pending(subtitleTarget),
                controller.playbackState.value.subtitleActivation,
            )
        }
    }

    @Test
    fun retryRejectsStaleSubtitleSelectionAndRestoresPlanTarget() {
        val plannedTarget = embeddedSubtitleTarget(requestId = 21L)
        val staleSelection = embeddedSubtitleSelection(embeddedSubtitleTarget(requestId = 22L))
        val lib = HardeningLibMpv(tracks = listOf(videoTrack, subtitleTrack))
        withController(lib) { controller ->
            prepareLoaded(controller, lib, playbackPlan(subtitleTarget = plannedTarget))
            controller.selectEmbeddedSubtitle(staleSelection)
            lib.propertyWrites.clear()

            controller.retry()
            finishLoad(controller, lib)

            assertFalse(lib.propertyWrites.contains("property:sid:${subtitleTrack.id}"))
            assertEquals(
                SubtitleActivationState.Pending(plannedTarget),
                controller.playbackState.value.subtitleActivation,
            )
        }
    }

    @Test
    fun retryTreatsAmbiguousNullAsUnspecifiedAndRestoresPlanTarget() {
        val plannedTarget = embeddedSubtitleTarget(requestId = 31L)
        val lib = HardeningLibMpv(tracks = listOf(videoTrack, subtitleTrack))
        withController(lib) { controller ->
            prepareLoaded(controller, lib, playbackPlan(subtitleTarget = plannedTarget))
            lib.propertyWrites.clear()

            controller.retry()
            finishLoad(controller, lib)

            assertFalse(lib.propertyWrites.contains("property:sid:${subtitleTrack.id}"))
            assertEquals(
                SubtitleActivationState.Pending(plannedTarget),
                controller.playbackState.value.subtitleActivation,
            )
        }
    }

    @Test
    fun rejectedBestEffortToneMappingDoesNotAbortInitialization() {
        val lib = HardeningLibMpv(rejectedOption = "tone-mapping")
        withController(lib) { controller ->
            assertNotEquals(PlaybackStatus.Failed, controller.playbackState.value.status)
            assertEquals(1, lib.initializeCount.get())
            assertTrue(lib.optionWrites.contains("tone-mapping=bt.2390"))
            assertTrue(lib.optionWrites.contains("target-peak=auto"))
        }
    }

    @Test
    fun volumeWritesClampMuteAndPollDoesNotPublishReadback() {
        val lib = HardeningLibMpv(tracks = listOf(videoTrack))
        val store = TestVolumeStore(PlayerVolumeState(volumePercent = 37, muted = false))
        withController(lib, volumeStore = store) { controller ->
            controller.setVolume(150)
            controller.setMuted(true)
            assertEquals(PlayerVolumeState(volumePercent = 100, muted = true), controller.volumeState.value)

            assertTrue(lib.propertyWrites.contains("property:volume:100.0"))
            assertTrue(lib.propertyWrites.contains("property:mute:yes"))

            controller.setVolume(-10)
            assertEquals(PlayerVolumeState(volumePercent = 0, muted = true), controller.volumeState.value)
            assertTrue(lib.propertyWrites.contains("property:volume:0.0"))

            store.submitted.clear()
            lib.volumePercent = 37.4
            lib.muted = false
            controller.pause()

            assertEquals(PlayerVolumeState(volumePercent = 0, muted = true), controller.volumeState.value)
            assertTrue(store.submitted.isEmpty())
        }
    }

    @Test
    fun volumeChangesSubmitTheSettledValueDirectlyToTheStore() {
        val lib = HardeningLibMpv(tracks = listOf(videoTrack))
        val store = TestVolumeStore(PlayerVolumeState())
        withController(lib, volumeStore = store) { controller ->
            controller.setVolume(20)
            controller.setVolume(27)

            assertEquals(27, store.submitted.last().volumePercent)
            assertEquals(2, store.submitted.size)
        }
    }

    @Test
    fun dragShorterThanTheCadenceStillSubmitsItsSettledValue() {
        val lib = HardeningLibMpv(tracks = listOf(videoTrack))
        val store = TestVolumeStore(PlayerVolumeState())
        withController(lib, volumeStore = store) { controller ->
            controller.setVolume(31)

            assertEquals(1, store.submitted.size)
            assertEquals(31, store.submitted.first().volumePercent)
            assertFalse(store.submitted.first().muted)
            assertEquals(PlayerVolumeState(volumePercent = 31, muted = false), store.submitted.first())
        }
    }

    @Test
    fun setMutedSubmitsDirectlyToTheStoreWhileDiskPersistenceRemainsStoreOwned() {
        val lib = HardeningLibMpv(tracks = listOf(videoTrack))
        val store = TestVolumeStore(PlayerVolumeState())
        withController(lib, volumeStore = store) { controller ->
            controller.setMuted(true)

            assertEquals(PlayerVolumeState(volumePercent = 100, muted = true), store.submitted.single())
        }
    }

    @Test
    fun staleVolumeReadbackCannotReplaceTheUserValueBeforeRelease() {
        val lib = HardeningLibMpv(tracks = listOf(videoTrack))
        val store = TestVolumeStore(PlayerVolumeState(volumePercent = 40, muted = false))
        withController(lib, volumeStore = store) { controller ->
            controller.setVolume(50)
            lib.volumePercent = 40.0
            controller.pause()
            controller.release()

            assertEquals(PlayerVolumeState(volumePercent = 50, muted = false), store.submitted.last())
            assertEquals(1, store.submitted.size)
        }
    }

    @Test
    fun staleVolumeThenMutePersistsBothUserValues() {
        val lib = HardeningLibMpv(tracks = listOf(videoTrack))
        val store = TestVolumeStore(PlayerVolumeState(volumePercent = 40, muted = false))
        withController(lib, volumeStore = store) { controller ->
            controller.setVolume(50)
            lib.volumePercent = 40.0
            controller.pause()
            controller.setMuted(true)

            assertEquals(PlayerVolumeState(volumePercent = 50, muted = true), store.submitted.last())
        }
    }

    @Test
    fun staleMuteThenVolumePersistsBothUserValues() {
        val lib = HardeningLibMpv(tracks = listOf(videoTrack))
        val store = TestVolumeStore(PlayerVolumeState(volumePercent = 40, muted = false))
        withController(lib, volumeStore = store) { controller ->
            controller.setMuted(true)
            lib.muted = false
            controller.pause()
            controller.setVolume(50)

            assertEquals(PlayerVolumeState(volumePercent = 50, muted = true), store.submitted.last())
        }
    }

    @Test
    fun restoredVolumeStateIsAppliedBeforePlayback() {
        val lib = HardeningLibMpv(tracks = listOf(videoTrack))
        val store = TestVolumeStore(PlayerVolumeState(volumePercent = 42, muted = true))
        withController(lib, volumeStore = store) { controller ->
            assertEquals(PlayerVolumeState(volumePercent = 42, muted = true), controller.volumeState.value)
            assertTrue(lib.propertyWrites.contains("property:volume:42.0"))
            assertTrue(lib.propertyWrites.contains("property:mute:yes"))
        }
    }

    @Test
    fun twoControllerReleasesKeepNewestVolumeSnapshot() =
        runTest {
            val writerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val firstWriteStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
            val allowFirstWriteToFinish = kotlinx.coroutines.CompletableDeferred<Unit>()
            val writes = CopyOnWriteArrayList<PlayerVolumeState>()
            val store =
                SerializedTestVolumeStore(
                    scope = writerScope,
                    monotonicTimeNanos = { testScheduler.currentTime * 1_000_000L },
                    firstWriteStarted = firstWriteStarted,
                    allowFirstWriteToFinish = allowFirstWriteToFinish,
                    writes = writes,
                )
            val first = MpvPlayerController(testSession, controllerScope, HardeningLibMpv(), volumeStore = store)
            val second = MpvPlayerController(testSession, controllerScope, HardeningLibMpv(), volumeStore = store)
            try {
                first.setVolume(20)
                runCurrent()
                advanceTimeBy(500L)
                runCurrent()
                assertTrue(firstWriteStarted.isCompleted)

                first.release()
                second.setVolume(80)
                second.release()
                allowFirstWriteToFinish.complete(Unit)
                advanceTimeBy(500L)
                runCurrent()

                assertEquals(listOf(20, 80), writes.map { state -> state.volumePercent })
            } finally {
                first.release()
                second.release()
                controllerScope.cancel()
                writerScope.cancel()
            }
        }
}

private class HardeningLibMpv(
    private val tracks: List<FakeTrack> = emptyList(),
    private val rejectedOption: String? = null,
    coreIdle: Boolean = false,
) : LibMpv {
    val optionWrites = CopyOnWriteArrayList<String>()
    val propertyWrites = CopyOnWriteArrayList<String>()
    val lifecycleEventsDelivered = AtomicInteger(0)
    val initializeCount = AtomicInteger(0)
    val terminateCount = AtomicInteger(0)
    val paused = AtomicBoolean(true)
    val coreIdle = AtomicBoolean(coreIdle)
    val eofReached = AtomicReference<Boolean?>(false)
    val eofReadCount = AtomicInteger(0)
    val playlistEntryAvailable = AtomicBoolean(true)
    var controlledPollThreadName: String? = null
    var controlledPollPositionSeconds: Double? = null
    var currentPlaylistEntryId = 0L
        private set

    private val context = Pointer(1L)
    private val renderContext = Pointer(2L)
    private val nativeEvents = ArrayDeque<Memory>()
    private val retainedPayloads = mutableListOf<Memory>()
    private val returnedStrings = CopyOnWriteArrayList<Memory>()
    private val tracksAvailable = AtomicBoolean(false)
    private val propertyReadInterlock = AtomicReference<PropertyReadInterlock?>(null)
    private var selectedAudioId = 0L
    var volumePercent = 100.0
    var muted = false

    override fun mpv_create(): Pointer = context

    override fun mpv_initialize(ctx: Pointer): Int {
        initializeCount.incrementAndGet()
        return 0
    }

    override fun mpv_terminate_destroy(ctx: Pointer) {
        terminateCount.incrementAndGet()
    }

    override fun mpv_error_string(error: Int): Pointer? {
        val value =
            when (error) {
                LibMpv.MPV_ERROR_LOADING_FAILED -> "loading failed"
                LibMpv.MPV_ERROR_NOTHING_TO_PLAY -> "nothing to play"
                LibMpv.MPV_ERROR_UNKNOWN_FORMAT -> "unknown format"
                LibMpv.MPV_ERROR_UNSUPPORTED -> "unsupported"
                else -> return null
            }
        return Memory((value.encodeToByteArray().size + 1).toLong()).also { memory ->
            memory.setString(0L, value)
            returnedStrings += memory
        }
    }

    override fun mpv_set_option_string(
        ctx: Pointer,
        name: String,
        data: String,
    ): Int {
        optionWrites += "$name=$data"
        return if (name == rejectedOption) LibMpv.MPV_ERROR_OPTION_NOT_FOUND else 0
    }

    override fun mpv_set_property_string(
        ctx: Pointer,
        name: String,
        data: String,
    ): Int {
        propertyWrites += "property:$name:$data"
        when (name) {
            "pause" -> paused.set(data == "yes")
            "aid" -> selectedAudioId = data.toLong()
            "volume" -> volumePercent = data.toDouble()
            "mute" -> muted = data == "yes"
        }
        return 0
    }

    override fun mpv_get_property(
        ctx: Pointer,
        name: String,
        format: Int,
        data: Pointer,
    ): Int {
        propertyReadInterlock.get()?.let { interlock ->
            if (
                interlock.matches(name, Thread.currentThread().name) &&
                propertyReadInterlock.compareAndSet(interlock, null)
            ) {
                interlock.block()
            }
        }
        when (name) {
            "time-pos" -> {
                val controlledPosition =
                    controlledPollPositionSeconds
                        ?.takeIf { Thread.currentThread().name == controlledPollThreadName }
                data.setDouble(0L, controlledPosition ?: 0.0)
            }
            "duration" -> data.setDouble(0L, 60.0)
            "demuxer-cache-time" -> data.setDouble(0L, 5.0)
            "aid" -> data.setLong(0L, selectedAudioId)
            "volume" -> data.setDouble(0L, volumePercent)
            "mute" -> data.setInt(0L, if (muted) 1 else 0)
            "playlist-pos" -> data.setLong(0L, 0L)
            "playlist/0/id" -> {
                if (!playlistEntryAvailable.get()) return -1
                data.setLong(0L, currentPlaylistEntryId)
            }
            "track-list/count" -> data.setLong(0L, if (tracksAvailable.get()) tracks.size.toLong() else 0L)
            "pause" -> data.setInt(0L, if (paused.get()) 1 else 0)
            "paused-for-cache",
            "seeking",
            "idle-active",
            -> data.setInt(0L, 0)
            "eof-reached" -> {
                eofReadCount.incrementAndGet()
                val eof = eofReached.get() ?: return -1
                data.setInt(0L, if (eof) 1 else 0)
            }
            "core-idle" -> data.setInt(0L, if (coreIdle.get()) 1 else 0)
            else -> {
                val trackProperty = TRACK_PROPERTY.matchEntire(name) ?: return -1
                val track = tracks.getOrNull(trackProperty.groupValues[1].toInt()) ?: return -1
                when (trackProperty.groupValues[2]) {
                    "id" -> {
                        data.setLong(0L, track.id)
                    }
                    "ff-index" -> data.setLong(0L, (track.ffIndex ?: -1).toLong())
                    "external" -> {
                        data.setInt(0L, if (track.external) 1 else 0)
                    }
                    "selected" -> data.setInt(0L, 0)
                    else -> return -1
                }
            }
        }
        return 0
    }

    fun blockNextPropertyRead(
        propertyName: String,
        threadName: String,
    ): PropertyReadInterlock =
        PropertyReadInterlock(propertyName, threadName).also { interlock ->
            check(propertyReadInterlock.compareAndSet(null, interlock)) { "A property read is already blocked" }
        }

    override fun mpv_get_property_string(
        ctx: Pointer,
        name: String,
    ): Pointer? {
        val trackProperty = TRACK_PROPERTY.matchEntire(name) ?: return null
        val track = tracks.getOrNull(trackProperty.groupValues[1].toInt()) ?: return null
        val value =
            when (trackProperty.groupValues[2]) {
                "type" -> track.type
                "codec" -> track.codec
                "lang" -> track.language
                "title" -> track.title
                else -> return null
            }
        return Memory((value.encodeToByteArray().size + 1).toLong()).also { memory ->
            memory.setString(0L, value)
            returnedStrings += memory
        }
    }

    override fun mpv_free(data: Pointer) = Unit

    override fun mpv_command(
        ctx: Pointer,
        args: Array<String?>,
    ): Int {
        if (args.firstOrNull() == "loadfile") currentPlaylistEntryId += 1L
        return 0
    }

    override fun mpv_wait_event(
        ctx: Pointer,
        timeout: Double,
    ): Pointer? =
        synchronized(nativeEvents) {
            if (nativeEvents.isEmpty()) {
                null
            } else {
                nativeEvents.removeFirst().also { lifecycleEventsDelivered.incrementAndGet() }
            }
        }

    fun enqueueStart(playlistEntryId: Long) {
        val payload = Memory(64L)
        MpvEventStartFile(payload).apply {
            this.playlistEntryId = playlistEntryId
            write()
        }
        enqueueEvent(LibMpv.EVENT_START_FILE, payload)
    }

    fun enqueueFileLoaded() {
        tracksAvailable.set(true)
        enqueueEvent(LibMpv.EVENT_FILE_LOADED, null)
    }

    fun enqueueEnd(
        playlistEntryId: Long,
        reason: Int,
        error: Int,
    ) {
        val payload = Memory(64L)
        MpvEventEndFile(payload).apply {
            this.reason = reason
            this.error = error
            this.playlistEntryId = playlistEntryId
            write()
        }
        enqueueEvent(LibMpv.EVENT_END_FILE, payload)
    }

    private fun enqueueEvent(
        eventId: Int,
        payload: Memory?,
    ) {
        val event = Memory(64L)
        MpvEvent(event).apply {
            this.eventId = eventId
            data = payload
            write()
        }
        synchronized(nativeEvents) {
            payload?.let(retainedPayloads::add)
            nativeEvents.addLast(event)
        }
    }

    override fun mpv_render_context_create(
        res: PointerByReference,
        mpv: Pointer,
        params: Array<MpvRenderParam>,
    ): Int {
        res.value = renderContext
        return 0
    }

    override fun mpv_render_context_render(
        ctx: Pointer,
        params: Array<MpvRenderParam>,
    ): Int = 0

    override fun mpv_render_context_set_update_callback(
        ctx: Pointer,
        callback: LibMpv.MpvRenderUpdateFn?,
        callbackCtx: Pointer?,
    ) = Unit

    override fun mpv_render_context_update(ctx: Pointer): Long = 0L

    override fun mpv_render_context_free(ctx: Pointer) = Unit

    private companion object {
        val TRACK_PROPERTY = Regex("track-list/(\\d+)/(id|ff-index|external|selected|type|codec|lang|title)")
    }
}

private data class FakeTrack(
    val id: Long,
    val type: String,
    val ffIndex: Int? = null,
    val external: Boolean = false,
    val codec: String = "aac",
    val language: String = "eng",
    val title: String = "English",
)

private class PropertyReadInterlock(
    private val propertyName: String,
    private val threadName: String,
) {
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)

    fun matches(
        propertyName: String,
        threadName: String,
    ): Boolean = this.propertyName == propertyName && this.threadName == threadName

    fun block() {
        entered.countDown()
        check(released.await(TEST_INTERLEAVING_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "Timed out waiting to release blocked mpv property read"
        }
    }

    fun awaitEntered() {
        check(entered.await(TEST_INTERLEAVING_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "Timed out waiting for blocked mpv property read"
        }
    }

    fun release() {
        released.countDown()
    }
}

private val videoTrack = FakeTrack(id = 10L, type = MPV_TRACK_TYPE_VIDEO, codec = "h264", title = "Video")
private val audioTrack = FakeTrack(id = 20L, type = MPV_TRACK_TYPE_AUDIO, ffIndex = 7)
private val alternateAudioTrack = FakeTrack(id = 30L, type = MPV_TRACK_TYPE_AUDIO, ffIndex = 2, title = "Alternate")
private val subtitleTrack = FakeTrack(id = 40L, type = MPV_TRACK_TYPE_SUBTITLE, ffIndex = 9, codec = "srt")

private fun withController(
    lib: HardeningLibMpv,
    volumeStore: DesktopPlayerVolumeStore? = null,
    block: (MpvPlayerController) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = MpvPlayerController(testSession, scope, lib, volumeStore = volumeStore)
    try {
        block(controller)
    } finally {
        controller.release()
        scope.cancel()
    }
}

private class TestVolumeStore(
    override val restoredState: PlayerVolumeState,
) : DesktopPlayerVolumeStore {
    val submitted = CopyOnWriteArrayList<PlayerVolumeState>()

    override fun submit(state: PlayerVolumeState) {
        submitted += state
    }
}

private class SerializedTestVolumeStore(
    scope: CoroutineScope,
    monotonicTimeNanos: () -> Long,
    private val firstWriteStarted: kotlinx.coroutines.CompletableDeferred<Unit>,
    private val allowFirstWriteToFinish: kotlinx.coroutines.CompletableDeferred<Unit>,
    private val writes: CopyOnWriteArrayList<PlayerVolumeState>,
) : DesktopPlayerVolumeStore {
    override val restoredState: PlayerVolumeState = PlayerVolumeState()
    private val writer: SerializedLatestValueWriter<PlayerVolumeState> =
        SerializedLatestValueWriter(
            scope = scope,
            monotonicTimeNanos = monotonicTimeNanos,
            write = { state ->
                if (state.volumePercent == 20) {
                    firstWriteStarted.complete(Unit)
                    allowFirstWriteToFinish.await()
                }
                writes += state
            },
        )

    override fun submit(state: PlayerVolumeState) {
        writer.submit(state)
    }
}

private fun playbackPlan(
    itemId: String = "item-1",
    audioTarget: AudioActivationTarget? = null,
    subtitleTarget: SubtitleActivationTarget? = null,
): PlaybackPlan =
    PlaybackPlan(
        itemId = itemId,
        mediaSourceId = "source-$itemId",
        startPositionMs = 0L,
        streamMode = StreamMode.DirectPlay,
        streamUrl = "https://jellyfin.example/Videos/$itemId/stream",
        progressReportingPolicy = ProgressReportingPolicy(10_000L),
        audioActivationTarget = audioTarget,
        subtitleActivationTarget = subtitleTarget,
    )

private fun embeddedAudioSelection(): EmbeddedAudioSelection =
    EmbeddedAudioSelection(
        target = AudioActivationTarget(requestId = 10L, itemId = "item-1", streamIndex = 7),
        descriptor = PlannedEmbeddedTrack(7, 0, "aac", "eng", "English"),
    )

private fun embeddedSubtitleTarget(requestId: Long): SubtitleActivationTarget =
    SubtitleActivationTarget(
        requestId = requestId,
        itemId = "item-1",
        identity = SubtitleActivationIdentity.JellyfinTrack(streamIndex = 9),
        kind = LocalSubtitleKind.EmbeddedText,
    )

private fun embeddedSubtitleSelection(target: SubtitleActivationTarget): EmbeddedSubtitleSelection =
    EmbeddedSubtitleSelection(
        target = target,
        descriptor = PlannedEmbeddedTrack(9, 0, "srt", "eng", "English"),
    )

private fun prepareLoaded(
    controller: MpvPlayerController,
    lib: HardeningLibMpv,
    plan: PlaybackPlan,
) {
    controller.prepare(plan)
    finishLoad(controller, lib)
}

private fun finishLoad(
    controller: MpvPlayerController,
    lib: HardeningLibMpv,
) {
    val entryId = lib.currentPlaylistEntryId
    lib.enqueueStart(entryId)
    lib.enqueueFileLoaded()
    waitUntil { controller.playbackState.value.status == PlaybackStatus.Paused }
}

private val testSession =
    Session(
        serverUrl = "https://jellyfin.example",
        serverId = "server-1",
        serverName = "Home",
        userId = "user-1",
        userName = "User",
        accessToken = "token",
        deviceId = "device-1",
    )

private fun waitUntil(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Timed out waiting for mpv controller state" }
        Thread.sleep(20L)
    }
}

private fun waitForAdditionalReads(
    readCount: AtomicInteger,
    additionalReads: Int,
) {
    val target = readCount.get() + additionalReads
    waitUntil { readCount.get() >= target }
}

private const val TEST_INTERLEAVING_TIMEOUT_MS = 3_000L
