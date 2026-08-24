// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.OpenSubtitlesSettingsStore
import com.jellyscope.core.data.remote.OpenSubtitlesApi
import com.jellyscope.core.data.remote.OpenSubtitlesQuery
import com.jellyscope.core.domain.model.OpenSubtitleSearchRequest
import com.jellyscope.core.domain.model.OpenSubtitleSearchResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface OpenSubtitlesRepository {
    suspend fun search(request: OpenSubtitleSearchRequest): List<OpenSubtitleSearchResult>

    suspend fun download(fileId: String): OpenSubtitleDownload
}

data class OpenSubtitleDownload(
    val bytes: ByteArray,
    val remaining: Int?,
    val resetTime: String?,
)

internal class DefaultOpenSubtitlesRepository(
    private val api: OpenSubtitlesApi,
    private val settings: OpenSubtitlesSettingsStore,
    // Off-main: search parses DTOs and ranks with sortedWith on the caller's thread.
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : OpenSubtitlesRepository {
    override suspend fun search(request: OpenSubtitleSearchRequest): List<OpenSubtitleSearchResult> =
        withContext(dispatcher) {
            val resultPreference = settings.resultPreference()
            val apiKey = requireApiKey()
            val languages = request.language.toOpenSubtitlesLanguages()
            val queries =
                buildList {
                    request.imdbId?.let { imdb ->
                        add(
                            OpenSubtitlesQuery(
                                imdbId = imdb,
                                seasonNumber = request.seasonNumber,
                                episodeNumber = request.episodeNumber,
                                language = languages,
                            ),
                        )
                        if (request.seasonNumber != null) add(OpenSubtitlesQuery(imdbId = imdb, language = languages))
                    }
                    add(
                        OpenSubtitlesQuery(
                            title = request.title,
                            year = request.year,
                            seasonNumber = request.seasonNumber,
                            episodeNumber = request.episodeNumber,
                            language = languages,
                        ),
                    )
                }.distinct()
            val results = linkedMapOf<String, OpenSubtitleRankingCandidate>()
            var encounterOrder = 0
            queries.forEachIndexed { queryPriority, query ->
                api.search(apiKey, query).data.forEach { dto ->
                    val currentEncounterOrder = encounterOrder++
                    val files = dto.attributes.files
                    val selectableFile = files.singleOrNull()?.takeIf { file -> file.fileName.extension() in SUPPORTED_FORMATS }
                    val file = selectableFile ?: files.firstOrNull() ?: return@forEach
                    val format = file.fileName.extension().takeIf(String::isNotBlank)
                    val result =
                        OpenSubtitleSearchResult(
                            subtitleId = dto.id,
                            fileId = file.fileId.toString(),
                            fileName = file.fileName,
                            language = dto.attributes.language,
                            releaseName = dto.attributes.releaseName,
                            hearingImpaired = dto.attributes.hearingImpaired,
                            forced = dto.attributes.forced,
                            trusted = dto.attributes.trusted,
                            rating = dto.attributes.ratings,
                            downloadCount = dto.attributes.downloadCount,
                            fps = dto.attributes.fps,
                            format = format,
                            selectable = selectableFile != null,
                            unavailableReason =
                                when {
                                    files.size != 1 -> "Multi-file subtitle"
                                    format !in SUPPORTED_FORMATS -> "Unsupported format"
                                    else -> null
                                },
                        )
                    if (result.fileId !in results) {
                        results[result.fileId] =
                            OpenSubtitleRankingCandidate(
                                queryPriority = queryPriority,
                                encounterOrder = currentEncounterOrder,
                                result = result,
                            )
                    }
                }
            }
            rankOpenSubtitles(
                candidates = results.values,
                preference = resultPreference,
                sourceReleaseBasename = request.sourceReleaseBasename,
            )
        }

    override suspend fun download(fileId: String): OpenSubtitleDownload =
        withContext(dispatcher) {
            val response = api.createDownload(requireApiKey(), fileId)
            OpenSubtitleDownload(api.download(response.link), response.remaining, response.resetTime)
        }

    private suspend fun requireApiKey(): String =
        settings.apiKey() ?: throw IllegalStateException("OpenSubtitles API key is not configured.")
}

private fun String.extension(): String = substringAfterLast('.', missingDelimiterValue = "").lowercase()

private val SUPPORTED_FORMATS = setOf("srt", "vtt")

internal fun String.toOpenSubtitlesLanguages(): String =
    split(',', ';')
        .mapNotNull { value ->
            val normalized = value.trim().lowercase().takeIf(String::isNotBlank) ?: return@mapNotNull null
            OPEN_SUBTITLES_LANGUAGE_ALIASES[normalized] ?: normalized.takeIf { it.length == 2 }
        }.distinct()
        .takeIf { values -> values.isNotEmpty() }
        ?.joinToString(",")
        ?: "en"

private val OPEN_SUBTITLES_LANGUAGE_ALIASES =
    mapOf(
        "eng" to "en",
        "english" to "en",
        "spa" to "es",
        "spanish" to "es",
        "fra" to "fr",
        "fre" to "fr",
        "french" to "fr",
        "deu" to "de",
        "ger" to "de",
        "german" to "de",
        "ita" to "it",
        "por" to "pt",
        "nld" to "nl",
        "dut" to "nl",
        "jpn" to "ja",
        "kor" to "ko",
        "zho" to "zh",
        "chi" to "zh",
        "ara" to "ar",
        "hin" to "hi",
        "rus" to "ru",
    )
