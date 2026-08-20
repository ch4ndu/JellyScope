// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.util.safeDiagnosticType

/** Closed platform values persisted in the previous-run marker. */
enum class PreviousRunFailurePlatform(
    val wireValue: String,
) {
    Android("android"),
    AndroidTv("android-tv"),
    Ios("ios"),
    TvOs("tvos"),
    Desktop("desktop"),
}

/** Identity-free evidence from one uncaught Kotlin/JVM or Kotlin/Native failure. */
data class PreviousRunFailureMarker(
    val exceptionType: String,
    val platform: PreviousRunFailurePlatform,
)

/** Synchronous, best-effort persistence used from uncaught-exception boundaries. */
interface PreviousRunFailureStore {
    fun write(
        throwable: Throwable,
        platform: PreviousRunFailurePlatform,
    )

    fun consume(): PreviousRunFailureMarker?

    fun clear()
}

object NoOpPreviousRunFailureStore : PreviousRunFailureStore {
    override fun write(
        throwable: Throwable,
        platform: PreviousRunFailurePlatform,
    ) = Unit

    override fun consume(): PreviousRunFailureMarker? = null

    override fun clear() = Unit
}

internal const val PREVIOUS_RUN_FAILURE_SCHEMA_VERSION = 1

internal fun previousRunFailureMarker(
    throwable: Throwable,
    platform: PreviousRunFailurePlatform,
): PreviousRunFailureMarker =
    PreviousRunFailureMarker(
        exceptionType = throwable.safeDiagnosticType(),
        platform = platform,
    )

internal fun decodePreviousRunFailureMarker(
    schemaVersion: Int?,
    exceptionType: String?,
    platform: String?,
): PreviousRunFailureMarker? {
    if (schemaVersion != PREVIOUS_RUN_FAILURE_SCHEMA_VERSION) return null
    val safeType = exceptionType?.let(::safeStoredDiagnosticType) ?: return null
    val safePlatform = PreviousRunFailurePlatform.entries.firstOrNull { value -> value.wireValue == platform } ?: return null
    return PreviousRunFailureMarker(exceptionType = safeType, platform = safePlatform)
}

private fun safeStoredDiagnosticType(value: String): String? =
    value
        .takeIf { candidate ->
            candidate.length in 1..80 &&
                (candidate.first().isLetter() || candidate.first() == '_') &&
                candidate.all { character -> character.isLetterOrDigit() || character == '_' || character == '.' }
        }
