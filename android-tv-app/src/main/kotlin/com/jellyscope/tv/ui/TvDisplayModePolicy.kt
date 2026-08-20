// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import kotlin.math.abs
import kotlin.math.roundToInt

internal data class TvDisplayMode(
    val id: Int,
    val width: Int,
    val height: Int,
    val refreshRateMilliHz: Int,
    val alternativeRefreshRatesMilliHz: List<Int> = emptyList(),
)

internal sealed interface TvDisplayModeDecision {
    data class KeepCurrent(
        val reason: TvDisplayModeKeepCurrentReason,
        val tier: TvDisplayModeMatchTier? = null,
    ) : TvDisplayModeDecision

    data class Switch(
        val modeId: Int,
        val tier: TvDisplayModeMatchTier,
        val seamless: Boolean,
    ) : TvDisplayModeDecision
}

internal enum class TvDisplayModeKeepCurrentReason {
    AlreadyMatching,
    NoBetterMode,
    InvalidTarget,
    SingleMode,
    NoSupportedModes,
}

internal enum class TvDisplayModeMatchTier(
    internal val priority: Int,
) {
    Exact(priority = 0),
    IntegerMultiple(priority = 1),
    TwoPointFive(priority = 2),
}

internal enum class TvDisplayModeResult {
    Idle,
    Off,
    Unavailable,
    Applied,
    Switched,
    TimedOut,
}

internal data class TvDisplayModeTelemetry(
    val activeMode: TvDisplayMode? = null,
    val requestedMode: TvDisplayMode? = null,
    val tier: TvDisplayModeMatchTier? = null,
    val result: TvDisplayModeResult = TvDisplayModeResult.Idle,
    val switchDurationMs: Long? = null,
)

internal fun selectDisplayMode(
    current: TvDisplayMode,
    supported: List<TvDisplayMode>,
    targetFrameRate: Double?,
): TvDisplayModeDecision {
    val target = targetFrameRate ?: return TvDisplayModeDecision.KeepCurrent(TvDisplayModeKeepCurrentReason.InvalidTarget)
    if (!target.isFinite() || target <= 0.0 || target > MAX_CONTENT_FRAME_RATE_HZ) {
        return TvDisplayModeDecision.KeepCurrent(TvDisplayModeKeepCurrentReason.InvalidTarget)
    }
    if (supported.isEmpty()) {
        return TvDisplayModeDecision.KeepCurrent(TvDisplayModeKeepCurrentReason.NoSupportedModes)
    }
    if (supported.map(TvDisplayMode::id).distinct().size <= 1) {
        return TvDisplayModeDecision.KeepCurrent(TvDisplayModeKeepCurrentReason.SingleMode)
    }

    val candidates =
        (supported.filter { mode -> mode.id != current.id } + current)
            .filter { mode -> mode.width == current.width && mode.height == current.height }
            .mapNotNull { mode -> mode.scoreFor(target) }
    val winning =
        candidates.minWithOrNull(
            compareBy<DisplayModeScore> { score -> score.tier.priority }
                .thenBy { score -> score.normalizedRateError }
                .thenBy { score -> score.mode.id },
        ) ?: return TvDisplayModeDecision.KeepCurrent(TvDisplayModeKeepCurrentReason.NoBetterMode)

    if (winning.mode.id == current.id) {
        return TvDisplayModeDecision.KeepCurrent(
            reason = TvDisplayModeKeepCurrentReason.AlreadyMatching,
            tier = winning.tier,
        )
    }
    return TvDisplayModeDecision.Switch(
        modeId = winning.mode.id,
        tier = winning.tier,
        seamless = current.alternativeRefreshRatesMilliHz.contains(winning.mode.refreshRateMilliHz),
    )
}

private data class DisplayModeScore(
    val mode: TvDisplayMode,
    val tier: TvDisplayModeMatchTier,
    val normalizedRateError: Double,
)

private fun TvDisplayMode.scoreFor(targetFrameRate: Double): DisplayModeScore? {
    val modeRate = refreshRateMilliHz.toDouble() / MILLI_HERTZ_PER_HERTZ
    if (modeRate <= 0.0 || !modeRate.isFinite()) return null

    val exactExpectedRate = targetFrameRate
    normalizedRateError(modeRate, exactExpectedRate).takeIf { error -> error.isWithinDisplayModeTolerance() }?.let { error ->
        return DisplayModeScore(this, TvDisplayModeMatchTier.Exact, error)
    }

    val multiple = (modeRate / targetFrameRate).roundToInt()
    if (multiple >= 2) {
        val expectedRate = multiple * targetFrameRate
        normalizedRateError(modeRate, expectedRate).takeIf { error -> error.isWithinDisplayModeTolerance() }?.let { error ->
            return DisplayModeScore(this, TvDisplayModeMatchTier.IntegerMultiple, error)
        }
    }

    val twoPointFiveExpectedRate = TWO_POINT_FIVE_MULTIPLIER * targetFrameRate
    normalizedRateError(modeRate, twoPointFiveExpectedRate).takeIf { error -> error.isWithinDisplayModeTolerance() }?.let { error ->
        return DisplayModeScore(this, TvDisplayModeMatchTier.TwoPointFive, error)
    }
    return null
}

private fun normalizedRateError(
    actualRate: Double,
    expectedRate: Double,
): Double = abs(actualRate - expectedRate) / expectedRate

private fun Double.isWithinDisplayModeTolerance(): Boolean = this <= DISPLAY_MODE_RATE_TOLERANCE + DISPLAY_MODE_TOLERANCE_EPSILON

internal const val DISPLAY_MODE_RATE_TOLERANCE = 0.002
internal const val MAX_CONTENT_FRAME_RATE_HZ = 240.0
private const val DISPLAY_MODE_TOLERANCE_EPSILON = 1e-12
private const val MILLI_HERTZ_PER_HERTZ = 1_000.0
private const val TWO_POINT_FIVE_MULTIPLIER = 2.5
