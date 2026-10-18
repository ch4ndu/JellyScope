// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.security.CredentialOriginGuard

internal data class Media3CredentialRequest(
    val sanitizedUrl: String,
    val headers: List<Pair<String, String>>,
)

internal class Media3CredentialRequestPolicy(
    private val credentialOriginGuard: CredentialOriginGuard,
    private val authorizationHeader: String,
) {
    fun resolve(
        resourceUrl: String,
        requestHeaders: Iterable<Pair<String, String>>,
    ): Media3CredentialRequest {
        val decision = credentialOriginGuard.decideResourceCredentials(resourceUrl)
        val sanitizedHeaders =
            requestHeaders
                .filterNot { (name, _) ->
                    CREDENTIAL_HEADER_NAMES.any { credentialName -> credentialName.equals(name, ignoreCase = true) }
                }.toMutableList()
        if (decision.attachCredentials) {
            sanitizedHeaders += AUTHORIZATION_HEADER to authorizationHeader
        }
        return Media3CredentialRequest(
            sanitizedUrl = decision.sanitizedUrl,
            headers = sanitizedHeaders,
        )
    }

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
        val CREDENTIAL_HEADER_NAMES =
            setOf(
                AUTHORIZATION_HEADER,
                "X-Emby-Token",
                "X-MediaBrowser-Token",
            )
    }
}
