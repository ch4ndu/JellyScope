// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import androidx.compose.runtime.staticCompositionLocalOf

enum class InputDiagnosticTarget {
    BACK,
    SETTINGS,
}

enum class InputDiagnosticEvent {
    PRESS,
    RELEASE,
    CANCEL,
    CLICK,
    TOOLTIP_SHOWN,
    TOOLTIP_HIDDEN,
}

interface InputDiagnosticsSink {
    val isEnabled: Boolean
        get() = false

    fun record(
        target: InputDiagnosticTarget,
        event: InputDiagnosticEvent,
        composeWindowX: Float? = null,
        composeWindowY: Float? = null,
        composeDensity: Float? = null,
    ) {}
}

private object NoOpInputDiagnosticsSink : InputDiagnosticsSink

val LocalInputDiagnosticsSink = staticCompositionLocalOf<InputDiagnosticsSink> { NoOpInputDiagnosticsSink }
