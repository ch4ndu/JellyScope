// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

sealed class JellyfinApiException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    data object NotReachable : JellyfinApiException("Jellyfin server is not reachable.")

    data object InvalidCredentials : JellyfinApiException("Invalid Jellyfin credentials.")

    data object Unauthorized : JellyfinApiException("Jellyfin session is not authorized.")

    data object PayloadTooLarge : JellyfinApiException("The bounded download payload is too large.")

    data object ClientLogUploadDisallowed : JellyfinApiException("Client log upload is disabled by this server.")

    data object QuickConnectExpired : JellyfinApiException("Quick Connect code expired or is unknown.")

    data object QuickConnectUnavailable : JellyfinApiException("Quick Connect is unavailable.")

    data class ServerError(
        val statusCode: Int,
    ) : JellyfinApiException("Jellyfin server returned HTTP $statusCode.")

    data class Unexpected(
        override val cause: Throwable,
    ) : JellyfinApiException("Unexpected Jellyfin API failure.", cause)
}
