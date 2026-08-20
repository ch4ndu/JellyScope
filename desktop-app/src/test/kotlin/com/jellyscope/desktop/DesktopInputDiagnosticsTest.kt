// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.desktop

import androidx.compose.ui.window.WindowPlacement
import com.jellyscope.ui.component.InputDiagnosticEvent
import com.jellyscope.ui.component.InputDiagnosticTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopInputDiagnosticsTest {
    @Test
    fun normalizesDifferentCoordinateRootsWithDensityAppliedOnce() {
        val result =
            normalizeInputCoordinates(
                compose =
                    DesktopInputCoordinate(
                        space = DesktopInputCoordinateSpace.COMPOSE_LOCAL,
                        x = 10f,
                        y = 40f,
                        density = 2f,
                        originX = 100f,
                        originY = 30f,
                    ),
                awt =
                    DesktopInputCoordinate(
                        space = DesktopInputCoordinateSpace.AWT_COMPONENT_LOCAL,
                        x = 20f,
                        y = 140f,
                        density = 1f,
                        originX = 200f,
                        originY = 0f,
                    ),
            )

        val supported = assertIs<DesktopInputCoordinateComparison.Supported>(result)
        assertEquals(DesktopInputWindowPoint(220, 140), supported.composeWindow)
        assertEquals(DesktopInputWindowPoint(220, 140), supported.awtWindow)
        assertEquals(0, supported.offsetX)
        assertEquals(0, supported.offsetY)
    }

    @Test
    fun unsupportedNormalizationHasNoOffsetConclusion() {
        val result =
            normalizeInputCoordinates(
                compose =
                    DesktopInputCoordinate(
                        space = DesktopInputCoordinateSpace.COMPOSE_WINDOW_LOGICAL,
                        x = 100f,
                        y = 50f,
                    ),
                awt =
                    DesktopInputCoordinate(
                        space = DesktopInputCoordinateSpace.AWT_COMPONENT_LOCAL,
                        x = 20f,
                        y = 10f,
                        density = 1f,
                    ),
            )

        val unsupported = assertIs<DesktopInputCoordinateComparison.Unsupported>(result)
        assertEquals(DesktopInputCoordinateSpace.COMPOSE_WINDOW_LOGICAL, unsupported.composeSpace)
        assertEquals(DesktopInputCoordinateSpace.AWT_COMPONENT_LOCAL, unsupported.awtSpace)
    }

    @Test
    fun dynamicGateTakesEffectWithoutRecreatingTheSink() {
        var enabled = false
        val output = mutableListOf<String>()
        val diagnostics =
            DesktopInputDiagnostics(
                enabled = { enabled },
                output = { record -> output += record.serialized },
            )

        diagnostics.record(InputDiagnosticTarget.BACK, InputDiagnosticEvent.PRESS)
        assertTrue(output.isEmpty())

        enabled = true
        diagnostics.record(InputDiagnosticTarget.BACK, InputDiagnosticEvent.PRESS)
        assertEquals(1, output.size)

        enabled = false
        diagnostics.recordWindowFocus(active = false, focused = false)
        assertEquals(1, output.size)
    }

    @Test
    fun diagnosticOutputUsesOnlyAllowlistedPrimitiveFields() {
        val output = mutableListOf<String>()
        val diagnostics =
            DesktopInputDiagnostics(
                isEnabled = true,
                nowNanos = { 17L },
                output = output::add,
            )

        diagnostics.record(
            target = InputDiagnosticTarget.SETTINGS,
            event = InputDiagnosticEvent.CLICK,
        )
        diagnostics.recordWindowPlacement(
            placement = WindowPlacement.Fullscreen,
            active = true,
            focused = false,
        )

        assertTrue(output[0].contains("event=input"))
        assertTrue(output[0].contains("target=SETTINGS inputEvent=CLICK"))
        assertTrue(output[1].contains("event=window windowEvent=PLACEMENT placement=FULLSCREEN active=true focused=false"))
        assertTrue(
            output.none { line ->
                listOf("label=", "title=", "url=", "path=", "/").any(line::contains)
            },
        )
    }

    @Test
    fun unsupportedRuntimeComparisonUsesSentinelsInsteadOfInventingAnOffset() {
        val output = mutableListOf<String>()
        val diagnostics = DesktopInputDiagnostics(isEnabled = true, output = output::add)

        diagnostics.record(
            target = InputDiagnosticTarget.BACK,
            event = InputDiagnosticEvent.PRESS,
            composeWindowX = 10f,
            composeWindowY = 20f,
            composeDensity = 2f,
        )

        assertTrue(output.single().contains("comparison=UNSUPPORTED"))
        assertTrue(output.single().contains("coordinateSpace=COMPOSE_WINDOW_LOGICAL"))
        assertTrue(output.single().contains("offsetX=-1"))
        assertTrue(output.single().contains("offsetY=-1"))
    }
}
