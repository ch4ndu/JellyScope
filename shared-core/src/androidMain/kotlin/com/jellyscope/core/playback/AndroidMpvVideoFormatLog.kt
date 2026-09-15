// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.PlaybackNativeVideoFormat
import com.jellyscope.core.domain.playback.PlaybackNativeVideoFormatStage
import com.jellyscope.core.domain.playback.PlaybackNativeVideoQueryResult

// Match only the pinned FFmpeg/mpv numerical records. Raw native text never leaves this adapter.
private val decoderCropPattern =
    Regex(
        "(?:[A-Za-z0-9_]+: )?Output crop parameters top=([0-9]{1,5}) bottom=([0-9]{1,5}) " +
            "left=([0-9]{1,5}) right=([0-9]{1,5}), resulting dimensions width=([0-9]{1,5}) height=([0-9]{1,5})",
    )
private val importedTexturePattern = Regex("Texture dimensions changed to ([0-9]{1,5})x([0-9]{1,5})")
private val imageReaderPattern =
    Regex(
        "ImageReader format buffer=([0-9]{1,5})x([0-9]{1,5}) image=([0-9]{1,5})x([0-9]{1,5}) " +
            "crop=([0-9]{1,5}),([0-9]{1,5}),([0-9]{1,5}),([0-9]{1,5}) " +
            "source=([0-9]{1,5})x([0-9]{1,5}) destination=([0-9]{1,5})x([0-9]{1,5}) queries=([012]),([012]),([012])",
    )

internal fun parseAndroidMpvVideoFormat(
    prefix: String,
    text: String,
): PlaybackNativeVideoFormat? {
    if (text.length > 256) return null
    val message = text.trim()
    return when (prefix) {
        "ffmpeg/video" -> {
            val match = decoderCropPattern.matchEntire(message) ?: return null
            val values = match.groupValues.drop(1).map { it.toInt() }
            if (values.any { it !in 0..16_384 } || values[4] == 0 || values[5] == 0) return null
            if (values[1] < values[0] || values[3] < values[2]) return null
            PlaybackNativeVideoFormat(
                stage = PlaybackNativeVideoFormatStage.DecoderCrop,
                width = values[4],
                height = values[5],
                cropTop = values[0],
                cropBottom = values[1],
                cropLeft = values[2],
                cropRight = values[3],
            )
        }
        "vo/gpu/aimagereader", "vo/gpu-next/aimagereader" -> {
            imageReaderPattern.matchEntire(message)?.let { match ->
                val values = match.groupValues.drop(1).map { it.toInt() }
                if (values.take(12).any { it !in 0..16_384 } || values[0] == 0 || values[1] == 0) return null
                val widthQuery = imageReaderQueryResult(values[12])
                val heightQuery = imageReaderQueryResult(values[13])
                val cropQuery = imageReaderQueryResult(values[14])
                return PlaybackNativeVideoFormat(
                    stage = PlaybackNativeVideoFormatStage.ImageReader,
                    width = values[0],
                    height = values[1],
                    imageWidth = values[2].takeIf { widthQuery == PlaybackNativeVideoQueryResult.Success },
                    imageHeight = values[3].takeIf { heightQuery == PlaybackNativeVideoQueryResult.Success },
                    // AImage right/bottom are exclusive, unlike DecoderCrop's inclusive endpoints.
                    cropLeft = values[4].takeIf { cropQuery == PlaybackNativeVideoQueryResult.Success },
                    cropTop = values[5].takeIf { cropQuery == PlaybackNativeVideoQueryResult.Success },
                    cropRight = values[6].takeIf { cropQuery == PlaybackNativeVideoQueryResult.Success },
                    cropBottom = values[7].takeIf { cropQuery == PlaybackNativeVideoQueryResult.Success },
                    mapperSourceWidth = values[8],
                    mapperSourceHeight = values[9],
                    mapperDestinationWidth = values[10],
                    mapperDestinationHeight = values[11],
                    imageWidthQuery = widthQuery,
                    imageHeightQuery = heightQuery,
                    imageCropQuery = cropQuery,
                )
            }
            val match = importedTexturePattern.matchEntire(message) ?: return null
            val width = match.groupValues[1].toInt()
            val height = match.groupValues[2].toInt()
            if (width !in 1..16_384 || height !in 1..16_384) return null
            PlaybackNativeVideoFormat(PlaybackNativeVideoFormatStage.ImportedTexture, width, height)
        }
        else -> null
    }
}

private fun imageReaderQueryResult(value: Int): PlaybackNativeVideoQueryResult =
    when (value) {
        0 -> PlaybackNativeVideoQueryResult.Success
        1 -> PlaybackNativeVideoQueryResult.Failed
        else -> PlaybackNativeVideoQueryResult.Unavailable
    }
