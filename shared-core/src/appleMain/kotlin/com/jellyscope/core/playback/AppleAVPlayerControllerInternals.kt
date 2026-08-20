// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.SubtitleEdgeStyle
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.rawValue
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVKeyValueStatusLoaded
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.AVMutableCompositionTrack
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVTextStyleRule
import platform.AVFoundation.addMutableTrackWithMediaType
import platform.AVFoundation.insertTimeRange
import platform.AVFoundation.mediaType
import platform.AVFoundation.setTextStyleRules
import platform.AVFoundation.timeRange
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.kCMPersistentTrackID_Invalid
import platform.CoreMedia.kCMTextMarkupAttribute_BackgroundColorARGB
import platform.CoreMedia.kCMTextMarkupAttribute_CharacterBackgroundColorARGB
import platform.CoreMedia.kCMTextMarkupAttribute_CharacterEdgeStyle
import platform.CoreMedia.kCMTextMarkupAttribute_ForegroundColorARGB
import platform.CoreMedia.kCMTextMarkupAttribute_RelativeFontSize
import platform.CoreMedia.kCMTextMarkupCharacterEdgeStyle_DropShadow
import platform.CoreMedia.kCMTextMarkupCharacterEdgeStyle_None
import platform.CoreMedia.kCMTextMarkupCharacterEdgeStyle_Uniform
import platform.Foundation.NSNumber
import platform.Foundation.NSString

/**
 * Inserts [track] in full, reporting whether the composition track was actually
 * assembled. `timeRange` (and the iOS `preferredTransform`) are read
 * synchronously here, so the caller must have preloaded
 * [compositionValueKeys] — on a network-backed asset an unloaded value would
 * otherwise block until it is fetched.
 */
internal fun AVMutableComposition.insertFullTrack(track: AVAssetTrack): Boolean {
    if (!track.hasLoadedCompositionValues()) return false
    val target =
        addMutableTrackWithMediaType(
            mediaType = track.mediaType,
            preferredTrackID = kCMPersistentTrackID_Invalid,
        ) ?: return false
    val inserted =
        target.insertTimeRange(
            timeRange = track.timeRange,
            ofTrack = track,
            atTime = CMTimeMakeWithSeconds(0.0, MILLISECOND_TIME_SCALE),
            error = null,
        )
    if (!inserted) return false
    if (track.mediaType == AVMediaTypeVideo) {
        target.applyPreferredVideoTransform(track)
    }
    return true
}

/** Track values [insertFullTrack] reads synchronously; preload them first. */
internal fun AVAssetTrack.compositionValueKeys(): List<String> =
    if (mediaType == AVMediaTypeVideo) {
        listOf(TRACK_TIME_RANGE_KEY, TRACK_PREFERRED_TRANSFORM_KEY)
    } else {
        listOf(TRACK_TIME_RANGE_KEY)
    }

private fun AVAssetTrack.hasLoadedCompositionValues(): Boolean =
    compositionValueKeys().all { key -> statusOfValueForKey(key, error = null) == AVKeyValueStatusLoaded }

// The tvOS SDK interop does not expose the AVMutableCompositionTrack
// preferredTransform setter, and rotation transforms are phone-recording
// metadata; iOS applies the source track's transform, tvOS leaves identity.
internal expect fun AVMutableCompositionTrack.applyPreferredVideoTransform(track: AVAssetTrack)

internal fun AVPlayerItem.applySubtitleStyle(style: SubtitleStyle) {
    val rule = style.toAVTextStyleRule() ?: return
    setTextStyleRules(listOf(rule))
}

private fun SubtitleStyle.toAVTextStyleRule(): AVTextStyleRule? {
    val attributes = mutableMapOf<Any?, Any>()
    foregroundColor
        .toArgbComponents()
        ?.let { components ->
            attributes[cfStringKey(kCMTextMarkupAttribute_ForegroundColorARGB)] = components.toNSNumberList()
        }
    backgroundColor
        .toArgbComponents()
        ?.let { components ->
            val color = components.toNSNumberList()
            attributes[cfStringKey(kCMTextMarkupAttribute_BackgroundColorARGB)] = color
            attributes[cfStringKey(kCMTextMarkupAttribute_CharacterBackgroundColorARGB)] = color
        }
    attributes[cfStringKey(kCMTextMarkupAttribute_RelativeFontSize)] =
        NSNumber(double = fontScale.coerceIn(0.1f, 5f).toDouble() * 100.0)
    attributes[cfStringKey(kCMTextMarkupAttribute_CharacterEdgeStyle)] =
        when (edgeStyle) {
            SubtitleEdgeStyle.None -> cfStringKey(kCMTextMarkupCharacterEdgeStyle_None)
            SubtitleEdgeStyle.Outline -> cfStringKey(kCMTextMarkupCharacterEdgeStyle_Uniform)
            SubtitleEdgeStyle.DropShadow -> cfStringKey(kCMTextMarkupCharacterEdgeStyle_DropShadow)
        }

    return AVTextStyleRule.textStyleRuleWithTextMarkupAttributes(attributes)
}

private data class ArgbComponents(
    val alpha: Double,
    val red: Double,
    val green: Double,
    val blue: Double,
) {
    fun toNSNumberList(): List<NSNumber> =
        listOf(
            NSNumber(double = alpha),
            NSNumber(double = red),
            NSNumber(double = green),
            NSNumber(double = blue),
        )
}

private fun String?.toArgbComponents(): ArgbComponents? {
    val hex = this?.removePrefix("#") ?: return null
    val argb =
        when (hex.length) {
            6 -> "FF$hex"
            8 -> hex
            else -> return null
        }
    val value = argb.toLongOrNull(radix = 16) ?: return null
    return ArgbComponents(
        alpha = ((value shr 24) and 0xFF).toDouble() / 255.0,
        red = ((value shr 16) and 0xFF).toDouble() / 255.0,
        green = ((value shr 8) and 0xFF).toDouble() / 255.0,
        blue = (value and 0xFF).toDouble() / 255.0,
    )
}

internal fun String.isAVFoundationSidecarSubtitleMimeType(): Boolean =
    equals("text/vtt", ignoreCase = true) ||
        equals("text/webvtt", ignoreCase = true) ||
        equals("application/ttml+xml", ignoreCase = true) ||
        equals("application/x-mpegurl", ignoreCase = true) ||
        equals("application/vnd.apple.mpegurl", ignoreCase = true)

private fun cfStringKey(value: kotlinx.cinterop.CPointer<*>?): NSString =
    interpretObjCPointer(value?.rawValue ?: error("Missing CoreMedia text markup key"))

internal fun Long.isAVNetworkStatusCode(): Boolean =
    this in 400L..599L ||
        this in AV_NETWORK_STATUS_CODES

private val AV_NETWORK_STATUS_CODES =
    setOf(
        -1001L,
        -1003L,
        -1004L,
        -1005L,
        -1009L,
        -1011L,
        -1100L,
        -12889L,
    )

internal fun String.containsAny(vararg needles: String): Boolean = needles.any { needle -> contains(needle) }

// Playback diagnostics retain structural grammar sanitization at their call sites;
// arbitrary value redaction is intentionally not part of this internal surface.

internal const val POSITION_OBSERVER_INTERVAL_SECONDS = 0.5
internal const val SEEK_TOLERANCE_SECONDS = 1.0
internal const val SEEK_SETTLE_TOLERANCE_MS = 1_500L
internal const val STATE_POLL_INTERVAL_MS = 300L
internal const val IDLE_STATE_POLL_INTERVAL_MS = 1_000L

// Generous deadline for the async AVAsset media-selection group load (iOS 18):
// on-device asset inspection can exceed the short 3s audio-activation deadline,
// so a merely-slow (but completing) load must not be false-failed into a
// needless transcode. Only a truly stalled load hits this bound and recovers.
internal const val MEDIA_SELECTION_LOAD_TIMEOUT_MS = 10_000L
internal const val MILLISECOND_TIME_SCALE = 1_000
internal const val MILLISECONDS_PER_SECOND = 1_000.0
internal const val END_POSITION_TOLERANCE_MS = 500L
internal const val AV_URL_ASSET_HTTP_HEADER_FIELDS_KEY = "AVURLAssetHTTPHeaderFieldsKey"

// Asynchronously loadable AVFoundation value keys read by the sidecar-composition
// assembly; loading them up front keeps the network fetch off the calling thread.
internal const val ASSET_TRACKS_KEY = "tracks"
internal const val TRACK_TIME_RANGE_KEY = "timeRange"
internal const val TRACK_PREFERRED_TRANSFORM_KEY = "preferredTransform"
internal const val WAITING_DIAGNOSTIC_FIRST_EMIT = 5
internal const val WAITING_DIAGNOSTIC_REPEAT_EMITS = 20
internal const val LOG_EVENT_LIMIT = 5

internal val controllerLogger = diagnosticLogger(DiagnosticTag.AppleAVPlayerController)
