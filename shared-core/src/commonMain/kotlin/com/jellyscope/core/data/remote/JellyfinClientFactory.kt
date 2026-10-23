// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine

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
        when (timeoutPolicy) {
            TimeoutPolicy.Ordinary -> configureJellyfinApi(followRedirects, loggingEnabled)
            TimeoutPolicy.DownloadTransfer -> configureJellyfinDownloadTransfer()
        }
    }

    private enum class TimeoutPolicy {
        Ordinary,
        DownloadTransfer,
    }
}
