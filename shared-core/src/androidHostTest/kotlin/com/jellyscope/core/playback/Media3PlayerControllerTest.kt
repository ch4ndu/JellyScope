// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jellyscope.core.data.remote.ClientInfo
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.platform.DeviceInfoProvider
import com.jellyscope.core.domain.playback.AudioActivationTarget
import com.jellyscope.core.domain.playback.EmbeddedAudioSelection
import com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection
import com.jellyscope.core.domain.playback.EmbeddedTrackKind
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.Media3AudioDecoderReason
import com.jellyscope.core.domain.playback.NativeTrackMappingReason
import com.jellyscope.core.domain.playback.NativeTrackMappingResult
import com.jellyscope.core.domain.playback.PlannedEmbeddedTrack
import com.jellyscope.core.domain.playback.PlaybackHealthMeasurementCapabilities
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleActivationIdentity
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.jellyscope.core.domain.playback.VideoOutputMeasurementCapabilities
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class Media3PlayerControllerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun androidControllerReportsMeasurementCapabilitiesWithoutPresentationPolicy() {
        val controller = media3PlayerController()
        try {
            assertEquals(
                PlaybackHealthMeasurementCapabilities(
                    hasReliableBufferingTransitions = true,
                    hasDroppedFrameMeasurements = true,
                    hasReliableFirstVideoOutput = true,
                ),
                controller.playbackHealthMeasurementCapabilities,
            )
            assertEquals(
                VideoOutputMeasurementCapabilities.NativeFirstOutput,
                controller.videoOutputMeasurementCapabilities,
            )
        } finally {
            controller.release()
        }
    }

    @Test
    fun prepareAndReleaseClearRuntimeDiagnosticsForEachItem() {
        val controller = media3PlayerController()
        try {
            controller.setRuntimeDiagnosticsForTest(populatedRuntimeDiagnostics)

            controller.prepare(testPlan)

            val diagnostics = controller.runtimeDiagnostics.value
            assertEquals(1L, diagnostics.prepareEpoch)
            assertEquals(MEDIA3_CONTROL_TARGET_BUFFER_BYTES.toLong(), diagnostics.targetBufferBytes)
            assertEquals(0, diagnostics.rebufferCount)
            assertEquals(0, diagnostics.audioUnderrunCount)
            assertNull(diagnostics.videoDecoderName)
            assertNull(diagnostics.bandwidthEstimateBps)

            controller.setRuntimeDiagnosticsForTest(populatedRuntimeDiagnostics)
            controller.release()

            assertEquals(PlaybackRuntimeDiagnostics.EMPTY, controller.runtimeDiagnostics.value)
        } finally {
            controller.release()
        }
    }

    @Test
    fun runtimeDiagnosticsPublishZeroBeforeFirstDropAndAccumulateDroppedFrames() {
        val afterVideoInfo =
            media3RuntimeDiagnosticsAfterVideoInfo(
                current = PlaybackRuntimeDiagnostics.EMPTY,
                decoderName = "c2.android.avc.decoder",
                width = 1920,
                height = 1080,
                frameRate = 23.976,
            )

        val afterDrops =
            media3RuntimeDiagnosticsAfterDroppedFrames(
                media3RuntimeDiagnosticsAfterDroppedFrames(afterVideoInfo, 2, elapsedMs = 1_000L),
                3,
                elapsedMs = 1_000L,
            )

        assertEquals("c2.android.avc.decoder", afterVideoInfo.videoDecoderName)
        assertEquals(0L, afterVideoInfo.droppedVideoFrames)
        assertEquals(5L, afterDrops.droppedVideoFrames)
        assertEquals(5L, afterDrops.decoderDroppedVideoFrames)
        // The overlay's rate describes the most recent report only; the count accumulates.
        assertEquals(3.0, afterDrops.droppedVideoFramesPerSecond)
    }

    @Test
    fun bundledFfmpegAudioDecoderClassificationFormatsOnlyClosedCorrelatedEvidence() {
        val rawDecoderName = "ffmpeg8.0.1-Jellyfin-eac3"
        val classification = classifyMedia3AudioDecoder(rawDecoderName)
        val diagnostic =
            formatPlaybackDiagnostic(
                media3AudioDecoderInitializedDiagnostic(
                    decoderName = rawDecoderName,
                    prepareSequence = 7L,
                    sessionSequence = 11L,
                ),
            )

        assertEquals(Media3AudioDecoderReason.BundledFfmpeg, classification.reason)
        assertEquals("eac3", classification.codec)
        assertEquals(
            "stage=nativeplayer event=native-lifecycle platform=android backend=exoplayer " +
                "prepareSequence=7 sessionSequence=11 codec=eac3 reason=BundledFfmpeg",
            diagnostic,
        )
        assertFalse(rawDecoderName in diagnostic)
        assertEquals(Media3AudioDecoderReason.Platform, classifyMedia3AudioDecoder("c2.android.eac3.decoder").reason)
        assertEquals(Media3AudioDecoderReason.Unknown, classifyMedia3AudioDecoder(" ").reason)
    }

    @Test
    fun droppedFrameRateUsesMedia3AccumulationIntervalAndIsNullWithoutOne() {
        val batched =
            media3RuntimeDiagnosticsAfterDroppedFrames(
                PlaybackRuntimeDiagnostics.EMPTY,
                50,
                elapsedMs = 25_000L,
            )
        // A realistic 50-frame Media3 batch over its own interval: 2 fps.
        assertEquals(2.0, batched.droppedVideoFramesPerSecond)
        assertEquals(50L, batched.droppedVideoFrames)

        val withoutInterval =
            media3RuntimeDiagnosticsAfterDroppedFrames(
                PlaybackRuntimeDiagnostics.EMPTY,
                50,
                elapsedMs = 0L,
            )
        assertNull(withoutInterval.droppedVideoFramesPerSecond)
        assertEquals(50L, withoutInterval.droppedVideoFrames)
    }

    @Test
    fun externalSubtitleIdsMatchExactOrMediaPeriodPrefixedTargetsOnly() {
        val targetId = "jellyscope-subtitle-7-item-4"

        assertTrue(media3FormatIdMatchesTarget(targetId, targetId))
        assertTrue(media3FormatIdMatchesTarget("1:$targetId", targetId))
        assertFalse(media3FormatIdMatchesTarget("1:jellyscope-subtitle-8-item-4", targetId))
        assertFalse(media3FormatIdMatchesTarget("prefix$targetId", targetId))
        assertFalse(media3FormatIdMatchesTarget(null, targetId))
    }

    @Test
    fun numericColonIdsSortNumericallyBeforeStableMalformedIds() {
        val ids = listOf("prefix:2", "1:10", "1:2", null, "3")
        val sorted =
            ids.withIndex().sortedWith { left, right ->
                compareMedia3TrackIds(left.value, right.value, left.index, right.index)
            }

        assertEquals(listOf("1:2", "1:10", "3", "prefix:2", null), sorted.map { it.value })
    }

    @Test
    fun onlyFullyNumericIdsExposeStableSourceIdentity() {
        assertEquals(7, "2:7".media3StableSourceIndex())
        assertEquals(9, "9".media3StableSourceIndex())
        assertEquals(null, "period:7".media3StableSourceIndex())
        assertEquals(null, "7:bad".media3StableSourceIndex())
        assertEquals(null, null.media3StableSourceIndex())
    }

    @Test
    fun unsupportedSubtitleGroupsRetainOrdinalSlotsAndFailAfterExactResolution() {
        val descriptor = PlannedEmbeddedTrack(7, 0, "srt", "eng", null)
        val candidates =
            listOf(
                media3Candidate("unsupported", codec = "application/x-subrip", language = "eng", supported = false),
                media3Candidate("supported", codec = "srt", language = "spa", supported = true),
            )

        val result = resolveMedia3TrackCandidates(descriptor, EmbeddedTrackKind.Subtitle, candidates)

        assertEquals(NativeTrackMappingResult.Unsupported, result.result)
        assertEquals(NativeTrackMappingReason.UnsupportedCandidate, result.reason)
        assertEquals(null, result.candidate)
    }

    @Test
    fun audioCandidatesKeepSupportedOnlyOrderingAndFamilyCodecPolicy() {
        val ordinal =
            resolveMedia3TrackCandidates(
                PlannedEmbeddedTrack(7, 0, "aac", null, null),
                EmbeddedTrackKind.Audio,
                listOf(
                    media3Candidate("unsupported", codec = "aac", supported = false),
                    media3Candidate("supported", codec = "aac", supported = true),
                ),
            )
        // Same-family aliases (Jellyfin "aac" vs Media3 codec string) resolve
        // instead of conflicting; genuinely different families still conflict.
        val familyAlias =
            resolveMedia3TrackCandidates(
                PlannedEmbeddedTrack(7, 0, "aac", null, null),
                EmbeddedTrackKind.Audio,
                listOf(media3Candidate("alias", codec = "mp4a.40.2", supported = true)),
            )
        val codecConflict =
            resolveMedia3TrackCandidates(
                PlannedEmbeddedTrack(7, 0, "aac", null, null),
                EmbeddedTrackKind.Audio,
                listOf(media3Candidate("different", codec = "ac-3", supported = true)),
            )

        assertEquals("supported", ordinal.candidate)
        assertEquals("alias", familyAlias.candidate)
        assertEquals(NativeTrackMappingResult.Active, familyAlias.result)
        assertEquals(NativeTrackMappingResult.NotFound, codecConflict.result)
        assertEquals(NativeTrackMappingReason.CodecConflict, codecConflict.reason)
    }

    @Test
    fun pendingInitialAudioGateIsBufferingRatherThanUserPaused() {
        assertEquals(
            PlaybackStatus.Buffering,
            media3ReadyPlaybackStatus(isPlaying = false, playIntent = true, initialAudioGate = true),
        )
        assertEquals(
            PlaybackStatus.Paused,
            media3ReadyPlaybackStatus(isPlaying = false, playIntent = false, initialAudioGate = true),
        )
        assertEquals(
            PlaybackStatus.Playing,
            media3ReadyPlaybackStatus(isPlaying = true, playIntent = true, initialAudioGate = true),
        )
    }

    @Test
    fun delayedRecoveryRequiresLiveGenerationAndPlan() {
        assertTrue(media3RecoveryIsCurrent(released = false, recoveryGeneration = 2L, activeGeneration = 2L, samePlan = true))
        assertFalse(media3RecoveryIsCurrent(released = true, recoveryGeneration = 2L, activeGeneration = 2L, samePlan = true))
        assertFalse(media3RecoveryIsCurrent(released = false, recoveryGeneration = 1L, activeGeneration = 2L, samePlan = true))
        assertFalse(media3RecoveryIsCurrent(released = false, recoveryGeneration = 2L, activeGeneration = 2L, samePlan = false))
    }

    @Test
    fun inPlayerSubtitleSelectionSyncsTheControllerPlanSoRePreparesFollowLiveIntent() {
        val controller = media3PlayerController()
        try {
            val plannedTarget = embeddedSubtitleTarget(requestId = 1L, streamIndex = 2)
            controller.prepare(testPlan.copy(subtitleActivationTarget = plannedTarget))

            val switchedTarget = embeddedSubtitleTarget(requestId = 2L, streamIndex = 3)
            controller.selectEmbeddedSubtitle(
                EmbeddedSubtitleSelection(
                    target = switchedTarget,
                    descriptor = PlannedEmbeddedTrack(3, 1, "srt", "eng", null),
                ),
            )

            // The internal re-prepares rebuild the media item from this plan, so a
            // stale target here silently reverts the user's in-player switch.
            assertEquals(switchedTarget, controller.planForTest()?.subtitleActivationTarget)

            controller.selectEmbeddedSubtitle(null)

            val offPlan = controller.planForTest()
            assertEquals(testPlan.itemId, offPlan?.itemId)
            assertNull(offPlan?.subtitleActivationTarget)
        } finally {
            controller.release()
        }
    }

    @Test
    fun subtitleOffDropsTheSidecarAssetSoRePreparesCannotReArmIt() {
        val controller = media3PlayerController()
        val asset =
            SubtitleAsset.JellyfinRemote(
                url = "https://jellyfin.example/Videos/item/Subtitles/2/Stream.srt",
                mimeType = "application/x-subrip",
                label = "English",
                language = "eng",
            )
        try {
            val externalTarget =
                SubtitleActivationTarget(
                    requestId = 1L,
                    itemId = "item",
                    identity = SubtitleActivationIdentity.JellyfinTrack(2),
                    kind = LocalSubtitleKind.ExternalText,
                )
            controller.prepare(
                testPlan.copy(subtitleActivationTarget = externalTarget, subtitleAsset = asset),
                asset,
            )

            controller.selectEmbeddedSubtitle(null)

            val plan = controller.planForTest()
            assertEquals(testPlan.itemId, plan?.itemId)
            assertNull(plan?.subtitleActivationTarget)
            assertNull(plan?.subtitleAsset)
        } finally {
            controller.release()
        }
    }

    @Test
    fun retryRetainsAudioAndExactSubtitleSelection() {
        val controller = media3PlayerController()
        val subtitleTarget = embeddedSubtitleTarget(requestId = 11L, streamIndex = 2)
        val subtitleSelection = embeddedSubtitleSelection(subtitleTarget)
        val audioSelection = embeddedAudioSelection()
        try {
            controller.prepare(testPlan.copy(subtitleActivationTarget = subtitleTarget))
            controller.selectEmbeddedAudio(audioSelection)
            controller.setPendingSubtitleSelectionForTest(subtitleSelection)

            controller.retry()

            assertEquals(2L, controller.runtimeDiagnostics.value.prepareEpoch)
            assertEquals(audioSelection, controller.pendingAudioSelectionForTest())
            assertEquals(subtitleSelection, controller.pendingSubtitleSelectionForTest())
            assertEquals(
                SubtitleActivationState.Pending(subtitleTarget),
                controller.playbackState.value.subtitleActivation,
            )
        } finally {
            controller.release()
        }
    }

    @Test
    fun retryRejectsStaleSubtitleSelectionAndRestoresPlanTarget() {
        val controller = media3PlayerController()
        val plannedTarget = embeddedSubtitleTarget(requestId = 21L, streamIndex = 2)
        val staleSelection =
            embeddedSubtitleSelection(
                embeddedSubtitleTarget(requestId = 22L, streamIndex = 2),
            )
        try {
            controller.prepare(testPlan.copy(subtitleActivationTarget = plannedTarget))
            controller.setPendingSubtitleSelectionForTest(staleSelection)

            controller.retry()

            assertEquals(2L, controller.runtimeDiagnostics.value.prepareEpoch)
            assertNull(controller.pendingSubtitleSelectionForTest())
            assertEquals(plannedTarget, controller.planForTest()?.subtitleActivationTarget)
            assertEquals(
                SubtitleActivationState.Pending(plannedTarget),
                controller.playbackState.value.subtitleActivation,
            )
        } finally {
            controller.release()
        }
    }

    @Test
    fun retryTreatsAmbiguousNullAsUnspecifiedAndRestoresPlanTarget() {
        val controller = media3PlayerController()
        val plannedTarget = embeddedSubtitleTarget(requestId = 31L, streamIndex = 2)
        try {
            controller.prepare(testPlan.copy(subtitleActivationTarget = plannedTarget))

            controller.retry()

            assertEquals(2L, controller.runtimeDiagnostics.value.prepareEpoch)
            assertNull(controller.pendingSubtitleSelectionForTest())
            assertEquals(plannedTarget, controller.planForTest()?.subtitleActivationTarget)
            assertEquals(
                SubtitleActivationState.Pending(plannedTarget),
                controller.playbackState.value.subtitleActivation,
            )
        } finally {
            controller.release()
        }
    }

    private fun embeddedSubtitleTarget(
        requestId: Long,
        streamIndex: Int,
    ) = SubtitleActivationTarget(
        requestId = requestId,
        itemId = "item",
        identity = SubtitleActivationIdentity.JellyfinTrack(streamIndex),
        kind = LocalSubtitleKind.EmbeddedText,
    )

    private fun embeddedSubtitleSelection(target: SubtitleActivationTarget) =
        EmbeddedSubtitleSelection(
            target = target,
            descriptor = PlannedEmbeddedTrack(target.streamIndex ?: 2, 0, "srt", "eng", "English"),
        )

    private fun embeddedAudioSelection() =
        EmbeddedAudioSelection(
            target = AudioActivationTarget(requestId = 10L, itemId = "item", streamIndex = 1),
            descriptor = PlannedEmbeddedTrack(1, 0, "aac", "eng", "English"),
        )

    private fun media3PlayerController() =
        Media3PlayerController(
            context = context,
            session =
                Session(
                    serverUrl = "https://jellyfin.example",
                    serverId = "server",
                    serverName = "Jellyfin",
                    userId = "user",
                    userName = "User",
                    accessToken = "token",
                    deviceId = "device",
                ),
            deviceInfoProvider =
                object : DeviceInfoProvider {
                    override val deviceName = "Test device"

                    override fun newDeviceId(): String = "new-device"
                },
            clientInfo = ClientInfo(versionName = "test"),
        )
}

private fun media3Candidate(
    value: String,
    codec: String? = null,
    language: String? = null,
    supported: Boolean,
) = Media3TrackResolutionCandidate(
    value = value,
    stableSourceIndex = null,
    codec = codec,
    language = language,
    label = null,
    supported = supported,
)

private val testPlan =
    PlaybackPlan(
        itemId = "item",
        mediaSourceId = "source",
        startPositionMs = 0L,
        streamMode = StreamMode.DirectPlay,
        streamUrl = "https://jellyfin.example/Videos/item/stream",
        progressReportingPolicy = ProgressReportingPolicy(10_000L),
    )

private val populatedRuntimeDiagnostics =
    PlaybackRuntimeDiagnostics(
        videoDecoderName = "test-decoder",
        videoWidth = 1920,
        videoHeight = 1080,
        videoFrameRate = 24.0,
        droppedVideoFrames = 1L,
        bandwidthEstimateBps = 1_000_000L,
    )

private fun Media3PlayerController.planForTest(): PlaybackPlan? {
    val field = Media3PlayerController::class.java.getDeclaredField("lastPlan")
    field.isAccessible = true
    return field.get(this) as PlaybackPlan?
}

private fun Media3PlayerController.pendingAudioSelectionForTest(): EmbeddedAudioSelection? {
    val field = Media3PlayerController::class.java.getDeclaredField("pendingEmbeddedAudioSelection")
    field.isAccessible = true
    return field.get(this) as EmbeddedAudioSelection?
}

private fun Media3PlayerController.pendingSubtitleSelectionForTest(): EmbeddedSubtitleSelection? {
    val field = Media3PlayerController::class.java.getDeclaredField("pendingEmbeddedSubtitleSelection")
    field.isAccessible = true
    return field.get(this) as EmbeddedSubtitleSelection?
}

private fun Media3PlayerController.setPendingSubtitleSelectionForTest(selection: EmbeddedSubtitleSelection) {
    val field = Media3PlayerController::class.java.getDeclaredField("pendingEmbeddedSubtitleSelection")
    field.isAccessible = true
    field.set(this, selection)
}

@Suppress("UNCHECKED_CAST")
private fun Media3PlayerController.setRuntimeDiagnosticsForTest(value: PlaybackRuntimeDiagnostics) {
    val field = Media3PlayerController::class.java.getDeclaredField("_runtimeDiagnostics")
    field.isAccessible = true
    (field.get(this) as MutableStateFlow<PlaybackRuntimeDiagnostics>).value = value
}
