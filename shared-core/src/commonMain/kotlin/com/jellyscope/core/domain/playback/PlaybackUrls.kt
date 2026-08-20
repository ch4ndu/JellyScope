// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.math.sqrt

internal fun normalizedPlaybackServerUrl(serverUrl: String): String = serverUrl.trim().trimEnd('/')

internal fun directPlayStreamUrl(
    serverUrl: String,
    itemId: String,
    mediaSourceId: String,
    deviceId: String,
): String =
    "${normalizedPlaybackServerUrl(serverUrl)}/Videos/$itemId/stream" +
        "?static=true&mediaSourceId=$mediaSourceId&deviceId=$deviceId"

internal fun directStreamUrl(
    serverUrl: String,
    itemId: String,
    container: String,
    mediaSourceId: String,
    deviceId: String,
    playSessionId: String?,
    audioStreamIndex: Int?,
    subtitleStreamIndex: Int?,
    subtitleMethod: SubtitleDeliveryMethod?,
): String {
    val parameters =
        buildList {
            add("mediaSourceId=$mediaSourceId")
            add("deviceId=$deviceId")
            playSessionId?.takeIf { it.isNotBlank() }?.let { add("playSessionId=$it") }
            audioStreamIndex?.let { add("audioStreamIndex=$it") }
            subtitleStreamIndex?.let { add("subtitleStreamIndex=$it") }
            subtitleMethod?.takeUnless { it == SubtitleDeliveryMethod.Off }?.let { add("subtitleMethod=${it.name}") }
        }
    return "${normalizedPlaybackServerUrl(serverUrl)}/Videos/$itemId/stream.${container.trim().trimStart('.')}" +
        "?${parameters.joinToString("&")}"
}

internal fun normalizedStreamContainer(container: String?): String? =
    container
        ?.substringBefore(',')
        ?.trim()
        ?.trimStart('.')
        ?.takeIf { value -> value.isNotEmpty() && value.all { it.isLetterOrDigit() } }

internal fun playbackStreamMimeType(
    container: String?,
    subProtocol: String?,
): String? {
    if (subProtocol.equals("hls", ignoreCase = true) || container.equals("m3u8", ignoreCase = true)) {
        return "application/x-mpegURL"
    }
    return when (container?.substringBefore(',')?.trim()?.lowercase()) {
        "mp4", "m4v", "mov" -> "video/mp4"
        "mkv", "matroska" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts", "mpegts" -> "video/mp2t"
        "avi" -> "video/x-msvideo"
        else -> null
    }
}

// Jellyfin's transcode scaler is width-tier based and never enforces the
// profile's Height condition on portrait sources (verified against 10.11: a
// 2160x3502 VP9 source with MaxHeight=2160 in the transcode URL still encodes
// 1920x3112, which hardware decoders reject and play back as a black screen).
// When the source cannot fit the decoder's box we compute the
// aspect-preserving fit ourselves and rewrite the URL with it, on both param
// sets the server consults: MaxWidth/MaxHeight must be REPLACED because the
// bitrate-driven resolution normalizer takes min(tier, MaxWidth) and then
// width-fits the portrait source past the height limit, while explicit
// Width/Height wins on the non-normalized path but is ignored once the
// normalizer engages (both behaviors verified against 10.11).
// Returns null when no cap is needed or the target decoder limits are unknown.
internal fun transcodeResolutionCap(
    transcodingUrl: String,
    sourceWidth: Int?,
    sourceHeight: Int?,
    videoResolutionsByCodec: Map<String, VideoCodecResolution>,
    sourceFrameRate: Double? = null,
    qualityRungCeiling: VideoCodecResolution? = null,
    userResolutionCeiling: VideoCodecResolution? = null,
    maxFramerate: Int? = null,
): String? {
    val parameters = transcodingUrl.substringAfter('?', "").split('&')
    // The framerate pin is a numeric CEILING, resolved before any
    // dimension-dependent work: an existing higher value is tightened to the
    // requested one, an existing equal-or-lower value is left alone, and a
    // source with no dimensions still gets its rate-only rewrite — missing
    // dimensions are an accepted forced-transcode path, and dropping the pin
    // there would return a transcode above the decoder's throughput limit,
    // the exact failure this safety path exists to prevent.
    val existingMaxFramerate = parameters.queryIntOrNull("MaxFramerate=")
    val pinnedFramerate =
        maxFramerate?.takeIf { requested -> existingMaxFramerate == null || existingMaxFramerate > requested }

    // Non-null parameter by construction: reading the outer nullable here would
    // let a future unguarded call site emit "MaxFramerate=null" into a URL.
    fun frameratePinnedUrl(pinned: Int): String {
        val base = transcodingUrl.substringBefore('?')
        val retained = parameters.filterNot { it.isEmpty() || it.startsWith("MaxFramerate=") }
        return "$base?${(retained + listOf("MaxFramerate=$pinned")).joinToString("&")}"
    }
    if (sourceWidth == null || sourceHeight == null || sourceWidth <= 0 || sourceHeight <= 0) {
        return pinnedFramerate?.let(::frameratePinnedUrl)
    }
    // A URL that already names a target Width/Height is capped against THAT
    // target, not the source: the server's 1920x1080 choice must still shrink
    // under a selected 480p rung, so the old skip-if-present guard would have
    // let every server-provided target bypass the rung entirely.
    val existingTargetWidth = parameters.queryIntOrNull("Width=")
    val existingTargetHeight = parameters.queryIntOrNull("Height=")
    val capInputWidth = existingTargetWidth?.takeIf { it > 0 } ?: sourceWidth
    val capInputHeight = existingTargetHeight?.takeIf { it > 0 } ?: sourceHeight
    val targetCodecs =
        parameters
            .firstOrNull { it.startsWith("VideoCodec=") }
            ?.substringAfter('=')
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
    // The capability map is keyed by canonical codec names; the URL carries raw
    // tokens (h265/av01/…). Canonicalize so an alias token cannot silently skip
    // the resolution cap (the portrait/oversize black-screen guard).
    val resolutions =
        targetCodecs.map { codec ->
            reconcileVideoResolutionBounds(
                deviceCeiling = videoResolutionsByCodec[canonicalVideoCodec(codec) ?: codec],
                qualityRungCeiling = qualityRungCeiling,
                userResolutionCeiling = userResolutionCeiling,
            ) ?: return null
        }
    val maxWidth = resolutions.mapNotNull { it.maxWidth }.minOrNull()
    val maxHeight = resolutions.mapNotNull { it.maxHeight }.minOrNull()
    // A frame that fits the box can still exceed the decoder's total-blocks or
    // throughput limit. Take the tighter of "pixels per frame" and "pixels per
    // second divided by this source's frame rate": an Amlogic AVC decoder allows
    // 4096x2160 at 30fps but only ~2560x1440 at 60fps, and a 60fps 8K source
    // capped on the box alone produced an AVC level 6.0 stream that no decoder on
    // the device would configure.
    val areaBudget =
        listOfNotNull(
            resolutions.mapNotNull { it.maxFrameArea }.minOrNull(),
            sourceFrameRate
                ?.takeIf { rate -> rate.isFinite() && rate > 0.0 }
                ?.let { rate ->
                    resolutions.mapNotNull { it.maxFrameAreaPerSecond }.minOrNull()?.let { perSecond ->
                        (perSecond.toDouble() / rate).toLong()
                    }
                },
        ).minOrNull()
    val exceedsWidth = maxWidth != null && capInputWidth > maxWidth
    val exceedsHeight = maxHeight != null && capInputHeight > maxHeight
    val exceedsArea = areaBudget != null && blockPaddedArea(capInputWidth, capInputHeight) > areaBudget
    if (!exceedsWidth && !exceedsHeight && !exceedsArea) {
        // MaxFramerate rides the same rewrite: the recovery path must pin it
        // even when the frame box already fits.
        return pinnedFramerate?.let(::frameratePinnedUrl)
    }
    // Integer math keeps the bound dimension exact (a floating-point scale
    // factor turns a 2160-high fit into 2158 through rounding error).
    val widthIsTighter =
        when {
            maxHeight == null -> true
            maxWidth == null -> false
            else -> maxWidth.toLong() * capInputHeight <= maxHeight.toLong() * capInputWidth
        }
    var cappedWidth: Int
    var cappedHeight: Int
    if (widthIsTighter && maxWidth != null) {
        cappedWidth = evenDimension(maxWidth)
        cappedHeight = evenDimension((maxWidth.toLong() * capInputHeight / capInputWidth).toInt())
    } else if (maxHeight != null) {
        cappedHeight = evenDimension(maxHeight)
        cappedWidth = evenDimension((maxHeight.toLong() * capInputWidth / capInputHeight).toInt())
    } else {
        cappedWidth = evenDimension(capInputWidth)
        cappedHeight = evenDimension(capInputHeight)
    }
    if (areaBudget != null) {
        // Shrink on the diagonal until the block-padded frame fits the budget.
        // Rounding to even can nudge the padded area back over, so verify rather
        // than trusting the scale factor.
        while (blockPaddedArea(cappedWidth, cappedHeight) > areaBudget) {
            if (cappedWidth <= MIN_CAPPED_DIMENSION || cappedHeight <= MIN_CAPPED_DIMENSION) break
            val scale = sqrt(areaBudget.toDouble() / blockPaddedArea(cappedWidth, cappedHeight).toDouble())
            val nextWidth = evenDimension((cappedWidth * scale).toInt())
            val nextHeight = evenDimension((cappedHeight * scale).toInt())
            // A scale of ~1.0 can round back to the same size; step down so the
            // loop always terminates. Clamp to the floor so an absurd budget
            // cannot drive a dimension to zero and lose the cap entirely — a
            // floor-sized frame every decoder accepts beats no cap at all.
            cappedWidth = (if (nextWidth < cappedWidth) nextWidth else cappedWidth - 2).coerceAtLeast(MIN_CAPPED_DIMENSION)
            cappedHeight = (if (nextHeight < cappedHeight) nextHeight else cappedHeight - 2).coerceAtLeast(MIN_CAPPED_DIMENSION)
        }
    }
    if (cappedWidth <= 0 || cappedHeight <= 0) return null
    if (cappedWidth >= capInputWidth && cappedHeight >= capInputHeight) {
        return pinnedFramerate?.let(::frameratePinnedUrl)
    }
    val base = transcodingUrl.substringBefore('?')
    val retained =
        parameters.filterNot { parameter ->
            parameter.isEmpty() ||
                parameter.startsWith("MaxWidth=") ||
                parameter.startsWith("MaxHeight=") ||
                parameter.startsWith("Width=") ||
                parameter.startsWith("Height=") ||
                (pinnedFramerate != null && parameter.startsWith("MaxFramerate="))
        }
    val cappedParameters =
        listOf(
            "MaxWidth=$cappedWidth",
            "MaxHeight=$cappedHeight",
            "Width=$cappedWidth",
            "Height=$cappedHeight",
        ) + listOfNotNull(pinnedFramerate?.let { "MaxFramerate=$it" })
    return "$base?${(retained + cappedParameters).joinToString("&")}"
}

private fun List<String>.queryIntOrNull(prefix: String): Int? = firstOrNull { it.startsWith(prefix) }?.substringAfter('=')?.toIntOrNull()

// Jellyfin can attach a default subtitle to a transcode even when PlaybackInfo
// was requested with no subtitle (or with -1). The transcode URL itself drives
// ffmpeg, so retain subtitle query parameters only when they express the
// resolved JellyScope request; otherwise they would burn an unrequested
// subtitle into the video.
fun subtitleHonestTranscodingUrl(
    transcodingUrl: String,
    selectedSubtitleStreamIndex: Int?,
): String {
    val queryStart = transcodingUrl.indexOf('?')
    if (queryStart < 0) return transcodingUrl

    val base = transcodingUrl.substring(0, queryStart)
    val parameters = transcodingUrl.substring(queryStart + 1).split('&')
    val attachedIndices =
        parameters
            .filter { parameter -> parameter.substringBefore('=') == "SubtitleStreamIndex" }
            .map { parameter -> parameter.substringAfter('=', missingDelimiterValue = "").toIntOrNull() }
    val matchesRequest =
        selectedSubtitleStreamIndex != null &&
            attachedIndices.isNotEmpty() &&
            attachedIndices.all { index -> index == selectedSubtitleStreamIndex }
    if (attachedIndices.isEmpty() || matchesRequest) return transcodingUrl

    val retained =
        parameters.filterNot { parameter ->
            parameter.substringBefore('=') in setOf("SubtitleStreamIndex", "SubtitleMethod")
        }
    return if (retained.isEmpty()) base else "$base?${retained.joinToString("&")}"
}

// Jellyfin serves a SubRip external subtitle as ".../Stream.subrip" with
// Content-Type application/octet-stream. libvlc identifies a subtitle slave by its
// extension/MIME and recognizes neither ".subrip" nor octet-stream, so it never
// creates a track for it (the transcode-subtitle bug) on any VLC backend. ".srt"
// serves byte-identical SubRip with a recognized extension (application/x-subrip).
// Jellyfin's other subtitle extensions (srt/ass/ssa/vtt/webvtt) are already
// VLC-recognized and OpenSubtitles assets are stored as .vtt, so only ".subrip" is
// remapped. Only the path is rewritten: the query — including any credential the
// caller's origin guard attaches — is preserved verbatim.
internal fun vlcSlaveSubtitleUrl(url: String): String {
    val queryIndex = url.indexOf('?')
    val path = if (queryIndex >= 0) url.substring(0, queryIndex) else url
    val query = if (queryIndex >= 0) url.substring(queryIndex) else ""
    if (!path.endsWith(VLC_UNRECOGNIZED_SUBRIP_EXTENSION, ignoreCase = true)) return url
    return path.dropLast(VLC_UNRECOGNIZED_SUBRIP_EXTENSION.length) + VLC_SUBRIP_EXTENSION + query
}

private const val VLC_UNRECOGNIZED_SUBRIP_EXTENSION = ".subrip"
private const val VLC_SUBRIP_EXTENSION = ".srt"

private fun evenDimension(value: Int): Int = (value / 2) * 2

// Decoder block limits count whole 16x16 macroblocks, so a frame is charged for
// the padding it rounds up to. Comparing raw pixels would let a size slip past a
// budget it actually exceeds once padded.
private const val MACROBLOCK = 16
private const val MIN_CAPPED_DIMENSION = 256

internal fun blockPaddedArea(
    width: Int,
    height: Int,
): Long {
    val paddedWidth = ((width + MACROBLOCK - 1) / MACROBLOCK).toLong() * MACROBLOCK
    val paddedHeight = ((height + MACROBLOCK - 1) / MACROBLOCK).toLong() * MACROBLOCK
    return paddedWidth * paddedHeight
}

fun resolveServerRelativeUrl(
    serverUrl: String,
    url: String,
): String {
    val trimmedUrl = url.trim()
    if (
        trimmedUrl.startsWith("http://", ignoreCase = true) ||
        trimmedUrl.startsWith("https://", ignoreCase = true)
    ) {
        return trimmedUrl
    }

    return "${normalizedPlaybackServerUrl(serverUrl)}/${trimmedUrl.trimStart('/')}"
}
