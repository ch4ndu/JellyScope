// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

@OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
@kotlin.native.HiddenFromObjC
fun HttpClientConfig<*>.configureJellyfinApi(
    followRedirects: Boolean = true,
    enableHttpLogging: Boolean = false,
) {
    configureJellyfin(followRedirects, TimeoutPolicy.Ordinary, enableHttpLogging)
}

@OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
@kotlin.native.HiddenFromObjC
fun HttpClientConfig<*>.configureJellyfinDownloadTransfer() {
    configureJellyfin(false, TimeoutPolicy.DownloadTransfer, false)
}

private fun HttpClientConfig<*>.configureJellyfin(
    followRedirects: Boolean,
    timeoutPolicy: TimeoutPolicy,
    loggingEnabled: Boolean,
) {
    // Ktor strips Authorization on cross-authority redirects and refuses
    // HTTPS-to-HTTP redirects. Non-Ktor players retain their own origin checks.
    this.followRedirects = followRedirects
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000L
        requestTimeoutMillis = timeoutPolicy.requestTimeoutMillis
        socketTimeoutMillis = timeoutPolicy.socketTimeoutMillis
    }
    if (loggingEnabled) {
        install(Logging) {
            filter { request -> !request.url.buildString().contains("/QuickConnect/Connect") }
            logger =
                object : Logger {
                    override fun log(message: String) {
                        httpLogger.d { message }
                    }
                }
            level = LogLevel.HEADERS
            sanitizeHeader { header -> header == HttpHeaders.Authorization }
        }
    }
}

private val httpLogger =
    co.touchlab.kermit.Logger
        .withTag("JellyfinHttp")

private enum class TimeoutPolicy(
    val requestTimeoutMillis: Long,
    val socketTimeoutMillis: Long,
) {
    Ordinary(30_000L, 30_000L),
    DownloadTransfer(HttpTimeoutConfig.INFINITE_TIMEOUT_MS, 120_000L),
}
