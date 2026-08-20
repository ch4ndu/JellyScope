// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import org.junit.Test
import kotlin.test.assertEquals

class TvDisplayModePolicyTest {
    @Test
    fun exactMatchBeatsIntegerMultipleAndTwoPointFiveFallback() {
        val decision =
            selectDisplayMode(
                current = mode(id = 1, rateMilliHz = 60_000),
                supported = listOf(mode(id = 2, rateMilliHz = 48_000), mode(id = 3, rateMilliHz = 24_000)),
                targetFrameRate = 24.0,
            )

        assertEquals(TvDisplayModeDecision.Switch(modeId = 3, tier = TvDisplayModeMatchTier.Exact, seamless = false), decision)
    }

    @Test
    fun currentTwoPointFiveModeLosesToAvailableExactMode() {
        val decision =
            selectDisplayMode(
                current = mode(id = 1, rateMilliHz = 60_000),
                supported = listOf(mode(id = 1, rateMilliHz = 60_000), mode(id = 2, rateMilliHz = 23_976)),
                targetFrameRate = 23.976,
            )

        assertEquals(TvDisplayModeDecision.Switch(modeId = 2, tier = TvDisplayModeMatchTier.Exact, seamless = false), decision)
    }

    @Test
    fun currentModeWinningOnTwoPointFiveFallbackKeepsCurrentWithFallbackTier() {
        val decision =
            selectDisplayMode(
                current = mode(id = 1, rateMilliHz = 60_000),
                supported = listOf(mode(id = 1, rateMilliHz = 60_000), mode(id = 2, rateMilliHz = 50_000)),
                targetFrameRate = 24.0,
            )

        assertEquals(
            TvDisplayModeDecision.KeepCurrent(
                reason = TvDisplayModeKeepCurrentReason.AlreadyMatching,
                tier = TvDisplayModeMatchTier.TwoPointFive,
            ),
            decision,
        )
    }

    @Test
    fun currentModeWinningExactlyKeepsCurrentWithExactTier() {
        val decision =
            selectDisplayMode(
                current = mode(id = 1, rateMilliHz = 23_976),
                supported = listOf(mode(id = 1, rateMilliHz = 23_976), mode(id = 2, rateMilliHz = 60_000)),
                targetFrameRate = 23.976,
            )

        assertEquals(
            TvDisplayModeDecision.KeepCurrent(
                reason = TvDisplayModeKeepCurrentReason.AlreadyMatching,
                tier = TvDisplayModeMatchTier.Exact,
            ),
            decision,
        )
    }

    @Test
    fun closerNtscRateBeatsTwentyFourHertzBeforeModeIdTieBreak() {
        val decision =
            selectDisplayMode(
                current = mode(id = 1, rateMilliHz = 60_000),
                supported = listOf(mode(id = 2, rateMilliHz = 24_000), mode(id = 9, rateMilliHz = 23_976)),
                targetFrameRate = 23.976,
            )

        assertEquals(TvDisplayModeDecision.Switch(modeId = 9, tier = TvDisplayModeMatchTier.Exact, seamless = false), decision)
    }

    @Test
    fun toleranceIncludesBoundaryAndRejectsRatesBeyondIt() {
        assertEquals(
            TvDisplayModeDecision.Switch(modeId = 2, tier = TvDisplayModeMatchTier.Exact, seamless = false),
            selectDisplayMode(
                current = mode(id = 1, rateMilliHz = 50_000),
                supported = listOf(mode(id = 1, rateMilliHz = 50_000), mode(id = 2, rateMilliHz = 24_048)),
                targetFrameRate = 24.0,
            ),
        )
        assertEquals(
            TvDisplayModeDecision.KeepCurrent(TvDisplayModeKeepCurrentReason.NoBetterMode),
            selectDisplayMode(
                current = mode(id = 1, rateMilliHz = 50_000),
                supported = listOf(mode(id = 1, rateMilliHz = 50_000), mode(id = 2, rateMilliHz = 24_049)),
                targetFrameRate = 24.0,
            ),
        )
    }

    @Test
    fun ntscAndPalFamiliesUseExactAndIntegerMultipleTiers() {
        assertEquals(
            TvDisplayModeDecision.Switch(modeId = 2, tier = TvDisplayModeMatchTier.Exact, seamless = false),
            selectDisplayMode(
                current = mode(id = 1, rateMilliHz = 60_000),
                supported = listOf(mode(id = 1, rateMilliHz = 60_000), mode(id = 2, rateMilliHz = 59_940)),
                targetFrameRate = 59.94,
            ),
        )
        assertEquals(
            TvDisplayModeDecision.Switch(modeId = 2, tier = TvDisplayModeMatchTier.IntegerMultiple, seamless = false),
            selectDisplayMode(
                current = mode(id = 1, rateMilliHz = 60_000),
                supported = listOf(mode(id = 1, rateMilliHz = 60_000), mode(id = 2, rateMilliHz = 50_000)),
                targetFrameRate = 25.0,
            ),
        )
    }

    @Test
    fun onlyConsidersModesAtTheCurrentResolution() {
        val decision =
            selectDisplayMode(
                current = mode(id = 1, width = 1920, height = 1080, rateMilliHz = 50_000),
                supported =
                    listOf(
                        mode(id = 2, width = 3840, height = 2160, rateMilliHz = 24_000),
                        mode(id = 3, width = 1920, height = 1080, rateMilliHz = 55_000),
                    ),
                targetFrameRate = 24.0,
            )

        assertEquals(TvDisplayModeDecision.KeepCurrent(TvDisplayModeKeepCurrentReason.NoBetterMode), decision)
    }

    @Test
    fun emptySingleAndInvalidInputsKeepTheCurrentMode() {
        val current = mode(id = 1, rateMilliHz = 60_000)

        assertEquals(
            TvDisplayModeDecision.KeepCurrent(TvDisplayModeKeepCurrentReason.NoSupportedModes),
            selectDisplayMode(current, emptyList(), 24.0),
        )
        assertEquals(
            TvDisplayModeDecision.KeepCurrent(TvDisplayModeKeepCurrentReason.SingleMode),
            selectDisplayMode(current, listOf(current), 24.0),
        )
        assertEquals(
            TvDisplayModeDecision.KeepCurrent(TvDisplayModeKeepCurrentReason.InvalidTarget),
            selectDisplayMode(current, listOf(current, mode(2, 24_000)), Double.NaN),
        )
    }

    @Test
    fun switchIsSeamlessOnlyWhenCurrentModeAdvertisesTheTargetRate() {
        val decision =
            selectDisplayMode(
                current = mode(id = 1, rateMilliHz = 60_000, alternatives = listOf(24_000)),
                supported = listOf(mode(id = 1, rateMilliHz = 60_000), mode(id = 2, rateMilliHz = 24_000)),
                targetFrameRate = 24.0,
            )

        assertEquals(TvDisplayModeDecision.Switch(modeId = 2, tier = TvDisplayModeMatchTier.Exact, seamless = true), decision)
    }
}

private fun mode(
    id: Int,
    rateMilliHz: Int,
    width: Int = 3840,
    height: Int = 2160,
    alternatives: List<Int> = emptyList(),
): TvDisplayMode =
    TvDisplayMode(
        id = id,
        width = width,
        height = height,
        refreshRateMilliHz = rateMilliHz,
        alternativeRefreshRatesMilliHz = alternatives,
    )
