// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

/**
 * Formats a failure without exposing throwable messages, causes, or stack traces.
 * Stage and event are constrained to the same token grammar used by client diagnostics.
 */
fun formatSafeFailureDiagnostic(
    stage: String,
    event: String,
    throwable: Throwable,
): String =
    renderSafeFailureDiagnostic(
        stage = stage,
        event = event,
        operation = null,
        throwable = throwable,
    )

/** Formats a failure with a closed operation token in stable wire-field order. */
fun formatSafeFailureDiagnostic(
    stage: String,
    event: String,
    operation: DiagnosticOperation,
    throwable: Throwable,
): String =
    renderSafeFailureDiagnostic(
        stage = stage,
        event = event,
        operation = operation.wireValue,
        throwable = throwable,
    )

/** Internal bridge for a previously sanitized marker exception token. */
internal fun formatSafeFailureDiagnostic(
    stage: String,
    event: String,
    operation: DiagnosticOperation,
    exceptionType: String,
): String =
    renderSafeFailureDiagnostic(
        stage = stage,
        event = event,
        operation = operation.wireValue,
        exceptionType = exceptionType.safeDiagnosticTypeToken(),
    )

private fun renderSafeFailureDiagnostic(
    stage: String,
    event: String,
    operation: String?,
    throwable: Throwable,
): String =
    renderSafeFailureDiagnostic(
        stage = stage,
        event = event,
        operation = operation,
        exceptionType = throwable.safeDiagnosticType(),
    )

private fun renderSafeFailureDiagnostic(
    stage: String,
    event: String,
    operation: String?,
    exceptionType: String,
): String =
    buildString {
        append("stage=${stage.safeDiagnosticLabel()} ")
        append("event=${event.safeDiagnosticLabel()}")
        operation?.let { value -> append(" operation=$value") }
        append(" exceptionType=$exceptionType")
    }

fun Throwable.safeDiagnosticType(): String = this::class.simpleName.safeDiagnosticTypeToken()

private fun String?.safeDiagnosticTypeToken(): String =
    this
        ?.takeIf { value ->
            value.length in 1..80 &&
                (value.first().isLetter() || value.first() == '_') &&
                value.all { character -> character.isLetterOrDigit() || character == '_' || character == '.' }
        }
        ?: UNKNOWN_DIAGNOSTIC_VALUE

private fun String.safeDiagnosticLabel(): String =
    takeIf { value ->
        value.length in 1..80 &&
            value.first().isLetter() &&
            value.all { character ->
                character.isLetterOrDigit() || character == '_' || character == '.' || character == '-'
            }
    } ?: UNKNOWN_DIAGNOSTIC_VALUE.lowercase()

private const val UNKNOWN_DIAGNOSTIC_VALUE = "Unknown"
