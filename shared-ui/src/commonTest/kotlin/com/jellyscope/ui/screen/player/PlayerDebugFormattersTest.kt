// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.PlannedVideoPresentation
import com.jellyscope.core.domain.playback.PlaybackBitrateConstraint
import com.jellyscope.core.domain.playback.PlaybackClientTrigger
import com.jellyscope.core.domain.playback.PlaybackFirstVideoOutputState
import com.jellyscope.core.domain.playback.PlaybackHealthThresholdClass
import com.jellyscope.core.domain.playback.PlaybackQualityPolicyOrigin
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.VideoOutputEvidence
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * These formatters are rendered by BOTH the shared overlay and the Android TV
 * overlay, so a change here is visible on two surfaces.
 */
class PlayerDebugFormattersTest {
    @Test
    fun videoPresentationJoinsResolutionFrameRateAndRange() {
        val presentation =
            PlannedVideoPresentation(
                width = 1920,
                height = 1080,
                frameRate = 23.976,
                videoRangeType = "SDR",
            )

        assertEquals("1920×1080 @ 23.976 fps · SDR", presentation.debugLabel())
    }

    @Test
    fun videoPresentationFallsBackWhenNothingIsKnown() {
        val presentation =
            PlannedVideoPresentation(
                width = null,
                height = null,
                frameRate = null,
                videoRangeType = null,
            )

        assertEquals("—", presentation.debugLabel())
    }

    @Test
    fun runtimeFormatFallsBackWhenDimensionsAreUnknown() {
        assertEquals("—", PlaybackRuntimeDiagnostics.EMPTY.runtimeFormatSummary())
    }

    @Test
    fun bufferedAheadReadsAsSecondsWithOneDecimal() {
        assertEquals("12.3 s", 12_345L.debugSecondsLabel())
        assertEquals("0.0 s", 49L.debugSecondsLabel())
        assertEquals("2.0 s", 2_000L.debugSecondsLabel())
    }

    @Test
    fun rebufferSummaryZeroFillsMissingTotals() {
        val diagnostics = PlaybackRuntimeDiagnostics.EMPTY.copy(rebufferCount = 2)

        assertEquals("2 · 0 ms total · 0 ms max", diagnostics.rebufferSummary())
    }

    @Test
    fun bufferAllocationFallsBackWhenNoAllocationIsReported() {
        assertEquals("—", PlaybackRuntimeDiagnostics.EMPTY.bufferAllocationSummary())
    }

    @Test
    fun decoderPreflightLabelDoesNotReadLikeTheOptionalBitrateBudget() {
        assertEquals(
            "Decoder profile incompatibility",
            PlaybackClientTrigger.DecodeCapabilityCap.debugLabel(),
        )
    }

    @Test
    fun unboundedPolicyShowsNoClientLimit() {
        assertEquals("No client limit", PlaybackBitrateConstraint.NoClientLimit.debugClientLimiterLabel())
    }

    @Test
    fun debugLabelsKeepPolicyOriginAndOutputEvidenceSeparate() {
        assertEquals("Playback settings default", PlaybackQualityPolicyOrigin.SettingsDefault.debugLabel())
        assertEquals("Current playback choice", PlaybackQualityPolicyOrigin.SessionOverride.debugLabel())
        assertEquals(
            "Observed · Displayed-picture counter",
            PlayerFirstVideoOutputDebug(
                state = PlaybackFirstVideoOutputState.Observed,
                evidence = VideoOutputEvidence.DisplayedPictureCounter,
            ).debugLabel(),
        )
    }

    @Test
    fun noVideoThresholdLabelsDistinguishVlcAndOtherBackends() {
        assertEquals(
            "5s without video output",
            PlaybackHealthThresholdClass.NoVideoOutput5Seconds.debugLabel(),
        )
        assertEquals(
            "20s without video output",
            PlaybackHealthThresholdClass.NoVideoOutput20Seconds.debugLabel(),
        )
    }

    @Test
    fun configuredVlcDefaultNamesItsSettingsSource() {
        assertEquals("Use playback default", PlayerDebugInfo(playMethod = "Direct Play").configuredVlcTranscodeBudgetSummary())
        assertEquals(
            "8.0 Mbps · Playback settings",
            PlayerDebugInfo(
                playMethod = "Transcode",
                configuredVlcTranscodeBudgetBps = 8_000_000L,
            ).configuredVlcTranscodeBudgetSummary(),
        )
    }
}
