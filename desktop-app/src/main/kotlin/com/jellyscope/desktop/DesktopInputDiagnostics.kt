// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.desktop

import androidx.compose.ui.window.WindowPlacement
import com.jellyscope.core.playback.DESKTOP_PLAYBACK_PROBE_PROPERTY
import com.jellyscope.core.playback.DesktopInputProbeRecord
import com.jellyscope.core.playback.DesktopPlaybackProbe
import com.jellyscope.core.playback.DesktopPlaybackProbeRecord
import com.jellyscope.core.playback.DesktopProbeToken
import com.jellyscope.core.playback.DesktopWindowProbeRecord
import com.jellyscope.ui.component.InputDiagnosticEvent
import com.jellyscope.ui.component.InputDiagnosticTarget
import com.jellyscope.ui.component.InputDiagnosticsSink
import java.awt.Component
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val DESKTOP_INPUT_DIAGNOSTICS_PROPERTY = DESKTOP_PLAYBACK_PROBE_PROPERTY

internal enum class DesktopInputSource {
    COMPOSE,
    AWT,
    SHELL,
}

internal enum class DesktopInputShellEvent {
    PLACEMENT,
    ACTIVATION,
    FOCUS,
}

internal enum class DesktopInputWindowPlacement {
    FLOATING,
    MAXIMIZED,
    FULLSCREEN,
}

internal enum class DesktopInputCoordinateSpace {
    COMPOSE_LOCAL,
    COMPOSE_WINDOW_LOGICAL,
    AWT_COMPONENT_LOCAL,
    AWT_WINDOW_LOGICAL,
    WINDOW_PIXELS,
    COMPOSE_UNAVAILABLE,
    AWT_UNAVAILABLE,
    AWT_MULTIPLE_PENDING,
}

internal data class DesktopInputCoordinate(
    val space: DesktopInputCoordinateSpace,
    val x: Float,
    val y: Float,
    val density: Float? = null,
    val originX: Float? = null,
    val originY: Float? = null,
)

internal data class DesktopInputWindowPoint(
    val x: Int,
    val y: Int,
)

internal sealed interface DesktopInputCoordinateComparison {
    data class Supported(
        val composeWindow: DesktopInputWindowPoint,
        val awtWindow: DesktopInputWindowPoint,
        val offsetX: Int,
        val offsetY: Int,
    ) : DesktopInputCoordinateComparison

    data class Unsupported(
        val composeSpace: DesktopInputCoordinateSpace,
        val awtSpace: DesktopInputCoordinateSpace,
    ) : DesktopInputCoordinateComparison
}

internal fun normalizeInputCoordinates(
    compose: DesktopInputCoordinate,
    awt: DesktopInputCoordinate,
): DesktopInputCoordinateComparison {
    val composeWindow = compose.toWindowPixels()
    val awtWindow = awt.toWindowPixels()
    if (composeWindow == null || awtWindow == null) {
        return DesktopInputCoordinateComparison.Unsupported(
            composeSpace = compose.space,
            awtSpace = awt.space,
        )
    }
    return DesktopInputCoordinateComparison.Supported(
        composeWindow = composeWindow,
        awtWindow = awtWindow,
        offsetX = awtWindow.x - composeWindow.x,
        offsetY = awtWindow.y - composeWindow.y,
    )
}

private fun DesktopInputCoordinate.toWindowPixels(): DesktopInputWindowPoint? {
    val coordinate =
        when (space) {
            DesktopInputCoordinateSpace.COMPOSE_LOCAL,
            DesktopInputCoordinateSpace.AWT_COMPONENT_LOCAL,
            -> {
                val rootX = originX ?: return null
                val rootY = originY ?: return null
                Triple(rootX + x, rootY + y, density)
            }

            DesktopInputCoordinateSpace.COMPOSE_WINDOW_LOGICAL,
            DesktopInputCoordinateSpace.AWT_WINDOW_LOGICAL,
            -> Triple(x, y, density)

            DesktopInputCoordinateSpace.WINDOW_PIXELS -> Triple(x, y, 1f)
            DesktopInputCoordinateSpace.COMPOSE_UNAVAILABLE,
            DesktopInputCoordinateSpace.AWT_UNAVAILABLE,
            DesktopInputCoordinateSpace.AWT_MULTIPLE_PENDING,
            -> return null
        }
    val density = coordinate.third
    if (density != null && (!density.isFinite() || density <= 0f)) return null
    val scale = density ?: return null
    return DesktopInputWindowPoint(
        x = (coordinate.first * scale).toRoundedIntOrNull() ?: return null,
        y = (coordinate.second * scale).toRoundedIntOrNull() ?: return null,
    )
}

private fun Float.toRoundedIntOrNull(): Int? {
    if (!isFinite() || this < Int.MIN_VALUE || this > Int.MAX_VALUE) return null
    return roundToInt()
}

internal class DesktopInputDiagnostics(
    private val enabled: () -> Boolean = { DesktopPlaybackProbe.isEnabled },
    private val nowNanos: () -> Long = System::nanoTime,
    private val output: (DesktopPlaybackProbeRecord) -> Unit = DesktopPlaybackProbe::emit,
) : InputDiagnosticsSink {
    internal constructor(
        isEnabled: Boolean = DesktopPlaybackProbe.isEnabled,
        nowNanos: () -> Long = System::nanoTime,
        output: (String) -> Unit,
    ) : this(
        enabled = { isEnabled },
        nowNanos = nowNanos,
        output = { record -> output(record.serialized) },
    )

    override val isEnabled: Boolean
        get() = enabled()

    private val lock = Any()
    private var nextSequence = 0L
    private val pendingAwtPresses = mutableListOf<PendingAwtPress>()
    private val pendingComposePresses = mutableListOf<PendingComposePress>()

    override fun record(
        target: InputDiagnosticTarget,
        event: InputDiagnosticEvent,
        composeWindowX: Float?,
        composeWindowY: Float?,
        composeDensity: Float?,
    ) {
        if (!isEnabled) return
        val timestampNanos = nowNanos()
        synchronized(lock) {
            prunePending(timestampNanos)
            val composeCoordinate =
                composeCoordinate(
                    x = composeWindowX,
                    y = composeWindowY,
                    density = composeDensity,
                )
            var comparison: DesktopInputCoordinateComparison =
                DesktopInputCoordinateComparison.Unsupported(
                    composeSpace = composeCoordinate.space,
                    awtSpace = DesktopInputCoordinateSpace.AWT_UNAVAILABLE,
                )
            if (event == InputDiagnosticEvent.PRESS) {
                val candidates = pendingAwtPresses.toList()
                var matchedAwtPress = false
                comparison =
                    when (candidates.size) {
                        1 -> {
                            pendingAwtPresses.remove(candidates.first())
                            matchedAwtPress = true
                            normalizeInputCoordinates(composeCoordinate, candidates.first().coordinate)
                        }

                        else ->
                            DesktopInputCoordinateComparison.Unsupported(
                                composeSpace = composeCoordinate.space,
                                awtSpace =
                                    if (candidates.isEmpty()) {
                                        DesktopInputCoordinateSpace.AWT_UNAVAILABLE
                                    } else {
                                        DesktopInputCoordinateSpace.AWT_MULTIPLE_PENDING
                                    },
                            )
                    }
                if (!matchedAwtPress) {
                    pendingComposePresses += PendingComposePress(timestampNanos, target, composeCoordinate)
                }
            }
            emitLocked(
                timestampNanos = timestampNanos,
                source = DesktopInputSource.COMPOSE,
                target = target,
                event = event,
                coordinate = composeCoordinate,
                comparison = comparison,
            )
        }
    }

    internal fun recordAwt(
        event: InputDiagnosticEvent,
        coordinate: DesktopInputCoordinate,
    ) {
        if (!isEnabled) return
        if (event != InputDiagnosticEvent.PRESS && event != InputDiagnosticEvent.RELEASE) return
        val timestampNanos = nowNanos()
        synchronized(lock) {
            prunePending(timestampNanos)
            var target: InputDiagnosticTarget? = null
            var comparison: DesktopInputCoordinateComparison =
                DesktopInputCoordinateComparison.Unsupported(
                    composeSpace = DesktopInputCoordinateSpace.COMPOSE_UNAVAILABLE,
                    awtSpace = coordinate.space,
                )
            if (event == InputDiagnosticEvent.PRESS) {
                val candidates = pendingComposePresses.toList()
                var matchedComposePress = false
                comparison =
                    when (candidates.size) {
                        1 -> {
                            val candidate = candidates.first()
                            pendingComposePresses.remove(candidate)
                            matchedComposePress = true
                            target = candidate.target
                            normalizeInputCoordinates(candidate.coordinate, coordinate)
                        }

                        else ->
                            DesktopInputCoordinateComparison.Unsupported(
                                composeSpace =
                                    if (candidates.isEmpty()) {
                                        DesktopInputCoordinateSpace.COMPOSE_UNAVAILABLE
                                    } else {
                                        candidates.first().coordinate.space
                                    },
                                awtSpace =
                                    if (candidates.size > 1) {
                                        DesktopInputCoordinateSpace.AWT_MULTIPLE_PENDING
                                    } else {
                                        coordinate.space
                                    },
                            )
                    }
                if (!matchedComposePress) {
                    pendingAwtPresses += PendingAwtPress(timestampNanos, coordinate)
                }
            }
            emitLocked(
                timestampNanos = timestampNanos,
                source = DesktopInputSource.AWT,
                target = target,
                event = event,
                coordinate = coordinate,
                comparison = comparison,
            )
        }
    }

    internal fun recordWindowPlacement(
        placement: WindowPlacement,
        active: Boolean,
        focused: Boolean,
    ) {
        if (!isEnabled) return
        val timestampNanos = nowNanos()
        synchronized(lock) {
            output(
                DesktopWindowProbeRecord(
                    windowEvent = DesktopProbeToken.from(DesktopInputShellEvent.PLACEMENT.name),
                    placement = DesktopProbeToken.from(placement.toDiagnosticPlacement().name),
                    active = active,
                    focused = focused,
                ),
            )
        }
    }

    internal fun recordWindowActivation(
        active: Boolean,
        focused: Boolean,
    ) = recordWindowState(DesktopInputShellEvent.ACTIVATION, active, focused)

    internal fun recordWindowFocus(
        active: Boolean,
        focused: Boolean,
    ) = recordWindowState(DesktopInputShellEvent.FOCUS, active, focused)

    private fun recordWindowState(
        shellEvent: DesktopInputShellEvent,
        active: Boolean,
        focused: Boolean,
    ) {
        if (!isEnabled) return
        val timestampNanos = nowNanos()
        synchronized(lock) {
            output(
                DesktopWindowProbeRecord(
                    windowEvent = DesktopProbeToken.from(shellEvent.name),
                    placement = DesktopProbeToken.from("unchanged"),
                    active = active,
                    focused = focused,
                ),
            )
        }
    }

    internal fun createAwtEventListener(window: Window): AWTEventListener =
        AWTEventListener { event ->
            if (!isEnabled) return@AWTEventListener
            val mouseEvent = event as? MouseEvent ?: return@AWTEventListener
            val inputEvent =
                when (mouseEvent.id) {
                    MouseEvent.MOUSE_PRESSED -> InputDiagnosticEvent.PRESS
                    MouseEvent.MOUSE_RELEASED -> InputDiagnosticEvent.RELEASE
                    else -> return@AWTEventListener
                }
            val component = mouseEvent.component ?: return@AWTEventListener
            val eventWindow =
                if (component is Window) {
                    component
                } else {
                    SwingUtilities.getWindowAncestor(component)
                }
            if (eventWindow !== window || !window.isShowing) return@AWTEventListener
            val root = (window as? RootPaneContainer)?.contentPane ?: window
            val density = root.desktopInputDensity()
            val convertedPoint =
                runCatching {
                    SwingUtilities.convertPoint(component, mouseEvent.point, root)
                }.getOrNull()
            val coordinate =
                convertedPoint?.let { point ->
                    DesktopInputCoordinate(
                        space = DesktopInputCoordinateSpace.AWT_WINDOW_LOGICAL,
                        x = point.x.toFloat(),
                        y = point.y.toFloat(),
                        density = density,
                    )
                } ?: DesktopInputCoordinate(
                    space = DesktopInputCoordinateSpace.AWT_COMPONENT_LOCAL,
                    x = mouseEvent.x.toFloat(),
                    y = mouseEvent.y.toFloat(),
                    density = density,
                )
            recordAwt(inputEvent, coordinate)
        }

    private fun prunePending(nowNanos: Long) {
        pendingAwtPresses.removeAll { pending -> nowNanos - pending.timestampNanos > CORRELATION_WINDOW_NANOS }
        pendingComposePresses.removeAll { pending -> nowNanos - pending.timestampNanos > CORRELATION_WINDOW_NANOS }
    }

    private fun emitLocked(
        timestampNanos: Long,
        source: DesktopInputSource,
        target: InputDiagnosticTarget? = null,
        event: InputDiagnosticEvent,
        coordinate: DesktopInputCoordinate,
        comparison: DesktopInputCoordinateComparison,
    ) {
        nextSequence += 1
        val supported = comparison as? DesktopInputCoordinateComparison.Supported
        output(
            DesktopInputProbeRecord(
                sequence = nextSequence,
                timeNanos = timestampNanos,
                source = DesktopProbeToken.from(source.name),
                target = DesktopProbeToken.from(target?.name),
                inputEvent = DesktopProbeToken.from(event.name),
                coordinateSpace = DesktopProbeToken.from(coordinate.space.name),
                x = coordinate.x.toRoundedIntOrNull() ?: -1,
                y = coordinate.y.toRoundedIntOrNull() ?: -1,
                densityMilli = coordinate.density?.times(1_000f)?.toRoundedIntOrNull() ?: -1,
                comparison =
                    DesktopProbeToken.from(
                        if (supported == null) "UNSUPPORTED" else "SUPPORTED",
                    ),
                offsetX = supported?.offsetX ?: -1,
                offsetY = supported?.offsetY ?: -1,
            ),
        )
    }

    private fun composeCoordinate(
        x: Float?,
        y: Float?,
        density: Float?,
    ): DesktopInputCoordinate {
        if (x == null || y == null) {
            return DesktopInputCoordinate(
                space = DesktopInputCoordinateSpace.COMPOSE_UNAVAILABLE,
                x = x ?: Float.NaN,
                y = y ?: Float.NaN,
                density = density,
            )
        }
        val validDensity = density?.takeIf { value -> value.isFinite() && value > 0f }
        return DesktopInputCoordinate(
            space = DesktopInputCoordinateSpace.COMPOSE_WINDOW_LOGICAL,
            x = if (validDensity == null) x else x / validDensity,
            y = if (validDensity == null) y else y / validDensity,
            density = density,
        )
    }

    private data class PendingAwtPress(
        val timestampNanos: Long,
        val coordinate: DesktopInputCoordinate,
    )

    private data class PendingComposePress(
        val timestampNanos: Long,
        val target: InputDiagnosticTarget,
        val coordinate: DesktopInputCoordinate,
    )
}

private fun WindowPlacement.toDiagnosticPlacement(): DesktopInputWindowPlacement =
    when (this) {
        WindowPlacement.Floating -> DesktopInputWindowPlacement.FLOATING
        WindowPlacement.Maximized -> DesktopInputWindowPlacement.MAXIMIZED
        WindowPlacement.Fullscreen -> DesktopInputWindowPlacement.FULLSCREEN
    }

private fun Component.desktopInputDensity(): Float? {
    val transform = graphicsConfiguration?.defaultTransform ?: return null
    val scaleX = transform.scaleX.toFloat()
    val scaleY = transform.scaleY.toFloat()
    if (!scaleX.isFinite() || !scaleY.isFinite() || scaleX <= 0f || abs(scaleX - scaleY) > 0.001f) {
        return null
    }
    return scaleX
}

private const val CORRELATION_WINDOW_NANOS = 500_000_000L
