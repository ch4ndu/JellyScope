// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

sealed class AuthError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    data object InvalidUrl : AuthError("Invalid server URL.")

    data object NotReachable : AuthError("Jellyfin server is not reachable.")

    data object InvalidCredentials : AuthError("Invalid credentials.")

    data class ServerError(
        val statusCode: Int?,
    ) : AuthError("Jellyfin server error.")

    data object QuickConnectExpired : AuthError("Quick Connect code expired or is unknown.")

    data object QuickConnectUnavailable : AuthError("Quick Connect is unavailable.")

    data object AccountNotFound : AuthError("Account was not found.")
}
