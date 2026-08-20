// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import com.jellyscope.core.domain.model.MediaVersion
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import kotlin.math.roundToLong

data class MediaInfoUi(
    val fileLine: String?,
    val videoLines: List<String>,
    val audioLines: List<String>,
    val subtitleLines: List<String>,
) {
    val hasAnything: Boolean
        get() =
            fileLine != null ||
                videoLines.isNotEmpty() ||
                audioLines.isNotEmpty() ||
                subtitleLines.isNotEmpty()
}

fun buildMediaInfo(
    version: MediaVersion?,
    sizeBytes: Long?,
    externalLabel: String = "External",
): MediaInfoUi? {
    val streams = version?.mediaStreams.orEmpty()
    if (streams.isEmpty() && sizeBytes == null) {
        return null
    }

    val mediaInfo =
        MediaInfoUi(
            fileLine = formatFileSize(sizeBytes),
            videoLines =
                streams
                    .filter { stream -> stream.type == "Video" }
                    .mapNotNull { stream -> stream.videoLabel() },
            audioLines =
                streams
                    .filter { stream -> stream.type == "Audio" }
                    .mapNotNull { stream -> stream.audioLabel() },
            subtitleLines =
                streams
                    .filter { stream -> stream.type == "Subtitle" }
                    .mapNotNull { stream -> stream.subtitleLabel(externalLabel) },
        )
    return mediaInfo.takeIf { info -> info.hasAnything }
}

internal fun bitrateText(bps: Long?): String? {
    val bitrate = bps?.takeIf { value -> value > 0L } ?: return null
    return if (bitrate >= BITS_PER_MEGABIT) {
        val tenths = (bitrate.toDouble() / BITS_PER_MEGABIT * 10).roundToLong()
        "${tenths / 10}.${tenths % 10} Mbps"
    } else {
        "${(bitrate.toDouble() / BITS_PER_KILOBIT).roundToLong()} kbps"
    }
}

internal fun formatFileSize(bytes: Long?): String? {
    val size = bytes?.takeIf { value -> value > 0L } ?: return null
    return if (size >= BYTES_PER_GIB) {
        val tenths = (size.toDouble() / BYTES_PER_GIB * 10).roundToLong()
        "${tenths / 10}.${tenths % 10} GB"
    } else {
        val mib = (size.toDouble() / BYTES_PER_MIB).roundToLong().coerceAtLeast(1L)
        "$mib MB"
    }
}

private fun PlaybackMediaStream.videoLabel(): String? =
    displayTitle.clean()
        ?: listOfNotNull(
            height?.let { value -> "${value}p" },
            codec.clean()?.uppercase(),
            bitrateText(bitRate),
        ).joinMediaInfoParts()

private fun PlaybackMediaStream.audioLabel(): String? =
    displayTitle.clean()
        ?: listOfNotNull(
            language.clean(),
            codec.clean()?.uppercase(),
            channelLayout.clean(),
        ).joinMediaInfoParts()

private fun PlaybackMediaStream.subtitleLabel(externalLabel: String): String? =
    displayTitle.clean()
        ?: listOfNotNull(
            language.clean(),
            codec.clean()?.uppercase(),
            externalLabel.takeIf { isExternal == true },
        ).joinMediaInfoParts()

private fun List<String>.joinMediaInfoParts(): String? =
    mapNotNull { value -> value.clean() }
        .takeIf { parts -> parts.isNotEmpty() }
        ?.joinToString(" · ")

private fun String?.clean(): String? = this?.trim()?.takeIf { value -> value.isNotBlank() }

private const val BITS_PER_KILOBIT = 1_000L
private const val BITS_PER_MEGABIT = 1_000_000L
private const val BYTES_PER_MIB = 1_024L * 1_024L
private const val BYTES_PER_GIB = BYTES_PER_MIB * 1_024L
