// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.domain.model.OpenSubtitleResultPreference
import com.jellyscope.core.domain.model.OpenSubtitleSearchResult

internal data class OpenSubtitleRankingCandidate(
    val queryPriority: Int,
    val encounterOrder: Int,
    val result: OpenSubtitleSearchResult,
)

internal fun rankOpenSubtitles(
    candidates: Collection<OpenSubtitleRankingCandidate>,
    preference: OpenSubtitleResultPreference,
    sourceReleaseBasename: String?,
): List<OpenSubtitleSearchResult> =
    candidates
        .sortedWith { left, right ->
            compareValues(left.queryPriority, right.queryPriority)
                .ifEqual { compareValues(right.result.selectable, left.result.selectable) }
                .ifEqual {
                    compareValues(
                        right.result.matches(preference),
                        left.result.matches(preference),
                    )
                }.ifEqual {
                    compareReleaseSimilarity(
                        left = releaseSimilarity(sourceReleaseBasename, left.result.releaseName),
                        right = releaseSimilarity(sourceReleaseBasename, right.result.releaseName),
                    )
                }.ifEqual { compareValues(right.result.trusted, left.result.trusted) }
                .ifEqual { compareValues(right.result.rating ?: 0.0, left.result.rating ?: 0.0) }
                .ifEqual { compareValues(right.result.downloadCount ?: 0L, left.result.downloadCount ?: 0L) }
                .ifEqual { compareValues(left.encounterOrder, right.encounterOrder) }
        }.map(OpenSubtitleRankingCandidate::result)

private fun OpenSubtitleSearchResult.matches(preference: OpenSubtitleResultPreference): Boolean =
    when (preference) {
        OpenSubtitleResultPreference.NoPreference -> false
        OpenSubtitleResultPreference.PreferHearingImpaired -> hearingImpaired
        OpenSubtitleResultPreference.PreferForced -> forced
    }

private data class ReleaseSimilarity(
    val exactNormalizedMatch: Boolean,
    val intersection: Int,
    val union: Int,
)

private fun releaseSimilarity(
    sourceReleaseBasename: String?,
    resultReleaseName: String?,
): ReleaseSimilarity {
    val source = normalizeReleaseName(sourceReleaseBasename, sourceOnly = true)
    val result = normalizeReleaseName(resultReleaseName, sourceOnly = false)
    if (source.tokens.isEmpty() || result.tokens.isEmpty()) return ReleaseSimilarity(false, 0, 1)
    val intersection = source.tokenSet.intersect(result.tokenSet).size
    val union =
        source.tokenSet
            .union(result.tokenSet)
            .size
            .coerceAtLeast(1)
    return ReleaseSimilarity(
        exactNormalizedMatch = source.tokens == result.tokens,
        intersection = intersection,
        union = union,
    )
}

private fun compareReleaseSimilarity(
    left: ReleaseSimilarity,
    right: ReleaseSimilarity,
): Int =
    compareValues(right.exactNormalizedMatch, left.exactNormalizedMatch)
        .ifEqual {
            compareValues(
                right.intersection * left.union,
                left.intersection * right.union,
            )
        }

private data class NormalizedReleaseName(
    val tokens: List<String>,
) {
    val tokenSet: Set<String> = tokens.toSet()
}

private fun normalizeReleaseName(
    value: String?,
    sourceOnly: Boolean,
): NormalizedReleaseName {
    val extensionAllowlist = if (sourceOnly) MEDIA_EXTENSIONS else MEDIA_EXTENSIONS + SUBTITLE_EXTENSIONS
    var normalized =
        value
            ?.trim()
            ?.lowercase()
            .orEmpty()
            .stripAllowedExtension(extensionAllowlist)
    normalized = normalized.replace(WEB_DL_ALIAS, "webdl")
    normalized =
        normalized.replace(CODEC_ALIAS) { match ->
            "${match.groupValues[1]}26${match.groupValues[2]}"
        }
    normalized = normalized.replace(AAC_CHANNEL_ALIAS, "aac")
    val tokens =
        normalized
            .split(NON_ALPHANUMERIC)
            .filter { token -> token.length > 1 && token !in TECHNICAL_STOP_TOKENS }
    return NormalizedReleaseName(tokens)
}

private fun String.stripAllowedExtension(allowlist: Set<String>): String {
    val extension = substringAfterLast('.', missingDelimiterValue = "")
    return if (extension in allowlist) dropLast(extension.length + 1) else this
}

private inline fun Int.ifEqual(next: () -> Int): Int = if (this == 0) next() else this

private val MEDIA_EXTENSIONS = setOf("mkv", "mp4", "m4v", "mov", "avi", "ts", "m2ts", "webm", "mpg", "mpeg")
private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub")
private val WEB_DL_ALIAS = Regex("\\bweb[\\s._-]*dl\\b", RegexOption.IGNORE_CASE)
private val CODEC_ALIAS = Regex("\\b([hx])[\\s._-]*26([45])\\b", RegexOption.IGNORE_CASE)
private val AAC_CHANNEL_ALIAS = Regex("\\baac[\\s._-]*(?:2|5|7)[\\s._-]*(?:0|1)\\b", RegexOption.IGNORE_CASE)
private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
private val TECHNICAL_STOP_TOKENS =
    setOf(
        "480p",
        "720p",
        "1080p",
        "2160p",
        "4k",
        "8k",
        "x264",
        "x265",
        "h264",
        "h265",
        "hevc",
        "av1",
        "vp9",
        "web",
        "webdl",
        "webrip",
        "bluray",
        "brrip",
        "hdrip",
        "dvdrip",
        "remux",
        "hdr",
        "hdr10",
        "dovi",
        "dv",
        "8bit",
        "10bit",
        "aac",
        "ac3",
        "eac3",
        "dts",
        "atmos",
    )
