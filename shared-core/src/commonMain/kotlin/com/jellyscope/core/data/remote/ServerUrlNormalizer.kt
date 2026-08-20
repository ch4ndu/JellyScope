// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import io.ktor.http.URLParserException
import io.ktor.http.Url

sealed interface ServerUrlNormalizationResult {
    data class Normalized(
        val url: String,
    ) : ServerUrlNormalizationResult

    data class Invalid(
        val reason: String,
    ) : ServerUrlNormalizationResult
}

object ServerUrlNormalizer {
    fun normalize(input: String): ServerUrlNormalizationResult {
        val trimmed = input.trim()

        if (trimmed.isEmpty()) {
            return ServerUrlNormalizationResult.Invalid("Server URL is required.")
        }

        val withScheme =
            if (trimmed.contains("://")) {
                trimmed
            } else {
                "https://$trimmed"
            }

        val authority = withScheme.substringAfter("://").takeWhile { it != '/' }
        if (authority.isBlank()) {
            return ServerUrlNormalizationResult.Invalid("Server URL is invalid.")
        }

        return try {
            val url = Url(withScheme)
            if (url.host.isBlank() || url.protocol.name !in setOf("http", "https")) {
                ServerUrlNormalizationResult.Invalid("Server URL must be http or https.")
            } else {
                val portPart =
                    if (url.port != url.protocol.defaultPort) ":${url.port}" else ""
                val pathPart = url.encodedPath.trimEnd('/')
                ServerUrlNormalizationResult.Normalized(
                    "${url.protocol.name}://${url.host}$portPart$pathPart",
                )
            }
        } catch (exception: URLParserException) {
            ServerUrlNormalizationResult.Invalid("Server URL is invalid.")
        } catch (exception: IllegalArgumentException) {
            ServerUrlNormalizationResult.Invalid("Server URL is invalid.")
        }
    }
}
