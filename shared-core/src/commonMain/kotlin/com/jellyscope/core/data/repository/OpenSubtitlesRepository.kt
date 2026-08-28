// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.OpenSubtitlesSettingsStore
import com.jellyscope.core.data.remote.OpenSubtitlesApi
import com.jellyscope.core.data.remote.OpenSubtitlesQuery
import com.jellyscope.core.domain.model.OpenSubtitleSearchRequest
import com.jellyscope.core.domain.model.OpenSubtitleSearchResult
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
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
    private val developerApiKey: String? = null,
) : OpenSubtitlesRepository {
    override suspend fun search(request: OpenSubtitleSearchRequest): List<OpenSubtitleSearchResult> =
        withContext(dispatcher) {
            val resultPreference = settings.resultPreference()
            val apiKey = requireApiKey()
            val languages = request.language.toOpenSubtitlesLanguages()
            val isEpisode = request.seasonNumber != null || request.episodeNumber != null
            val fallbackTitle =
                if (isEpisode) {
                    request.seriesTitle?.takeIf(String::isNotBlank) ?: request.title
                } else {
                    request.title
                }
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
                            title = fallbackTitle,
                            year = request.year.takeUnless { isEpisode },
                            seasonNumber = request.seasonNumber,
                            episodeNumber = request.episodeNumber,
                            language = languages,
                        ),
                    )
                }.distinct()
            val results = linkedMapOf<String, OpenSubtitleRankingCandidate>()
            var encounterOrder = 0
            queries.forEachIndexed { queryPriority, query ->
                api.search(apiKey, query).data.orEmpty().forEach { dto ->
                    val currentEncounterOrder = encounterOrder++
                    val subtitleId = dto?.id?.takeIf(String::isNotBlank) ?: return@forEach
                    val attributes = dto.attributes ?: return@forEach
                    val files =
                        attributes.files.orEmpty().mapNotNull { file ->
                            val fileId = file?.fileId?.takeIf { value -> value > 0 } ?: return@mapNotNull null
                            val fileName = file.fileName?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                            fileId to fileName
                        }
                    val selectableFile = files.singleOrNull()?.takeIf { file -> file.second.extension() in SUPPORTED_FORMATS }
                    val file = selectableFile ?: files.firstOrNull() ?: return@forEach
                    val format = file.second.extension().takeIf(String::isNotBlank)
                    val result =
                        OpenSubtitleSearchResult(
                            subtitleId = subtitleId,
                            fileId = file.first.toString(),
                            fileName = file.second,
                            language = attributes.language.orEmpty(),
                            releaseName = attributes.releaseName,
                            hearingImpaired = attributes.hearingImpaired == true,
                            forced = attributes.forced == true,
                            trusted = attributes.trusted == true,
                            rating = attributes.ratings,
                            downloadCount = attributes.downloadCount,
                            fps = attributes.fps,
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
            ).also { ranked ->
                openSubtitlesRepositoryLogger.i {
                    "stage=search event=completed operation=${DiagnosticOperation.OpenSubtitleSearch.wireValue} " +
                        "queryCount=${queries.size} resultCount=${ranked.size}"
                }
            }
        }

    override suspend fun download(fileId: String): OpenSubtitleDownload =
        withContext(dispatcher) {
            val response = api.createDownload(requireApiKey(), fileId)
            OpenSubtitleDownload(api.download(response.link), response.remaining, response.resetTime)
        }

    private suspend fun requireApiKey(): String =
        settings.apiKey()
            ?: developerApiKey?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("OpenSubtitles API key is not configured.")
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

private val openSubtitlesRepositoryLogger = diagnosticLogger(DiagnosticTag.OpenSubtitles)
