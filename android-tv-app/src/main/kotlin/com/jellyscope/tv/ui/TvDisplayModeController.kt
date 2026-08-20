// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Window
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.jellyscope.core.domain.playback.PlaybackDiagnostic
import com.jellyscope.core.domain.playback.PlaybackDiagnosticDisplayResult
import com.jellyscope.core.domain.playback.PlaybackDiagnosticDisplayTier
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackDiagnosticStage
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Android-TV-only display refresh-rate owner. It deliberately consumes plan metadata
 * after planning and never participates in PlaybackInfo or stream-mode decisions.
 */
internal class TvDisplayModeController(
    private val platform: TvDisplayModePlatform,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val logger = diagnosticLogger(DiagnosticTag.TvDisplayMode)
    private val _telemetry = MutableStateFlow(TvDisplayModeTelemetry())
    val telemetry: StateFlow<TvDisplayModeTelemetry> = _telemetry.asStateFlow()

    private var generation = 0L
    private var listenerRegistered = false
    private var timeoutJob: Job? = null
    private var settleJob: Job? = null

    fun apply(targetFrameRate: Double?) {
        val applyGeneration = nextGeneration()
        clearPendingSwitch()
        if (!targetFrameRate.isUsableContentFrameRate()) {
            releaseDisplayControl(TvDisplayModeResult.Unavailable)
            return
        }

        val current = platform.currentMode()
        val supported = platform.supportedModes()
        if (current == null || supported.isEmpty()) {
            releaseDisplayControl(TvDisplayModeResult.Unavailable)
            return
        }

        platform.disablePlayerFrameRateSwitching()
        when (val decision = selectDisplayMode(current, supported, targetFrameRate)) {
            is TvDisplayModeDecision.KeepCurrent -> {
                if (decision.reason == TvDisplayModeKeepCurrentReason.AlreadyMatching) {
                    publish(
                        TvDisplayModeTelemetry(
                            activeMode = current,
                            requestedMode = current,
                            tier = decision.tier,
                            result = TvDisplayModeResult.Applied,
                        ),
                    )
                } else {
                    releaseDisplayControl(TvDisplayModeResult.Unavailable)
                }
            }

            is TvDisplayModeDecision.Switch -> {
                val requested =
                    supported.firstOrNull { mode -> mode.id == decision.modeId }
                        ?: current.takeIf { mode -> mode.id == decision.modeId }
                        ?: run {
                            releaseDisplayControl(TvDisplayModeResult.Unavailable)
                            return
                        }
                val startedAtMs = nowMs()
                platform.registerDisplayListener {
                    if (applyGeneration != generation || platform.currentMode()?.id != requested.id) return@registerDisplayListener
                    completeSwitch(
                        generation = applyGeneration,
                        requested = requested,
                        tier = decision.tier,
                        seamless = decision.seamless,
                        startedAtMs = startedAtMs,
                    )
                }
                listenerRegistered = true
                platform.setPreferredDisplayModeId(requested.id)
                publish(
                    TvDisplayModeTelemetry(
                        activeMode = platform.currentMode() ?: current,
                        requestedMode = requested,
                        tier = decision.tier,
                        result = TvDisplayModeResult.Applied,
                    ),
                )
                if (platform.currentMode()?.id == requested.id) {
                    completeSwitch(
                        generation = applyGeneration,
                        requested = requested,
                        tier = decision.tier,
                        seamless = decision.seamless,
                        startedAtMs = startedAtMs,
                    )
                    return
                }
                timeoutJob =
                    scope.launch {
                        delay(DISPLAY_MODE_SWITCH_TIMEOUT_MS)
                        if (applyGeneration != generation) return@launch
                        unregisterDisplayListener()
                        publish(
                            TvDisplayModeTelemetry(
                                activeMode = platform.currentMode(),
                                requestedMode = requested,
                                tier = decision.tier,
                                result = TvDisplayModeResult.TimedOut,
                                switchDurationMs = (nowMs() - startedAtMs).coerceAtLeast(0L),
                            ),
                        )
                    }
            }
        }
    }

    fun reset() {
        nextGeneration()
        clearPendingSwitch()
        platform.setPreferredDisplayModeId(DEFAULT_DISPLAY_MODE_ID)
        platform.restorePlayerFrameRateSwitching()
        publish(
            TvDisplayModeTelemetry(
                activeMode = platform.currentMode(),
                result = TvDisplayModeResult.Off,
            ),
        )
    }

    fun dispose() {
        reset()
    }

    private fun completeSwitch(
        generation: Long,
        requested: TvDisplayMode,
        tier: TvDisplayModeMatchTier,
        seamless: Boolean,
        startedAtMs: Long,
    ) {
        if (generation != this.generation) return
        unregisterDisplayListener()
        timeoutJob?.cancel()
        timeoutJob = null
        val completedAtMs = nowMs()
        if (seamless) {
            publish(
                TvDisplayModeTelemetry(
                    activeMode = platform.currentMode(),
                    requestedMode = requested,
                    tier = tier,
                    result = TvDisplayModeResult.Applied,
                    switchDurationMs = (completedAtMs - startedAtMs).coerceAtLeast(0L),
                ),
            )
            return
        }
        settleJob?.cancel()
        settleJob =
            scope.launch {
                delay(NON_SEAMLESS_SWITCH_SETTLE_MS)
                if (generation != this@TvDisplayModeController.generation) return@launch
                publish(
                    TvDisplayModeTelemetry(
                        activeMode = platform.currentMode(),
                        requestedMode = requested,
                        tier = tier,
                        result = TvDisplayModeResult.Switched,
                        switchDurationMs = (nowMs() - startedAtMs).coerceAtLeast(0L),
                    ),
                )
            }
    }

    private fun releaseDisplayControl(result: TvDisplayModeResult) {
        clearPendingSwitch()
        platform.setPreferredDisplayModeId(DEFAULT_DISPLAY_MODE_ID)
        platform.restorePlayerFrameRateSwitching()
        publish(
            TvDisplayModeTelemetry(
                activeMode = platform.currentMode(),
                result = result,
            ),
        )
    }

    private fun nextGeneration(): Long {
        generation += 1L
        return generation
    }

    private fun clearPendingSwitch() {
        unregisterDisplayListener()
        timeoutJob?.cancel()
        timeoutJob = null
        settleJob?.cancel()
        settleJob = null
    }

    private fun unregisterDisplayListener() {
        if (listenerRegistered) {
            platform.unregisterDisplayListener()
            listenerRegistered = false
        }
    }

    private fun publish(value: TvDisplayModeTelemetry) {
        _telemetry.value = value
        logger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.TvDisplay,
                    event = PlaybackDiagnosticEvent.TvDisplay,
                    platform = PlaybackDiagnosticPlatform.Android,
                    tvDisplayResult = value.result.toDiagnosticResult(),
                    tvDisplayTier = value.tier.toDiagnosticTier(),
                    tvDisplayActiveWidthPx = value.activeMode?.width,
                    tvDisplayActiveHeightPx = value.activeMode?.height,
                    tvDisplayActiveRefreshMilliHz = value.activeMode?.refreshRateMilliHz,
                    tvDisplayRequestedWidthPx = value.requestedMode?.width,
                    tvDisplayRequestedHeightPx = value.requestedMode?.height,
                    tvDisplayRequestedRefreshMilliHz = value.requestedMode?.refreshRateMilliHz,
                    tvDisplaySwitchDurationMs = value.switchDurationMs,
                ),
            )
        }
    }
}

private fun TvDisplayModeResult.toDiagnosticResult(): PlaybackDiagnosticDisplayResult =
    when (this) {
        TvDisplayModeResult.Idle -> PlaybackDiagnosticDisplayResult.Idle
        TvDisplayModeResult.Off -> PlaybackDiagnosticDisplayResult.Off
        TvDisplayModeResult.Unavailable -> PlaybackDiagnosticDisplayResult.Unavailable
        TvDisplayModeResult.Applied -> PlaybackDiagnosticDisplayResult.Applied
        TvDisplayModeResult.Switched -> PlaybackDiagnosticDisplayResult.Switched
        TvDisplayModeResult.TimedOut -> PlaybackDiagnosticDisplayResult.TimedOut
    }

private fun TvDisplayModeMatchTier?.toDiagnosticTier(): PlaybackDiagnosticDisplayTier? =
    when (this) {
        null -> null
        TvDisplayModeMatchTier.Exact -> PlaybackDiagnosticDisplayTier.Exact
        TvDisplayModeMatchTier.IntegerMultiple -> PlaybackDiagnosticDisplayTier.IntegerMultiple
        TvDisplayModeMatchTier.TwoPointFive -> PlaybackDiagnosticDisplayTier.TwoPointFive
    }

internal interface TvDisplayModePlatform {
    fun currentMode(): TvDisplayMode?

    fun supportedModes(): List<TvDisplayMode>

    fun registerDisplayListener(onDisplayChanged: () -> Unit)

    fun unregisterDisplayListener()

    fun setPreferredDisplayModeId(modeId: Int)

    fun disablePlayerFrameRateSwitching()

    fun restorePlayerFrameRateSwitching()
}

internal fun tvDisplayModeController(
    activity: Activity?,
    playerController: PlayerController,
    scope: CoroutineScope,
): TvDisplayModeController =
    TvDisplayModeController(
        platform = AndroidTvDisplayModePlatform(activity = activity, playerController = playerController),
        scope = scope,
    )

private class AndroidTvDisplayModePlatform(
    private val activity: Activity?,
    private val playerController: PlayerController,
) : TvDisplayModePlatform {
    private val window: Window? = activity?.window
    private val displayManager: DisplayManager? = activity?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private var displayListener: DisplayManager.DisplayListener? = null

    override fun currentMode(): TvDisplayMode? = currentDisplay()?.mode?.toTvDisplayMode()

    override fun supportedModes(): List<TvDisplayMode> = currentDisplay()?.supportedModes?.map(Display.Mode::toTvDisplayMode).orEmpty()

    override fun registerDisplayListener(onDisplayChanged: () -> Unit) {
        val displayManager = displayManager ?: return
        val displayId = currentDisplay()?.displayId ?: return
        unregisterDisplayListener()
        val listener =
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit

                override fun onDisplayRemoved(displayId: Int) = Unit

                override fun onDisplayChanged(changedDisplayId: Int) {
                    if (changedDisplayId == displayId) {
                        onDisplayChanged()
                    }
                }
            }
        displayListener = listener
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    }

    override fun unregisterDisplayListener() {
        val listener = displayListener ?: return
        displayManager?.unregisterDisplayListener(listener)
        displayListener = null
    }

    override fun setPreferredDisplayModeId(modeId: Int) {
        val window = window ?: return
        val attributes = window.attributes
        attributes.preferredDisplayModeId = modeId
        window.attributes = attributes
    }

    override fun disablePlayerFrameRateSwitching() {
        (playerController.platformPlayer as? ExoPlayer)?.setVideoChangeFrameRateStrategy(
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF,
        )
    }

    override fun restorePlayerFrameRateSwitching() {
        (playerController.platformPlayer as? ExoPlayer)?.setVideoChangeFrameRateStrategy(
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS,
        )
    }

    private fun currentDisplay(): Display? = window?.decorView?.display
}

private fun Display.Mode.toTvDisplayMode(): TvDisplayMode =
    TvDisplayMode(
        id = modeId,
        width = physicalWidth,
        height = physicalHeight,
        refreshRateMilliHz = (refreshRate * MILLI_HERTZ_PER_HERTZ).roundToInt(),
        alternativeRefreshRatesMilliHz =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alternativeRefreshRates.map { rate -> (rate * MILLI_HERTZ_PER_HERTZ).roundToInt() }
            } else {
                emptyList()
            },
    )

private fun Double?.isUsableContentFrameRate(): Boolean = this != null && isFinite() && this > 0.0 && this <= MAX_CONTENT_FRAME_RATE_HZ

internal const val DISPLAY_MODE_SWITCH_TIMEOUT_MS = 5_000L
internal const val NON_SEAMLESS_SWITCH_SETTLE_MS = 250L
private const val DEFAULT_DISPLAY_MODE_ID = 0
private const val MILLI_HERTZ_PER_HERTZ = 1_000f
