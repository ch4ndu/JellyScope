// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.AudioActivationTarget
import com.jellyscope.core.domain.playback.NativeTrackMappingReason
import com.jellyscope.core.domain.playback.NativeTrackMappingResult
import com.jellyscope.core.domain.playback.PlaybackDiagnosticTrackKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackResolutionDiagnosticGateTest {
    @Test
    fun unchangedOutcomeIsAdmittedOncePerTrackKind() {
        val gate = TrackResolutionDiagnosticGate()

        assertTrue(gate.admitAudio())
        assertFalse(gate.admitAudio())
    }

    @Test
    fun everyKeyFieldDifferenceAdmits() {
        val gate = TrackResolutionDiagnosticGate()
        val firstTarget = AudioActivationTarget(requestId = 1L, itemId = "item-1", streamIndex = 4)
        val secondTarget = AudioActivationTarget(requestId = 2L, itemId = "item-1", streamIndex = 4)

        assertTrue(gate.admitAudio(target = firstTarget))
        assertTrue(gate.admitAudio(target = secondTarget))
        assertTrue(gate.admitAudio(target = secondTarget, candidateCount = 2))
        assertTrue(
            gate.admitAudio(
                target = secondTarget,
                candidateCount = 2,
                result = NativeTrackMappingResult.NotFound,
            ),
        )
        assertTrue(
            gate.admitAudio(
                target = secondTarget,
                candidateCount = 2,
                result = NativeTrackMappingResult.NotFound,
                reason = NativeTrackMappingReason.CodecConflict,
            ),
        )
    }

    @Test
    fun audioAndSubtitleOutcomesAreIndependent() {
        val gate = TrackResolutionDiagnosticGate()

        assertTrue(gate.admitAudio())
        assertTrue(
            gate.admit(
                kind = PlaybackDiagnosticTrackKind.Subtitle,
                target = gateTestTarget,
                candidateCount = 1,
                result = NativeTrackMappingResult.Active,
                reason = NativeTrackMappingReason.Ordinal,
            ),
        )
        assertFalse(gate.admitAudio())
        assertFalse(
            gate.admit(
                kind = PlaybackDiagnosticTrackKind.Subtitle,
                target = gateTestTarget,
                candidateCount = 1,
                result = NativeTrackMappingResult.Active,
                reason = NativeTrackMappingReason.Ordinal,
            ),
        )
    }

    @Test
    fun resetAllowsAnIdenticalOutcomeToBeAdmittedAgain() {
        val gate = TrackResolutionDiagnosticGate()

        assertTrue(gate.admitAudio())
        gate.reset()
        assertTrue(gate.admitAudio())
    }

    @Test
    fun resetOnAnEmptyGateIsANoOp() {
        val gate = TrackResolutionDiagnosticGate()

        gate.reset()

        assertTrue(gate.admitAudio())
    }

    private fun TrackResolutionDiagnosticGate.admitAudio(
        target: Any? = gateTestTarget,
        candidateCount: Int = 1,
        result: NativeTrackMappingResult = NativeTrackMappingResult.Active,
        reason: NativeTrackMappingReason = NativeTrackMappingReason.Ordinal,
    ): Boolean =
        admit(
            kind = PlaybackDiagnosticTrackKind.Audio,
            target = target,
            candidateCount = candidateCount,
            result = result,
            reason = reason,
        )
}

private val gateTestTarget = AudioActivationTarget(requestId = 1L, itemId = "item-1", streamIndex = 4)
