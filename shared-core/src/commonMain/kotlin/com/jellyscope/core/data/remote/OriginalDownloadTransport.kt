// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import io.ktor.http.fromHttpToGmtDate
import io.ktor.utils.io.ByteReadChannel

/**
 * Stable facts established by the authenticated Original-download preflight.
 * The URL and credential used to establish them are deliberately not retained.
 */
data class OriginalDownloadSource(
    val itemId: String,
    val mediaSourceId: String,
    val totalBytes: Long,
    val lastModified: String,
    val audioStreamIndices: Set<Int> = emptySet(),
    val embeddedSubtitleStreamIndices: Set<Int> = emptySet(),
    val externalSubtitleStreamIndices: Set<Int> = emptySet(),
    val unsupportedExternalSubtitleStreamIndices: Set<Int> = emptySet(),
) {
    init {
        require(itemId.isNotBlank()) { "itemId must not be blank." }
        require(mediaSourceId.isNotBlank()) { "mediaSourceId must not be blank." }
        require(totalBytes > 0L) { "totalBytes must be positive." }
        require(lastModified.isSafeValidator()) { "lastModified must be a bounded HTTP validator." }
        require(audioStreamIndices.all { index -> index >= 0 }) { "Audio stream indices must be non-negative." }
        require(embeddedSubtitleStreamIndices.all { index -> index >= 0 }) { "Subtitle stream indices must be non-negative." }
        require(externalSubtitleStreamIndices.all { index -> index >= 0 }) { "Subtitle stream indices must be non-negative." }
        require(unsupportedExternalSubtitleStreamIndices.all { index -> index >= 0 }) {
            "Subtitle stream indices must be non-negative."
        }
        require(externalSubtitleStreamIndices.intersect(unsupportedExternalSubtitleStreamIndices).isEmpty()) {
            "Supported and unsupported subtitle stream indices must be disjoint."
        }
    }
}

sealed interface OriginalDownloadPreflightResult {
    data class Ready(
        val source: OriginalDownloadSource,
    ) : OriginalDownloadPreflightResult

    data class Rejected(
        val failure: OriginalDownloadFailure,
    ) : OriginalDownloadPreflightResult
}

sealed interface OriginalDownloadStreamResult<out T> {
    data class Success<T>(
        val value: T,
    ) : OriginalDownloadStreamResult<T>

    data class Rejected(
        val failure: OriginalDownloadFailure,
    ) : OriginalDownloadStreamResult<Nothing>
}

/** Fixed transport outcomes; no server text, URL, credential, or identifier is retained. */
enum class OriginalDownloadFailure {
    AccountUnauthorized,
    PermissionDenied,
    SourceUnavailable,
    SizeUnavailable,
    SourceChanged,
    Network,
    ServerUnavailable,
}

/**
 * A bounded-lifetime view of the response body. It is valid only while the
 * consumer passed to [JellyfinApi.streamOriginalDownload] is running.
 *
 * Calling [close] is safe before reading any bytes. Returning from the consumer
 * also closes the underlying response, so an early-return path cannot leak it.
 */
class OriginalDownloadStream internal constructor(
    val source: OriginalDownloadSource,
    val startByte: Long,
    val endByteInclusive: Long,
    val body: ByteReadChannel,
) {
    fun close() {
        body.cancel(null)
    }
}

internal fun String.isSafeValidator(): Boolean =
    isNotBlank() &&
        length <= MAX_ORIGINAL_VALIDATOR_LENGTH &&
        all { character -> character.code in HTTP_VISIBLE_ASCII_RANGE || character == ' ' } &&
        runCatching { fromHttpToGmtDate() }.isSuccess

private const val MAX_ORIGINAL_VALIDATOR_LENGTH = 256
private val HTTP_VISIBLE_ASCII_RANGE = 0x21..0x7e
