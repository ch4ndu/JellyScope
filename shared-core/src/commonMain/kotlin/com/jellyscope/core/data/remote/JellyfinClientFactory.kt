// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class JellyfinClientFactory(
    private val enableHttpLogging: Boolean = false,
) {
    fun create(followRedirects: Boolean = true): HttpClient =
        HttpClient {
            configure(
                followRedirects = followRedirects,
                timeoutPolicy = TimeoutPolicy.Ordinary,
                loggingEnabled = enableHttpLogging,
            )
        }

    internal fun create(
        engine: HttpClientEngine,
        followRedirects: Boolean = true,
    ): HttpClient =
        HttpClient(engine) {
            configure(
                followRedirects = followRedirects,
                timeoutPolicy = TimeoutPolicy.Ordinary,
                loggingEnabled = enableHttpLogging,
            )
        }

    /**
     * Long-body client for app-owned download localization. Redirects and HTTP
     * logging stay disabled so a transfer cannot move credentials to a new
     * origin or emit source-identifying request URLs.
     */
    fun createDownloadTransfer(): HttpClient =
        HttpClient {
            configure(
                followRedirects = false,
                timeoutPolicy = TimeoutPolicy.DownloadTransfer,
                loggingEnabled = false,
            )
        }

    internal fun createDownloadTransfer(engine: HttpClientEngine): HttpClient =
        HttpClient(engine) {
            configure(
                followRedirects = false,
                timeoutPolicy = TimeoutPolicy.DownloadTransfer,
                loggingEnabled = false,
            )
        }

    private fun HttpClientConfig<*>.configure(
        followRedirects: Boolean,
        timeoutPolicy: TimeoutPolicy,
        loggingEnabled: Boolean,
    ) {
        // Ktor's built-in HttpRedirect (installed when followRedirects is true)
        // already strips the Authorization header on cross-authority redirects and
        // refuses HTTPS->HTTP downgrades. This shared client authenticates only via
        // the Authorization header, so that is sufficient; player HTTP stacks that
        // also carry token-only Authorization / ApiKey handle their own redirect
        // origin checks.
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
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = timeoutPolicy.requestTimeoutMillis
            socketTimeoutMillis = timeoutPolicy.socketTimeoutMillis
        }

        if (loggingEnabled) {
            install(Logging) {
                filter { request ->
                    !request.url.buildString().contains("/QuickConnect/Connect")
                }
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

    private companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val SOCKET_TIMEOUT_MS = 30_000L
        private const val DOWNLOAD_SOCKET_TIMEOUT_MS = 120_000L

        val httpLogger =
            co.touchlab.kermit.Logger
                .withTag("JellyfinHttp")
    }

    private enum class TimeoutPolicy(
        val requestTimeoutMillis: Long,
        val socketTimeoutMillis: Long,
    ) {
        Ordinary(
            requestTimeoutMillis = REQUEST_TIMEOUT_MS,
            socketTimeoutMillis = SOCKET_TIMEOUT_MS,
        ),
        DownloadTransfer(
            requestTimeoutMillis = io.ktor.client.plugins.HttpTimeoutConfig.INFINITE_TIMEOUT_MS,
            socketTimeoutMillis = DOWNLOAD_SOCKET_TIMEOUT_MS,
        ),
    }
}
