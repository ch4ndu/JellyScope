// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.playback.QualityRung
import com.jellyscope.core.domain.playback.qualityRungForBitrate
import io.ktor.utils.io.ByteReadChannel

/**
 * The deliberately small device profile for fixed offline conversion.  It is not the profile
 * used by normal playback: fixed downloads must produce the verified HLS-TS/H.264/AAC VOD
 * subset regardless of the user's selected player backend.
 */
internal fun fixedDownloadDeviceProfile(request: FixedDownloadRequest): PlaybackDeviceProfileDto =
    PlaybackDeviceProfileDto(
        directPlayProfiles = emptyList(),
        transcodingProfiles =
            listOf(
                TranscodingProfileDto(
                    container = "ts",
                    type = "Video",
                    protocol = "hls",
                    videoCodec = "h264",
                    audioCodec = "aac",
                    context = "Streaming",
                    enableSubtitlesInManifest = false,
                    maxBitrate = request.quality.maxBitrateBps,
                ),
            ),
        codecProfiles = emptyList(),
        subtitleProfiles =
            request.subtitleFormat
                ?.let { format ->
                    listOf(SubtitleProfileDto(format = format.trim(), method = "Encode"))
                }.orEmpty(),
        maxStreamingBitrate = request.quality.maxBitrateBps,
        maxStaticBitrate = request.quality.maxBitrateBps,
    )

/**
 * The request used by the fixed-quality offline HLS path.
 *
 * This is deliberately separate from the ordinary playback request.  A fixed download is
 * always an authenticated, finite VOD transcode: direct play/stream and both stream-copy
 * options are disabled by the transport implementation.  The selected URL is never part of
 * this value, so a request can safely cross the admission/database boundary.
 */
data class FixedDownloadRequest(
    val itemId: String,
    val mediaSourceId: String,
    val quality: QualityRung,
    val audioStreamIndex: Int?,
    val subtitleSelection: DownloadSubtitleSelection,
    /** Jellyfin subtitle codec/format, required only for confirmed embedded burn-in. */
    val subtitleFormat: String? = null,
) {
    init {
        require(itemId.isNotBlank()) { "itemId must not be blank." }
        require(mediaSourceId.isNotBlank()) { "mediaSourceId must not be blank." }
        require(qualityRungForBitrate(quality.maxBitrateBps) == quality) {
            "Fixed download quality must use a canonical quality rung."
        }
        require(audioStreamIndex == null || audioStreamIndex >= 0) {
            "Audio stream index must be non-negative when present."
        }
        when (val selection = subtitleSelection) {
            DownloadSubtitleSelection.Off -> Unit
            is DownloadSubtitleSelection.Embedded -> {
                require(selection.burnInConfirmed) {
                    "A fixed download subtitle requires explicit permanent burn-in confirmation."
                }
            }
            is DownloadSubtitleSelection.ExternalTextSidecar,
            is DownloadSubtitleSelection.ExternalServerTextSidecar,
            -> require(false) { "Fixed downloads do not support external subtitle sidecars." }
        }
        require(subtitleFormat == null || subtitleFormat.length <= MAX_FIXED_SUBTITLE_FORMAT_LENGTH) {
            "Fixed download subtitle format must be bounded."
        }
        require(subtitleFormat?.none(Char::isISOControl) != false) {
            "Fixed download subtitle format must not contain control characters."
        }
    }

    val subtitleStreamIndex: Int?
        get() = (subtitleSelection as? DownloadSubtitleSelection.Embedded)?.streamIndex

    val alwaysBurnInSubtitleWhenTranscoding: Boolean
        get() = subtitleSelection is DownloadSubtitleSelection.Embedded
}

/**
 * Ephemeral facts established by a fixed-download PlaybackInfo response.
 *
 * [transcodingUrl], [deviceId], and [playSessionId] are in-memory, credential-stripped/ephemeral
 * transport facts used only while localizing the package and stopping its active encoding. They
 * must never be written to Room, a checkpoint, a route, or a log. The caller must obtain a new
 * source after a retry/restart so the server policy and exact source are rechecked.
 */
data class FixedDownloadSource(
    val itemId: String,
    val mediaSourceId: String,
    val quality: QualityRung,
    val audioStreamIndex: Int,
    val subtitleStreamIndex: Int?,
    val durationMs: Long,
    val estimatedBytes: Long,
    val transcodingUrl: String,
    /** Ephemeral server identity required to stop this exact active encoding. */
    val deviceId: String,
    /** Ephemeral PlaybackInfo identity required to stop this exact active encoding. */
    val playSessionId: String,
) {
    init {
        require(itemId.isNotBlank()) { "itemId must not be blank." }
        require(mediaSourceId.isNotBlank()) { "mediaSourceId must not be blank." }
        require(qualityRungForBitrate(quality.maxBitrateBps) == quality) {
            "Fixed download quality must use a canonical quality rung."
        }
        require(audioStreamIndex >= 0) { "Audio stream index must be non-negative." }
        require(subtitleStreamIndex == null || subtitleStreamIndex >= 0) {
            "Subtitle stream index must be non-negative when present."
        }
        require(durationMs > 0L) { "Fixed download duration must be positive." }
        require(estimatedBytes > 0L) { "Fixed download estimate must be positive." }
        require(transcodingUrl.isBoundedResourceUrl()) {
            "Fixed download resource URL must be bounded and contain no control characters."
        }
        require(deviceId.isBoundedDownloadIdentity()) {
            "Fixed download device identity must be bounded and contain no control characters."
        }
        require(playSessionId.isBoundedDownloadIdentity()) {
            "Fixed download play-session identity must be bounded and contain no control characters."
        }
    }
}

/** The server accepted the active-encoding cleanup request. Other outcomes remain best effort. */
enum class FixedDownloadCleanupResult {
    Stopped,
    Rejected,
}

sealed interface FixedDownloadPreflightResult {
    data class Ready(
        val source: FixedDownloadSource,
    ) : FixedDownloadPreflightResult

    data class Rejected(
        val failure: FixedDownloadFailure,
    ) : FixedDownloadPreflightResult
}

/** Fixed-download transport failures are closed and contain no server text, URL, or token. */
enum class FixedDownloadFailure {
    AccountUnauthorized,
    PermissionDenied,
    SourceUnavailable,
    SourceChanged,
    SizeUnavailable,
    UnsupportedArtifact,
    Network,
    ServerUnavailable,
    PayloadTooLarge,
}

/** A response body valid only for the duration of the consumer callback. */
class FixedDownloadResource internal constructor(
    val source: FixedDownloadSource,
    val requestedUrl: String,
    val body: ByteReadChannel,
    val contentLength: Long?,
) {
    fun close() {
        body.cancel(null)
    }
}

sealed interface FixedDownloadResourceResult<out T> {
    data class Success<T>(
        val value: T,
    ) : FixedDownloadResourceResult<T>

    data class Rejected(
        val failure: FixedDownloadFailure,
    ) : FixedDownloadResourceResult<Nothing>
}

internal fun String.isBoundedResourceUrl(): Boolean =
    isNotBlank() &&
        length <= MAX_FIXED_RESOURCE_URL_LENGTH &&
        none(Char::isISOControl) &&
        none(Char::isWhitespace)

internal fun String.isBoundedDownloadIdentity(): Boolean =
    isNotBlank() &&
        length <= MAX_FIXED_DOWNLOAD_IDENTITY_LENGTH &&
        none(Char::isISOControl) &&
        none(Char::isWhitespace)

private const val MAX_FIXED_RESOURCE_URL_LENGTH = 8_192
private const val MAX_FIXED_DOWNLOAD_IDENTITY_LENGTH = 256
private const val MAX_FIXED_SUBTITLE_FORMAT_LENGTH = 64
