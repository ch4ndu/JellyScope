// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal class OpenSubtitlesApi(
    private val apiClient: HttpClient,
    private val downloadClient: HttpClient,
    private val userAgent: String,
) {
    suspend fun search(
        apiKey: String,
        query: OpenSubtitlesQuery,
    ): OpenSubtitlesSearchResponse {
        val url =
            URLBuilder(OPEN_SUBTITLES_API)
                .apply {
                    appendPathSegments("subtitles")
                    query.imdbId?.let { parameters.append("imdb_id", it.removePrefix("tt")) }
                    query.title?.let { parameters.append("query", it) }
                    query.year?.let { parameters.append("year", it.toString()) }
                    query.seasonNumber?.let { parameters.append("season_number", it.toString()) }
                    query.episodeNumber?.let { parameters.append("episode_number", it.toString()) }
                    parameters.append("languages", query.language)
                }.buildString()
        return apiClient.get(url) { openSubtitlesHeaders(apiKey) }.checked().body()
    }

    suspend fun createDownload(
        apiKey: String,
        fileId: String,
    ): OpenSubtitlesDownloadResponse =
        apiClient
            .post("$OPEN_SUBTITLES_API/download") {
                openSubtitlesHeaders(apiKey)
                header(HttpHeaders.ContentType, "application/json")
                setBody(OpenSubtitlesDownloadRequest(fileId))
            }.checked()
            .body()

    suspend fun download(url: String): ByteArray {
        var currentUrl = Url(url).also(::requireSafeDownloadUrl)
        repeat(MAX_DOWNLOAD_REDIRECTS + 1) { redirectCount ->
            val response = downloadClient.get(currentUrl)
            val channel = response.bodyAsChannel()
            try {
                if (response.status.value in 200..299) {
                    response.headers[HttpHeaders.ContentLength]
                        ?.toLongOrNull()
                        ?.let { length -> require(length <= MAX_DOWNLOAD_BYTES) { DOWNLOAD_SIZE_ERROR } }
                    return channel.readBoundedSubtitle()
                }
                if (response.status !in redirectStatuses || redirectCount == MAX_DOWNLOAD_REDIRECTS) {
                    response.checked()
                }
                val location =
                    response.headers[HttpHeaders.Location]
                        ?: throw IllegalArgumentException("OpenSubtitles redirect has no location.")
                currentUrl = resolveRedirect(currentUrl, location).also(::requireSafeDownloadUrl)
            } finally {
                channel.cancel(null)
            }
        }
        error("OpenSubtitles download exceeded its redirect limit.")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.openSubtitlesHeaders(apiKey: String) {
        header("Api-Key", apiKey)
        header(HttpHeaders.UserAgent, userAgent)
        header(HttpHeaders.Accept, "application/json")
    }
}

private suspend fun io.ktor.utils.io.ByteReadChannel.readBoundedSubtitle(): ByteArray {
    var buffer = ByteArray(INITIAL_SUBTITLE_BUFFER_BYTES)
    var count = 0
    while (true) {
        if (count == buffer.size) {
            buffer = buffer.copyOf(minOf(buffer.size * 2, MAX_DOWNLOAD_BYTES + 1))
        }
        val read = readAvailable(buffer, count, buffer.size - count)
        if (read < 0) return buffer.copyOf(count)
        if (read == 0) continue
        count += read
        require(count <= MAX_DOWNLOAD_BYTES) { DOWNLOAD_SIZE_ERROR }
    }
}

private fun resolveRedirect(
    base: Url,
    location: String,
): Url {
    val trimmed = location.trim()
    val authority =
        buildString {
            append(base.host)
            if (base.port != base.protocol.defaultPort) append(':').append(base.port)
        }
    val absolute =
        when {
            trimmed.startsWith("//") -> "${base.protocol.name}:$trimmed"
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> {
                val fragmentStart = trimmed.indexOf('#')
                val fragment = fragmentStart.takeIf { index -> index >= 0 }?.let(trimmed::substring)?.orEmpty()
                val withoutFragment =
                    if (fragmentStart >= 0) trimmed.substringBefore('#') else trimmed
                val queryStart = withoutFragment.indexOf('?')
                val locationPath =
                    if (queryStart >= 0) withoutFragment.substringBefore('?') else withoutFragment
                val query =
                    queryStart.takeIf { index -> index >= 0 }?.let(withoutFragment::substring)
                        ?: if (locationPath.isEmpty()) {
                            base.encodedQuery
                                .takeIf { query -> query.isNotEmpty() }
                                ?.let { "?$it" }
                                .orEmpty()
                        } else {
                            ""
                        }
                val path =
                    when {
                        locationPath.startsWith("/") -> normalizeRedirectPath(locationPath)
                        locationPath.isEmpty() -> base.encodedPath
                        else -> {
                            val directory =
                                if (base.encodedPath.endsWith('/')) {
                                    base.encodedPath
                                } else {
                                    base.encodedPath.substringBeforeLast('/', missingDelimiterValue = "")
                                }
                            val combinedPath =
                                when {
                                    directory.isEmpty() -> "/$locationPath"
                                    directory.endsWith('/') -> "$directory$locationPath"
                                    else -> "$directory/$locationPath"
                                }
                            normalizeRedirectPath(combinedPath)
                        }
                    }
                "${base.protocol.name}://$authority$path$query$fragment"
            }
        }
    return Url(absolute)
}

private fun normalizeRedirectPath(path: String): String {
    val leadingSlash = path.startsWith('/')
    val trailingSlash = path.endsWith('/')
    val segments = mutableListOf<String>()
    path.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
            else -> segments += segment
        }
    }
    val normalized = segments.joinToString("/")
    return buildString {
        if (leadingSlash) append('/')
        append(normalized)
        if (trailingSlash && normalized.isNotEmpty()) append('/')
    }.ifEmpty { if (leadingSlash) "/" else "" }
}

internal data class OpenSubtitlesQuery(
    val imdbId: String? = null,
    val title: String? = null,
    val year: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val language: String,
)

@Serializable internal data class OpenSubtitlesSearchResponse(
    val data: List<OpenSubtitlesResultDto> = emptyList(),
)

@Serializable internal data class OpenSubtitlesResultDto(
    val id: String = "",
    val attributes: OpenSubtitlesAttributesDto = OpenSubtitlesAttributesDto(),
)

@Serializable internal data class OpenSubtitlesAttributesDto(
    val language: String = "",
    @SerialName("release") val releaseName: String? = null,
    @SerialName("hearing_impaired") val hearingImpaired: Boolean = false,
    @SerialName("foreign_parts_only") val forced: Boolean = false,
    @SerialName("from_trusted") val trusted: Boolean = false,
    val ratings: Double? = null,
    val fps: Double? = null,
    @SerialName("download_count") val downloadCount: Long? = null,
    val files: List<OpenSubtitlesFileDto> = emptyList(),
)

@Serializable internal data class OpenSubtitlesFileDto(
    @SerialName("file_id") val fileId: Int,
    @SerialName("file_name") val fileName: String,
)

@Serializable internal data class OpenSubtitlesDownloadRequest(
    @SerialName("file_id") val fileId: Int,
) {
    constructor(fileId: String) : this(fileId.toIntOrNull() ?: throw IllegalArgumentException("Invalid OpenSubtitles file id."))
}

@Serializable internal data class OpenSubtitlesDownloadResponse(
    val link: String,
    @SerialName("remaining") val remaining: Int? = null,
    @SerialName("reset_time") val resetTime: String? = null,
)

private suspend fun HttpResponse.checked(): HttpResponse {
    if (status.value !in 200..299) {
        throw when (status.value) {
            401, 403 -> OpenSubtitlesException.InvalidApiKey
            406, 429 -> OpenSubtitlesException.RateLimited
            else -> OpenSubtitlesException.ServerError(status.value)
        }
    }
    return this
}

sealed class OpenSubtitlesException(
    message: String,
) : Exception(message) {
    data object InvalidApiKey : OpenSubtitlesException("OpenSubtitles rejected the API consumer key.")

    data object RateLimited : OpenSubtitlesException("OpenSubtitles download quota is exhausted.")

    data class ServerError(
        val statusCode: Int,
    ) : OpenSubtitlesException("OpenSubtitles returned HTTP $statusCode.")
}

private fun requireSafeDownloadUrl(url: Url) {
    require(url.protocol.name == "https") { "OpenSubtitles download must use HTTPS." }
    val host = url.host.lowercase()
    require(host == "opensubtitles.com" || host.endsWith(".opensubtitles.com")) {
        "OpenSubtitles download host is not allowed."
    }
}

private const val OPEN_SUBTITLES_API = "https://api.opensubtitles.com/api/v1"
private const val INITIAL_SUBTITLE_BUFFER_BYTES = 16 * 1024
private const val MAX_DOWNLOAD_BYTES = 5 * 1024 * 1024
private const val DOWNLOAD_SIZE_ERROR = "Subtitle download exceeds the 5 MiB limit."
private const val MAX_DOWNLOAD_REDIRECTS = 3
private val redirectStatuses =
    setOf(
        HttpStatusCode.MovedPermanently,
        HttpStatusCode.Found,
        HttpStatusCode.SeeOther,
        HttpStatusCode.TemporaryRedirect,
        HttpStatusCode.PermanentRedirect,
    )
