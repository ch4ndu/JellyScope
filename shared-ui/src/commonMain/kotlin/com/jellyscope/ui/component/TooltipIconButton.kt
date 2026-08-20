// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.jellyscope.ui.platform.LocalPlatformCapabilities
import com.jellyscope.ui.theme.Dimensions
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * An accessible icon button that adds the Material tooltip affordance on desktop only.
 * The icon content must be decorative because [label] owns the button semantics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TooltipIconButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    diagnosticTarget: InputDiagnosticTarget? = null,
    icon: @Composable () -> Unit,
) {
    val diagnosticsSink = LocalInputDiagnosticsSink.current
    val desktopTooltipsEnabled = LocalPlatformCapabilities.current.desktopScrollInput
    // Keep the event sources installed for the button lifetime. The desktop
    // sink owns the dynamic Settings/CLI gate, so enabling collection later
    // does not require reconstructing this composition.
    val diagnosticsInstalled = diagnosticTarget != null && desktopTooltipsEnabled
    val diagnosticInteractionSource =
        if (diagnosticsInstalled) {
            remember { MutableInteractionSource() }
        } else {
            null
        }
    val diagnosticCoordinatesState =
        if (diagnosticsInstalled) {
            remember { mutableStateOf<LayoutCoordinates?>(null) }
        } else {
            null
        }
    val diagnosticDensityState =
        if (diagnosticsInstalled) {
            rememberUpdatedState(LocalDensity.current.density)
        } else {
            null
        }
    val diagnosticSinkState =
        if (diagnosticsInstalled) {
            rememberUpdatedState(diagnosticsSink)
        } else {
            null
        }
    val diagnosticTargetState =
        if (diagnosticsInstalled) {
            rememberUpdatedState(diagnosticTarget)
        } else {
            null
        }

    if (
        diagnosticInteractionSource != null &&
        diagnosticCoordinatesState != null &&
        diagnosticDensityState != null &&
        diagnosticSinkState != null &&
        diagnosticTargetState != null
    ) {
        LaunchedEffect(diagnosticInteractionSource) {
            diagnosticInteractionSource.interactions.collect { interaction ->
                if (!diagnosticSinkState.value.isEnabled) return@collect
                val event =
                    when (interaction) {
                        is PressInteraction.Press -> InputDiagnosticEvent.PRESS
                        is PressInteraction.Release -> InputDiagnosticEvent.RELEASE
                        is PressInteraction.Cancel -> InputDiagnosticEvent.CANCEL
                        else -> null
                    } ?: return@collect
                val target = diagnosticTargetState.value
                val windowPress =
                    (interaction as? PressInteraction.Press)?.pressPosition?.let { pressPosition ->
                        diagnosticCoordinatesState.value?.localToWindow(pressPosition)
                    }
                val sink = diagnosticSinkState.value
                sink.record(
                    target = target,
                    event = event,
                    composeWindowX = windowPress?.x,
                    composeWindowY = windowPress?.y,
                    composeDensity = diagnosticDensityState.value,
                )
            }
        }
    }

    val tooltipState = if (desktopTooltipsEnabled) rememberTooltipState() else null
    if (
        diagnosticsInstalled &&
        desktopTooltipsEnabled &&
        tooltipState != null &&
        diagnosticSinkState != null &&
        diagnosticTargetState != null
    ) {
        LaunchedEffect(tooltipState) {
            snapshotFlow { tooltipState.isVisible }
                .distinctUntilChanged()
                .collect { visible ->
                    if (!diagnosticSinkState.value.isEnabled) return@collect
                    val target = diagnosticTargetState.value
                    diagnosticSinkState.value.record(
                        target = target,
                        event =
                            if (visible) {
                                InputDiagnosticEvent.TOOLTIP_SHOWN
                            } else {
                                InputDiagnosticEvent.TOOLTIP_HIDDEN
                            },
                    )
                }
        }
    }

    val button: @Composable (Modifier) -> Unit = { buttonModifier ->
        val diagnosticModifier =
            if (diagnosticCoordinatesState != null) {
                buttonModifier.onGloballyPositioned { coordinates ->
                    if (diagnosticCoordinatesState.value != coordinates) {
                        diagnosticCoordinatesState.value = coordinates
                    }
                }
            } else {
                buttonModifier
            }
        IconButton(
            onClick = {
                if (diagnosticsInstalled && diagnosticsSink.isEnabled) {
                    diagnosticsSink.record(diagnosticTarget, InputDiagnosticEvent.CLICK)
                }
                onClick()
            },
            enabled = enabled,
            modifier =
                diagnosticModifier
                    .size(Dimensions.minTouchTarget)
                    .semantics { contentDescription = label },
            interactionSource = diagnosticInteractionSource,
        ) {
            Box(modifier = Modifier.clearAndSetSemantics {}, content = { icon() })
        }
    }

    if (desktopTooltipsEnabled && tooltipState != null) {
        TooltipBox(
            modifier = modifier,
            positionProvider =
                TooltipDefaults.rememberTooltipPositionProvider(
                    positioning = TooltipAnchorPosition.Above,
                ),
            tooltip = { PlainTooltip { Text(label) } },
            state = tooltipState,
        ) {
            button(Modifier)
        }
    } else {
        button(modifier)
    }
}
