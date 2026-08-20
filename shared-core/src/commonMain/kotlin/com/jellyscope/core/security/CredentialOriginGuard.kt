// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.security

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.parseUrl

internal const val JELLYFIN_API_KEY_QUERY_NAME = "ApiKey"

data class CredentialResourceDecision(
    val sanitizedUrl: String,
    val attachCredentials: Boolean,
)

class CredentialOriginGuard(
    private val serverBaseUrl: String,
) {
    /**
     * Sanitizes a resource before any platform player request is built. Trust is
     * evaluated from the original candidate so removing an inbound credential
     * cannot turn an untrusted URL into a trusted one.
     */
    fun decideResourceCredentials(candidateUrl: String): CredentialResourceDecision =
        CredentialResourceDecision(
            sanitizedUrl =
                if (candidateUrl.hasExplicitNonHttpScheme()) {
                    candidateUrl
                } else {
                    stripAuthQueryParams(candidateUrl)
                },
            attachCredentials = mayAttachCredentials(candidateUrl),
        )

    fun mayAttachCredentials(candidateUrl: String): Boolean {
        val server = parseHttpUrl(serverBaseUrl) ?: return false
        val candidate = resolveCandidateUrl(server, candidateUrl) ?: return false
        if (!server.hasTrustedAuthority() || !candidate.hasTrustedAuthority()) return false

        return candidate.protocol.name.equals(server.protocol.name, ignoreCase = true) &&
            candidate.host.equals(server.host, ignoreCase = true) &&
            candidate.port == server.port &&
            candidate.pathSegmentsUnder(server)
    }

    /**
     * Returns an authorized URL only when the original URL is trusted by this
     * server.  Native playback engines cannot use the app's authenticated HTTP
     * header bridge, so this is deliberately stricter than ordinary URL
     * normalization: inbound auth parameters are removed and exactly one
     * current modern ApiKey is appended.
     */
    fun authorizedUrl(
        candidateUrl: String,
        apiKey: String,
    ): String? {
        if (apiKey.isBlank() || !mayAttachCredentials(candidateUrl)) return null
        val server = parseHttpUrl(serverBaseUrl) ?: return null
        val candidate = resolveCandidateUrl(server, candidateUrl) ?: return null
        val stripped = stripAuthQueryParams(candidate.toString())
        return URLBuilder(stripped)
            .apply { parameters.append(JELLYFIN_API_KEY_QUERY_NAME, apiKey) }
            .buildString()
    }

    private fun resolveCandidateUrl(
        server: Url,
        candidateUrl: String,
    ): Url? {
        val candidate = candidateUrl.trim()
        if (candidate.isBlank() || candidate.any(Char::isWhitespace) || candidate.startsWith("//") || '\\' in candidate) {
            return null
        }

        return if (candidate.startsWith("http://", ignoreCase = true) ||
            candidate.startsWith("https://", ignoreCase = true)
        ) {
            parseHttpUrl(candidate)
        } else {
            if (URL_SCHEME_PREFIX.containsMatchIn(candidate)) return null
            parseHttpUrl("${serverBaseUrl.trimEnd('/')}/${candidate.trimStart('/')}")
        }
    }

    private fun parseHttpUrl(value: String): Url? =
        parseUrl(value.trim())?.takeIf { url ->
            url.protocol.name.equals("http", ignoreCase = true) ||
                url.protocol.name.equals("https", ignoreCase = true)
        }

    private fun Url.hasTrustedAuthority(): Boolean =
        user == null &&
            password == null &&
            host.isNotBlank() &&
            host.all { char -> char.code in 0..127 } &&
            host.split('.').none { label -> label.startsWith("xn--", ignoreCase = true) }

    private fun Url.pathSegmentsUnder(server: Url): Boolean {
        val serverSegments = server.segments.takeIf { values -> values.hasNoTraversal() } ?: return false
        val candidateSegments = segments.takeIf { values -> values.hasNoTraversal() } ?: return false
        if (candidateSegments.size < serverSegments.size) return false
        return candidateSegments.take(serverSegments.size) == serverSegments
    }

    companion object {
        fun stripAuthQueryParams(url: String): String {
            val queryStart = url.indexOf('?')
            if (queryStart < 0) return url

            val fragmentStart = url.indexOf('#', startIndex = queryStart + 1)
            val queryEnd = fragmentStart.takeIf { index -> index >= 0 } ?: url.length
            val retainedPairs =
                url
                    .substring(queryStart + 1, queryEnd)
                    .split('&')
                    .filter { part ->
                        part.substringBefore('=').decodeQueryName()?.let { name ->
                            !name.isAuthQueryParamName()
                        } ?: false
                    }
            val fragment = fragmentStart.takeIf { index -> index >= 0 }?.let(url::substring).orEmpty()
            return if (retainedPairs.isEmpty()) {
                url.substring(0, queryStart) + fragment
            } else {
                url.substring(0, queryStart) + "?${retainedPairs.joinToString("&")}$fragment"
            }
        }

        private val URL_SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

        // "apikey" covers Jellyfin's own `ApiKey` spelling (emitted in generated
        // subtitle/playlist DeliveryUrls) in addition to the `api_key` form; matched
        // case-insensitively in isAuthQueryParamName.
        private val AUTH_QUERY_PARAM_NAMES = setOf("api_key", "apikey", "access_token", "token")

        private fun List<String>.hasNoTraversal(): Boolean = none { segment -> segment == "." || segment == ".." }

        private fun String.isAuthQueryParamName(): Boolean = AUTH_QUERY_PARAM_NAMES.any { name -> equals(name, ignoreCase = true) }

        /** Decodes only a raw query name for classification; the original pair is retained. */
        private fun String.decodeQueryName(): String? {
            if ('%' !in this) return this

            val decoded = StringBuilder(length)
            var index = 0
            while (index < length) {
                val character = this[index]
                if (character != '%') {
                    decoded.append(character)
                    index += 1
                    continue
                }

                if (index + 2 >= length) return null
                val high = this[index + 1].hexValue() ?: return null
                val low = this[index + 2].hexValue() ?: return null
                decoded.append((high * 16 + low).toChar())
                index += 3
            }
            return decoded.toString()
        }

        private fun Char.hexValue(): Int? =
            when (this) {
                in '0'..'9' -> code - '0'.code
                in 'a'..'f' -> code - 'a'.code + 10
                in 'A'..'F' -> code - 'A'.code + 10
                else -> null
            }

        private fun String.hasExplicitNonHttpScheme(): Boolean {
            val scheme = URL_SCHEME_PREFIX.find(trim())?.value?.dropLast(1) ?: return false
            return !scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)
        }
    }
}
