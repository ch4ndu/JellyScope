// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

import co.touchlab.kermit.Severity
import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import com.jellyscope.core.data.local.PreviousRunFailureMarker
import com.jellyscope.core.data.local.PreviousRunFailurePlatform
import com.jellyscope.core.data.local.PreviousRunFailureStore

/** Installs the platform's process-level uncaught Kotlin exception hook once. */
expect fun installPreviousRunFailureHandler(
    store: PreviousRunFailureStore,
    platform: PreviousRunFailurePlatform,
)

/** Consumes one marker only when safe collection is enabled. */
fun consumePreviousRunFailure(
    store: PreviousRunFailureStore,
    preferenceStore: LogCollectionPreferenceStore,
    logBufferStore: LogBufferStore,
) {
    if (!preferenceStore.enabled.value) {
        runCatching { store.clear() }
        return
    }

    val marker = runCatching { store.consume() }.getOrNull() ?: return
    logBufferStore.append(
        tag = DiagnosticTag.PreviousRunFailure.wireValue,
        severity = Severity.Warn,
        message = formatPreviousRunFailureDiagnostic(marker),
    )
}

internal fun formatPreviousRunFailureDiagnostic(marker: PreviousRunFailureMarker): String =
    formatSafeFailureDiagnostic(
        stage = "process-exit",
        event = "unhandled",
        operation = DiagnosticOperation.PreviousRunFailure,
        exceptionType = marker.exceptionType,
    ) + " platform=${marker.platform.wireValue}"
