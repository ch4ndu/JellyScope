// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import com.jellyscope.core.domain.model.MediaPerson
import com.jellyscope.core.domain.model.MediaPersonType
import com.jellyscope.core.domain.model.MediaVersion
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.toPlaybackDuration
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_directed_by
import com.jellyscope.ui.generated.resources.detail_external_subtitle
import com.jellyscope.ui.generated.resources.detail_runtime_hours_minutes
import com.jellyscope.ui.generated.resources.detail_runtime_minutes
import com.jellyscope.ui.generated.resources.detail_time_left
import com.jellyscope.ui.generated.resources.detail_version_fallback
import com.jellyscope.ui.generated.resources.month_abbrev_apr
import com.jellyscope.ui.generated.resources.month_abbrev_aug
import com.jellyscope.ui.generated.resources.month_abbrev_dec
import com.jellyscope.ui.generated.resources.month_abbrev_feb
import com.jellyscope.ui.generated.resources.month_abbrev_jan
import com.jellyscope.ui.generated.resources.month_abbrev_jul
import com.jellyscope.ui.generated.resources.month_abbrev_jun
import com.jellyscope.ui.generated.resources.month_abbrev_mar
import com.jellyscope.ui.generated.resources.month_abbrev_may
import com.jellyscope.ui.generated.resources.month_abbrev_nov
import com.jellyscope.ui.generated.resources.month_abbrev_oct
import com.jellyscope.ui.generated.resources.month_abbrev_sep
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.getString
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

data class DetailFormatterStrings(
    val timeLeftTemplate: String,
    val directedByTemplate: String,
    val versionTemplate: String,
    val monthAbbreviations: List<String>,
    val runtimeHoursMinutesTemplate: String = "%1\$d h %2\$d min",
    val runtimeMinutesTemplate: String = "%1\$d min",
    val externalSubtitleLabel: String = "External",
)

suspend fun detailFormatterStrings(): DetailFormatterStrings =
    DetailFormatterStrings(
        timeLeftTemplate = getString(Res.string.detail_time_left),
        directedByTemplate = getString(Res.string.detail_directed_by),
        versionTemplate = getString(Res.string.detail_version_fallback),
        runtimeHoursMinutesTemplate = getString(Res.string.detail_runtime_hours_minutes),
        runtimeMinutesTemplate = getString(Res.string.detail_runtime_minutes),
        externalSubtitleLabel = getString(Res.string.detail_external_subtitle),
        monthAbbreviations =
            listOf(
                getString(Res.string.month_abbrev_jan),
                getString(Res.string.month_abbrev_feb),
                getString(Res.string.month_abbrev_mar),
                getString(Res.string.month_abbrev_apr),
                getString(Res.string.month_abbrev_may),
                getString(Res.string.month_abbrev_jun),
                getString(Res.string.month_abbrev_jul),
                getString(Res.string.month_abbrev_aug),
                getString(Res.string.month_abbrev_sep),
                getString(Res.string.month_abbrev_oct),
                getString(Res.string.month_abbrev_nov),
                getString(Res.string.month_abbrev_dec),
            ),
    )

fun streamBadges(version: MediaVersion?): List<String> {
    val streams = version?.mediaStreams.orEmpty()
    val videoStream =
        streams.firstOrNull { stream ->
            stream.type.equals("Video", ignoreCase = true)
        }
    val audioStream = defaultAudioStream(streams)

    return listOfNotNull(
        videoStream?.height?.let(::resolutionLabel),
        videoStream?.codec?.takeIf { value -> value.isNotBlank() }?.uppercaseCodec(),
        audioStream?.codec?.takeIf { value -> value.isNotBlank() }?.uppercaseCodec(),
        audioStream?.channelLayout?.takeIf { value -> value.isNotBlank() }?.compactChannels(),
    )
}

fun resolutionLabel(height: Int): String =
    when {
        height >= 2_000 -> "4K"
        height >= 1_000 -> "1080p"
        height >= 700 -> "720p"
        else -> "SD"
    }

fun timeLeftText(
    runtime: Duration?,
    playbackPositionTicks: Long?,
    strings: DetailFormatterStrings,
): String? {
    val remaining =
        runtime
            ?.minus(playbackPositionTicks.toPlaybackDuration())
            ?.takeIf { duration -> duration.isPositive() }
            ?: return null
    return strings.timeLeftTemplate.formatSingleStringArg(remaining.compactTime())
}

fun castAndCrew(people: List<MediaPerson>): List<MediaPerson> {
    val actors = people.filter { person -> person.type == MediaPersonType.Actor }
    val creative =
        people.filter { person ->
            person.type == MediaPersonType.Director || person.type == MediaPersonType.Writer
        }
    return (actors + creative).distinctBy { person -> person.id }.take(20)
}

fun directedByLine(
    people: List<MediaPerson>,
    strings: DetailFormatterStrings,
): String? =
    people
        .filter { person -> person.type == MediaPersonType.Director }
        .map { person -> person.name }
        .distinct()
        .takeIf { names -> names.isNotEmpty() }
        ?.joinToString(", ")
        ?.let { names -> strings.directedByTemplate.formatSingleStringArg(names) }

fun communityRatingText(rating: Double?): String? = rating?.let { value -> "★ ${((value * 10).roundToInt() / 10.0).trimTrailingZero()}" }

fun criticRatingText(rating: Double?): String? = rating?.let { value -> "${value.roundToInt()}%" }

internal fun mediaVersionLabel(
    serverName: String,
    index: Int,
    strings: DetailFormatterStrings,
): String =
    serverName.trim().takeIf(String::isNotEmpty)
        ?: strings.versionTemplate.formatSingleIntArg(index + 1)

/**
 * Season and episode label for detail surfaces. Unlike [episodeNumberLabel], a
 * missing episode number preserves a season-only label; queue badges use the
 * episode-specific formatter and render nothing in that case.
 */
fun seasonEpisodeLabel(
    seasonNumber: Int?,
    episodeNumber: Int?,
): String? =
    listOfNotNull(
        seasonNumber?.let { value -> "S$value" },
        episodeNumber?.let { value -> "E$value" },
    ).takeIf { parts -> parts.isNotEmpty() }?.joinToString(" · ")

fun airDateText(
    premiereDate: Instant?,
    strings: DetailFormatterStrings,
): String? {
    val date = premiereDate?.toLocalDateTime(TimeZone.UTC)?.date ?: return null
    return "${monthName(date.month.number, strings)} ${date.day}, ${date.year}"
}

fun episodeRuntimeText(
    runtime: Duration?,
    strings: DetailFormatterStrings =
        DetailFormatterStrings(
            timeLeftTemplate = "",
            directedByTemplate = "",
            versionTemplate = "",
            monthAbbreviations = emptyList(),
        ),
): String? =
    runtime
        ?.takeIf { duration -> duration.isPositive() }
        ?.let { duration ->
            val hours = duration.inWholeHours
            val minutes = (duration - hours.hours).inWholeMinutes
            if (hours > 0) {
                strings.runtimeHoursMinutesTemplate.formatTwoIntArgs(hours.toInt(), minutes.toInt())
            } else {
                strings.runtimeMinutesTemplate.formatSingleIntArg(minutes.toInt())
            }
        }

private fun defaultAudioStream(streams: List<PlaybackMediaStream>): PlaybackMediaStream? =
    streams
        .filter { candidate -> candidate.type.equals("Audio", ignoreCase = true) }
        .let { audioStreams ->
            audioStreams.firstOrNull { candidate -> candidate.isDefault == true }
                ?: audioStreams.firstOrNull()
        }

private fun Duration.compactTime(): String {
    val hours = inWholeHours
    val minutes = (this - hours.hours).inWholeMinutes
    return if (hours > 0) {
        "${hours}h ${minutes}m"
    } else {
        "${minutes}m"
    }
}

private fun String.uppercaseCodec(): String =
    when (lowercase()) {
        "eac3" -> "EAC3"
        "ac3" -> "AC3"
        "aac" -> "AAC"
        "dts" -> "DTS"
        "truehd" -> "TRUEHD"
        "subrip", "srt" -> "SRT"
        "webvtt", "vtt" -> "VTT"
        else -> uppercase()
    }

private fun String.compactChannels(): String? {
    val digitMatch = Regex("""\d(?:\.\d)?""").find(this)?.value
    if (digitMatch != null) {
        return digitMatch
    }
    return when (lowercase()) {
        "mono" -> "1.0"
        "stereo" -> "2.0"
        else -> null
    }
}

private fun monthName(
    monthNumber: Int,
    strings: DetailFormatterStrings,
): String =
    when (monthNumber) {
        in 1..11 -> strings.monthAbbreviations[monthNumber - 1]
        else -> strings.monthAbbreviations[11]
    }

private fun String.formatSingleStringArg(value: String): String =
    when {
        contains("%1\$s") -> replace("%1\$s", value)
        contains("%s") -> replace("%s", value)
        else -> this
    }

private fun String.formatSingleIntArg(value: Int): String =
    when {
        contains("%1\$d") -> replace("%1\$d", value.toString())
        contains("%d") -> replace("%d", value.toString())
        else -> this
    }

private fun String.formatTwoIntArgs(
    first: Int,
    second: Int,
): String = formatSingleIntArg(first).replace("%2\$d", second.toString())

private fun Double.trimTrailingZero(): String =
    if (this % 1.0 == 0.0) {
        toInt().toString()
    } else {
        toString()
    }
