// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.LogScrubber
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvDisplayModeControllerTest {
    @Test
    fun registersListenerBeforeRequestingSwitchAndDetectsImmediateCompletion() =
        runTest {
            val platform = FakeTvDisplayModePlatform(applyPreferredModeImmediately = true)
            val controller = TvDisplayModeController(platform = platform, scope = this, nowMs = { testScheduler.currentTime })

            controller.apply(24.0)
            advanceTimeBy(NON_SEAMLESS_SWITCH_SETTLE_MS)
            runCurrent()

            assertEquals(listOf("register", "preferred:2", "unregister"), platform.events)
            assertEquals(TvDisplayModeResult.Switched, controller.telemetry.value.result)
            assertFalse(platform.listenerRegistered)
        }

    @Test
    fun timesOutWhenDisplayNeverReportsRequestedMode() =
        runTest {
            val platform = FakeTvDisplayModePlatform()
            val controller = TvDisplayModeController(platform = platform, scope = this, nowMs = { testScheduler.currentTime })

            controller.apply(24.0)
            advanceTimeBy(DISPLAY_MODE_SWITCH_TIMEOUT_MS)
            runCurrent()

            assertEquals(TvDisplayModeResult.TimedOut, controller.telemetry.value.result)
            assertFalse(platform.listenerRegistered)
        }

    @Test
    fun staleCallbacksFromSupersededRequestCannotOverwriteNewTelemetry() =
        runTest {
            val platform = FakeTvDisplayModePlatform()
            val controller = TvDisplayModeController(platform = platform, scope = this, nowMs = { testScheduler.currentTime })

            controller.apply(24.0)
            val staleListener = platform.listener
            controller.apply(25.0)
            staleListener?.invoke()

            assertEquals(
                3,
                controller.telemetry.value.requestedMode
                    ?.id,
            )
            assertEquals(TvDisplayModeResult.Applied, controller.telemetry.value.result)
        }

    @Test
    fun resetSupersessionAndDisposalUnregisterListeners() =
        runTest {
            val platform = FakeTvDisplayModePlatform()
            val controller = TvDisplayModeController(platform = platform, scope = this, nowMs = { testScheduler.currentTime })

            controller.apply(24.0)
            controller.apply(25.0)
            controller.reset()
            controller.apply(24.0)
            controller.dispose()

            assertFalse(platform.listenerRegistered)
            assertEquals(0, platform.requestedDisplayModeId)
            assertTrue(platform.unregisterCalls >= 3)
        }

    @Test
    fun currentModeWinningOnFallbackTierPublishesAppliedWithThatTier() =
        runTest {
            val platform = FakeTvDisplayModePlatform()
            val controller = TvDisplayModeController(platform = platform, scope = this, nowMs = { testScheduler.currentTime })

            controller.apply(30.0)

            assertEquals(TvDisplayModeResult.Applied, controller.telemetry.value.result)
            assertEquals(TvDisplayModeMatchTier.IntegerMultiple, controller.telemetry.value.tier)
            assertEquals(
                1,
                controller.telemetry.value.requestedMode
                    ?.id,
            )
            assertEquals(-1, platform.requestedDisplayModeId)
        }

    @Test
    fun invalidApplyAndResetShareCleanupButPublishDifferentStates() =
        runTest {
            val platform = FakeTvDisplayModePlatform()
            val controller = TvDisplayModeController(platform = platform, scope = this, nowMs = { testScheduler.currentTime })

            controller.apply(null)

            assertEquals(TvDisplayModeResult.Unavailable, controller.telemetry.value.result)
            assertEquals(0, platform.requestedDisplayModeId)
            assertEquals(1, platform.restorePlayerStrategyCalls)

            controller.reset()

            assertEquals(TvDisplayModeResult.Off, controller.telemetry.value.result)
            assertEquals(0, platform.requestedDisplayModeId)
            assertEquals(2, platform.restorePlayerStrategyCalls)
        }

    @Test
    fun appliedTelemetryProjectsToAnAdmittedIdentityFreeDisplayRecord() =
        runTest {
            val platform = FakeTvDisplayModePlatform(applyPreferredModeImmediately = true)
            val controller = TvDisplayModeController(platform = platform, scope = this, nowMs = { testScheduler.currentTime })
            val captured = mutableListOf<Triple<Severity, String, Throwable?>>()
            val writer =
                object : LogWriter() {
                    override fun log(
                        severity: Severity,
                        message: String,
                        tag: String,
                        throwable: Throwable?,
                    ) {
                        if (tag == DiagnosticTag.TvDisplayMode.wireValue) {
                            captured += Triple(severity, message, throwable)
                        }
                    }
                }

            try {
                Logger.setLogWriters(listOf(writer))
                controller.apply(24.0)
                advanceTimeBy(NON_SEAMLESS_SWITCH_SETTLE_MS)
                runCurrent()
            } finally {
                Logger.setLogWriters(emptyList())
            }

            val telemetry = controller.telemetry.value
            assertEquals(TvDisplayModeResult.Switched, telemetry.result)
            assertEquals(2, captured.size)
            assertEquals(listOf(Severity.Info, Severity.Info), captured.map { (severity, _, _) -> severity })
            captured.forEach { (_, message, throwable) ->
                assertEquals(null, throwable)
                assertEquals(
                    message,
                    LogScrubber.capture(DiagnosticTag.TvDisplayMode.wireValue, message),
                )
                assertFalse(message.contains("modeId"))
                assertFalse(message.contains("displayId"))
            }

            val message = captured.last().second
            assertTrue(message.contains("stage=tvdisplay"))
            assertTrue(message.contains("event=tv-display"))
            assertTrue(message.contains("tvDisplayResult=Switched"))
            assertTrue(message.contains("tvDisplayTier=Exact"))
            assertTrue(message.contains("tvDisplayActiveWidthPx=3840"))
            assertTrue(message.contains("tvDisplayActiveHeightPx=2160"))
            assertTrue(message.contains("tvDisplayActiveRefreshMilliHz=24000"))
            assertTrue(message.contains("tvDisplayRequestedWidthPx=3840"))
            assertTrue(message.contains("tvDisplayRequestedHeightPx=2160"))
            assertTrue(message.contains("tvDisplayRequestedRefreshMilliHz=24000"))
            assertTrue(message.contains("tvDisplaySwitchDurationMs=250"))
        }
}

private class FakeTvDisplayModePlatform(
    private val applyPreferredModeImmediately: Boolean = false,
) : TvDisplayModePlatform {
    private val modes =
        listOf(
            TvDisplayMode(id = 1, width = 3840, height = 2160, refreshRateMilliHz = 60_000),
            TvDisplayMode(id = 2, width = 3840, height = 2160, refreshRateMilliHz = 24_000),
            TvDisplayMode(id = 3, width = 3840, height = 2160, refreshRateMilliHz = 25_000),
        )
    private var activeMode = modes.first()
    var listener: (() -> Unit)? = null
    var listenerRegistered = false
    val events = mutableListOf<String>()
    var requestedDisplayModeId = -1
    var unregisterCalls = 0
    var restorePlayerStrategyCalls = 0

    override fun currentMode(): TvDisplayMode = activeMode

    override fun supportedModes(): List<TvDisplayMode> = modes

    override fun registerDisplayListener(onDisplayChanged: () -> Unit) {
        events += "register"
        listener = onDisplayChanged
        listenerRegistered = true
    }

    override fun unregisterDisplayListener() {
        events += "unregister"
        listener = null
        listenerRegistered = false
        unregisterCalls += 1
    }

    override fun setPreferredDisplayModeId(modeId: Int) {
        events += "preferred:$modeId"
        requestedDisplayModeId = modeId
        if (applyPreferredModeImmediately && modeId != 0) {
            activeMode = modes.first { mode -> mode.id == modeId }
        }
    }

    override fun disablePlayerFrameRateSwitching() = Unit

    override fun restorePlayerFrameRateSwitching() {
        restorePlayerStrategyCalls += 1
    }
}
