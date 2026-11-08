// SPDX-License-Identifier: MPL-2.0

@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.jellyscope.core.data.remote

import com.jellyscope.core.domain.model.SeerrCatalog
import com.jellyscope.core.domain.model.SeerrCatalogPage
import com.jellyscope.core.domain.model.SeerrError
import com.jellyscope.core.domain.model.SeerrLoginMethod
import com.jellyscope.core.domain.model.SeerrMediaKey
import com.jellyscope.core.domain.model.SeerrMediaType
import com.jellyscope.core.domain.model.SeerrQuality
import com.jellyscope.core.domain.model.SeerrQuotas
import com.jellyscope.core.domain.model.SeerrRequestIntent
import com.jellyscope.core.domain.model.SeerrRequestSummary
import com.jellyscope.core.domain.model.SeerrRequestsPage
import com.jellyscope.core.domain.model.SeerrServiceInfo
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodedPath
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.native.HiddenFromObjC
import kotlin.time.Clock

/**
 * Inject a dedicated, nonlogging client with no redirects, retries, default headers,
 * authentication or cookie plugins. The manager owns and closes that client.
 * Cookies are values: only the repository may commit a response's cookie snapshot.
 */
@HiddenFromObjC
class SeerrApi(
    private val client: HttpClient,
    private val json: Json,
) {
    init {
        require(client.pluginOrNull(HttpRedirect) == null)
        require(client.pluginOrNull(Logging) == null)
        require(client.pluginOrNull(HttpRequestRetry) == null)
        require(client.pluginOrNull(HttpCookies) == null)
        require(client.pluginOrNull(DefaultRequest) == null)
    }

    suspend fun status(session: SeerrTransportSession): SeerrApiResponse<String> =
        get(session, "status") { it.requiredText("version").take(80) }

    suspend fun publicSettings(
        session: SeerrTransportSession,
        version: String,
    ): SeerrApiResponse<SeerrServiceInfo> = get(session, "settings/public") { it.toServiceInfo(session.endpoint, version) }

    suspend fun login(
        session: SeerrTransportSession,
        method: SeerrLoginMethod,
        identifier: String,
        password: String,
    ): SeerrApiResponse<Unit> =
        post(
            session,
            if (method == SeerrLoginMethod.Local) "auth/local" else "auth/jellyfin",
            buildJsonObject {
                put(if (method == SeerrLoginMethod.Local) "email" else "username", identifier)
                put("password", password)
            },
            login = true,
        ) { Unit }

    suspend fun me(session: SeerrTransportSession): SeerrApiResponse<SeerrWireUser> = get(session, "auth/me") { it.toUser() }

    suspend fun beginQuickConnect(session: SeerrTransportSession): SeerrApiResponse<SeerrWireQuickConnect> =
        post(session, "auth/jellyfin/quickconnect/initiate", buildJsonObject {}, login = true) {
            val code = it.requiredText("code")
            val secret = it.requiredText("secret")
            require(code.length in 1..32 && code.all { character -> character.isLetterOrDigit() })
            require(secret.length in 8..128 && secret.all { character -> character in "0123456789abcdefABCDEF" })
            SeerrWireQuickConnect(code, secret)
        }

    suspend fun checkQuickConnect(
        session: SeerrTransportSession,
        secret: String,
    ): SeerrApiResponse<Boolean> =
        get(session, "auth/jellyfin/quickconnect/check", listOf("secret" to secret)) {
            it.boolean("authenticated") ?: error("Invalid Quick Connect response")
        }

    suspend fun completeQuickConnect(
        session: SeerrTransportSession,
        secret: String,
    ): SeerrApiResponse<Unit> =
        post(session, "auth/jellyfin/quickconnect/authenticate", buildJsonObject { put("secret", secret) }, login = true) { Unit }

    suspend fun logout(session: SeerrTransportSession): SeerrApiResponse<Unit> = post(session, "auth/logout", buildJsonObject {}) { Unit }

    suspend fun catalog(
        session: SeerrTransportSession,
        catalog: SeerrCatalog,
        page: Int,
        query: String,
    ): SeerrApiResponse<SeerrCatalogPage> {
        require(page > 0)
        require(query.length <= 300)
        val path =
            when (catalog) {
                SeerrCatalog.Trending -> "discover/trending"
                SeerrCatalog.Movies -> "discover/movies"
                SeerrCatalog.Tv -> "discover/tv"
                SeerrCatalog.Search -> "search"
            }
        val parameters =
            listOf("page" to page.toString()) +
                if (catalog == SeerrCatalog.Search) listOf("query" to query) else emptyList()
        return get(session, path, parameters) { it.toCatalogPage(catalog, page) }
    }

    suspend fun similar(
        session: SeerrTransportSession,
        key: SeerrMediaKey,
        page: Int = 1,
    ): SeerrApiResponse<SeerrCatalogPage> {
        require(page == 1)
        val catalog = if (key.type == SeerrMediaType.Movie) SeerrCatalog.Movies else SeerrCatalog.Tv
        return get(session, "${key.type.wireName()}/${key.tmdbId}/similar", listOf("page" to page.toString())) {
            it.toCatalogPage(catalog, page)
        }
    }

    suspend fun detail(
        session: SeerrTransportSession,
        key: SeerrMediaKey,
    ): SeerrApiResponse<SeerrWireDetail> = get(session, "${key.type.wireName()}/${key.tmdbId}") { it.toDetail(key) }

    suspend fun quota(
        session: SeerrTransportSession,
        userId: Int,
    ): SeerrApiResponse<SeerrQuotas> {
        require(userId > 0)
        return get(session, "user/$userId/quota") { it.toQuotas() }
    }

    suspend fun requests(
        session: SeerrTransportSession,
        userId: Int,
        skip: Int,
    ): SeerrApiResponse<SeerrRequestsPage> {
        require(userId > 0 && skip in 0..MAX_REQUEST_SKIP)
        return get(session, "user/$userId/requests", listOf("take" to REQUEST_PAGE_SIZE.toString(), "skip" to skip.toString())) {
            it.toRequestsPage(skip)
        }
    }

    suspend fun request(
        session: SeerrTransportSession,
        intent: SeerrRequestIntent,
    ): SeerrApiResponse<SeerrRequestSummary> =
        post(
            session,
            "request",
            buildJsonObject {
                put("mediaType", intent.key.type.wireName())
                put("mediaId", intent.key.tmdbId)
                put("is4k", intent.quality == SeerrQuality.FourK)
                if (intent.key.type == SeerrMediaType.Tv) put("seasons", JsonArray(intent.seasons.map(::JsonPrimitive)))
            },
            creation = true,
        ) { payload ->
            val media = requireNotNull(payload.obj("media"))
            require(media.integer("tmdbId") == intent.key.tmdbId)
            require(media.text("mediaType") == intent.key.type.wireName())
            val requestStatus = payload.integer("status")
            require(requestStatus != null && requestStatus in 1..5)
            payload.toRequest(intent.key).also { request ->
                require(request.quality == intent.quality)
                if (intent.key.type == SeerrMediaType.Tv) {
                    // Seerr filters seasons already requested or available at creation time.
                    require(request.seasons.isNotEmpty() && intent.seasons.containsAll(request.seasons))
                }
            }
        }

    private suspend fun <T> get(
        session: SeerrTransportSession,
        path: String,
        parameters: List<Pair<String, String>> = emptyList(),
        decode: (JsonObject) -> T,
    ): SeerrApiResponse<T> = exchange(session, path, HttpMethod.Get, parameters, null, false, false, decode)

    private suspend fun <T> post(
        session: SeerrTransportSession,
        path: String,
        body: JsonObject,
        login: Boolean = false,
        creation: Boolean = false,
        decode: (JsonObject) -> T,
    ): SeerrApiResponse<T> = exchange(session, path, HttpMethod.Post, emptyList(), body, login, creation, decode)

    private suspend fun <T> exchange(
        session: SeerrTransportSession,
        path: String,
        method: HttpMethod,
        queryParameters: List<Pair<String, String>>,
        body: JsonObject?,
        login: Boolean,
        creation: Boolean,
        decode: (JsonObject) -> T,
    ): SeerrApiResponse<T> {
        var cookies = session.cookies
        var status: Int? = null
        return try {
            val endpoint = normalizeSeerrEndpoint(session.endpoint)
            require(endpoint == session.endpoint)
            require(validSeerrCookies(endpoint, cookies))
            val url =
                URLBuilder("$endpoint/api/v1/$path")
                    .apply {
                        queryParameters.forEach { (name, value) -> parameters.append(name, value) }
                    }.build()
            val now = Clock.System.now().toEpochMilliseconds()
            val applicable = cookies.filter { it.appliesTo(url, now) }.sortedByDescending { it.path.length }
            if (method != HttpMethod.Get &&
                url.protocol.name != "https" &&
                cookies.any { it.name in csrfCookieNames && it.secure && !it.expired(now) }
            ) {
                return SeerrApiResponse(error = SeerrError.HttpsRequired, cookies = cookies)
            }
            withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
                client
                    .prepareRequest(url) {
                        this.method = method
                        header(HttpHeaders.Accept, "application/json")
                        if (applicable.isNotEmpty()) header(HttpHeaders.Cookie, applicable.joinToString("; ") { "${it.name}=${it.value}" })
                        if (method != HttpMethod.Get) {
                            applicable.firstOrNull { it.name == "XSRF-TOKEN" }?.let {
                                val token = it.value.decodeURLQueryComponent(plusIsSpace = false)
                                require(token.all { character -> character.code in 33..126 })
                                header("X-XSRF-TOKEN", token)
                            }
                            header(HttpHeaders.ContentType, "application/json")
                            setBody(body.toString())
                        }
                    }.execute { response ->
                        status = response.status.value
                        val channel = response.bodyAsChannel()
                        try {
                            // A redirect is never followed and never contributes credentials to the jar.
                            if (response.status.value in 300..399) {
                                return@execute SeerrApiResponse<T>(
                                    error = SeerrError.RedirectRejected,
                                    cookies = cookies,
                                    httpStatus = status,
                                )
                            }
                            cookies = rotateCookies(session, url, response.headers.getAll(HttpHeaders.SetCookie).orEmpty(), now)
                            val successful = response.status.value in 200..299
                            val text = channel.readSeerrBody(if (successful) MAX_RESPONSE_BYTES else MAX_ERROR_BYTES)
                            val payload =
                                try {
                                    json.parseToJsonElement(text) as? JsonObject
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    logger.w {
                                        formatSafeFailureDiagnostic("seerr-response", "parse-failed", failure) +
                                            " reason=InvalidResponse"
                                    }
                                    null
                                }
                            val error =
                                when {
                                    payload.isCsrfRejection() -> SeerrError.SecurityRejected
                                    creation && response.status.value == 202 -> SeerrError.NoSeasonsAvailable
                                    creation && successful && response.status.value != 201 -> SeerrError.ReconciliationRequired
                                    !successful -> mapHttpFailure(response.status.value, payload, login, path == "auth/me", creation)
                                    else -> null
                                }
                            if (error != null) {
                                SeerrApiResponse<T>(error = error, cookies = cookies, httpStatus = status)
                            } else {
                                try {
                                    SeerrApiResponse(value = decode(requireNotNull(payload)), cookies = cookies, httpStatus = status)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: SeerrProtocolException) {
                                    logger.w {
                                        formatSafeFailureDiagnostic("seerr-response", "protocol-rejected", failure) +
                                            " reason=${failure.error.name}"
                                    }
                                    SeerrApiResponse<T>(error = failure.error, cookies = cookies, httpStatus = status)
                                } catch (failure: Exception) {
                                    logger.w {
                                        formatSafeFailureDiagnostic("seerr-response", "decode-failed", failure) +
                                            " reason=InvalidResponse"
                                    }
                                    SeerrApiResponse<T>(
                                        error = if (creation) SeerrError.ReconciliationRequired else SeerrError.InvalidResponse,
                                        cookies = cookies,
                                        httpStatus = status,
                                    )
                                }
                            }
                        } finally {
                            channel.cancel(null)
                        }
                    }
            }
                ?: run {
                    logger.w { "stage=seerr-transport event=timed-out reason=Timeout" }
                    SeerrApiResponse(
                        error = if (creation) SeerrError.ReconciliationRequired else SeerrError.NetworkUnavailable,
                        cookies = cookies,
                    )
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            logger.w {
                formatSafeFailureDiagnostic("seerr-transport", "exchange-failed", failure) +
                    " reason=NetworkUnavailable"
            }
            SeerrApiResponse(
                error = if (creation) SeerrError.ReconciliationRequired else SeerrError.NetworkUnavailable,
                cookies = cookies,
                httpStatus = status,
            )
        }
    }
}

@HiddenFromObjC
data class SeerrTransportSession(
    val endpoint: String,
    val cookies: List<SeerrCookie> = emptyList(),
) {
    override fun toString(): String = "SeerrTransportSession([redacted])"
}

@HiddenFromObjC
data class SeerrApiResponse<T>(
    val value: T? = null,
    val error: SeerrError? = null,
    val cookies: List<SeerrCookie>,
    val httpStatus: Int? = null,
) {
    override fun toString(): String = "SeerrApiResponse([redacted])"
}

@HiddenFromObjC
@Serializable
data class SeerrCookie(
    val name: String,
    val value: String,
    val host: String,
    val path: String,
    val secure: Boolean,
    val expiresAtEpochMillis: Long?,
) {
    override fun toString(): String = "SeerrCookie([redacted])"

    fun expired(now: Long): Boolean = expiresAtEpochMillis?.let { it <= now } == true

    internal fun appliesTo(
        url: Url,
        now: Long,
    ): Boolean =
        host.equals(url.host, ignoreCase = true) &&
            !expired(now) &&
            (!secure || url.protocol.name == "https") &&
            cookiePathMatches(path, url.encodedPath)
}

/** Reject ambiguous URL syntax instead of repairing credential destinations. */
@HiddenFromObjC
fun normalizeSeerrEndpoint(input: String): String {
    val value = input.trim()
    require(value.length in 1..2048 && value.none { it.isWhitespace() || it.code < 32 || it == '\\' })
    require(value.startsWith("https://", true) || value.startsWith("http://", true))
    require('?' !in value && '#' !in value)
    val url = Url(value)
    require(url.user == null && url.password == null && url.host.isNotBlank())
    require(url.protocol.name == "https" || url.protocol.name == "http")
    require(url.host.all { it.code in 33..126 } && !url.host.contains("%"))
    require(url.port in 1..65535)
    val path = url.encodedPath.trimEnd('/')
    require(path.split('/').none { it == "." || it == ".." })
    require('%' !in path && "//" !in path)
    return URLBuilder(url).apply { encodedPath = path }.buildString().trimEnd('/')
}

@HiddenFromObjC
fun validSeerrCookies(
    endpoint: String,
    cookies: List<SeerrCookie>,
): Boolean =
    cookies.size <= MAX_COOKIES &&
        cookies.all { cookie ->
            cookie.host.equals(Url(endpoint).host, true) &&
                cookie.name.matches(cookieName) &&
                cookie.value.length <= MAX_COOKIE_VALUE &&
                cookie.value.all { it.code in 33..126 && it !in ";,\"\\" } &&
                cookie.path.startsWith('/') &&
                cookie.path.length <= 2048 &&
                cookie.path.all { it.code in 33..126 && it !in ";\\?#" } &&
                cookie.path.split('/').none { it == "." || it == ".." } &&
                '%' !in cookie.path
        } &&
        cookies.map { it.name to it.path }.distinct().size == cookies.size

private fun rotateCookies(
    session: SeerrTransportSession,
    url: Url,
    headers: List<String>,
    now: Long,
): List<SeerrCookie> {
    require(headers.size <= MAX_COOKIES)
    val result = session.cookies.filterNot { it.expired(now) }.toMutableList()
    for (header in headers) {
        require(header.length <= MAX_COOKIE_VALUE + 2048)
        val cookie = parseServerSetCookieHeader(header)
        val domain = cookie.domain?.removePrefix(".")
        if (domain != null && !domain.equals(url.host, true)) continue
        val path = cookie.path?.takeIf { it.startsWith('/') } ?: url.encodedPath.substringBeforeLast('/').ifEmpty { "/" }
        val expires = cookie.maxAge?.let { now + it.toLong() * 1000 } ?: cookie.expires?.timestamp
        val stored = SeerrCookie(cookie.name, cookie.value, url.host.lowercase(), path, cookie.secure, expires)
        require(validSeerrCookies(session.endpoint, listOf(stored)))
        result.removeAll { it.name == stored.name && it.path == stored.path }
        if (!stored.expired(now) && stored.value.isNotEmpty()) result += stored
    }
    require(validSeerrCookies(session.endpoint, result))
    return result.toList()
}

private fun cookiePathMatches(
    cookiePath: String,
    requestPath: String,
): Boolean =
    requestPath == cookiePath ||
        (
            requestPath.startsWith(cookiePath) &&
                (cookiePath.endsWith('/') || requestPath.getOrNull(cookiePath.length) == '/')
        )

private suspend fun ByteReadChannel.readSeerrBody(limit: Int): String {
    var buffer = ByteArray(minOf(8192, limit + 1))
    var count = 0
    while (true) {
        if (count == buffer.size) buffer = buffer.copyOf(minOf(buffer.size * 2, limit + 1))
        val read = readAvailable(buffer, count, buffer.size - count)
        if (read < 0) return buffer.decodeToString(0, count, throwOnInvalidSequence = true)
        count += read
        require(count <= limit)
    }
}

private fun JsonObject?.isCsrfRejection(): Boolean =
    this?.text("code") == "EBADCSRFTOKEN" ||
        this?.text("message") == "invalid csrf token" ||
        this?.text("error") == "invalid csrf token"

private fun mapHttpFailure(
    status: Int,
    body: JsonObject?,
    login: Boolean,
    me: Boolean,
    creation: Boolean,
): SeerrError =
    when {
        me && status in listOf(401, 403) -> SeerrError.SessionExpired
        login && status in listOf(400, 401, 403) -> SeerrError.InvalidCredentials
        status == 401 -> SeerrError.SessionExpired
        status == 403 ->
            when {
                body?.text("message")?.contains("quota", ignoreCase = true) == true -> SeerrError.QuotaExceeded
                body?.text("message")?.contains("blocklist", ignoreCase = true) == true -> SeerrError.Blocklisted
                else -> SeerrError.PermissionDenied
            }
        status == 409 -> SeerrError.AlreadyRequested
        status == 429 -> SeerrError.RateLimited
        status == 404 -> SeerrError.MetadataUnavailable
        status in 400..499 -> SeerrError.InvalidSelection
        creation -> SeerrError.ReconciliationRequired
        else -> SeerrError.NetworkUnavailable
    }

internal fun SeerrMediaType.wireName(): String = if (this == SeerrMediaType.Movie) "movie" else "tv"

internal const val REQUEST_PAGE_SIZE = 20
internal const val MAX_REQUEST_SKIP = Int.MAX_VALUE - REQUEST_PAGE_SIZE
private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
private const val MAX_ERROR_BYTES = 8192
private const val REQUEST_TIMEOUT_MILLIS = 30_000L
private const val MAX_COOKIES = 16
private const val MAX_COOKIE_VALUE = 4096
private val csrfCookieNames = setOf("_csrf", "XSRF-TOKEN")
private val cookieName = Regex("[A-Za-z0-9_!#$&+.^`|~-]{1,128}")
private val logger = diagnosticLogger(DiagnosticTag.MediaRepository)
