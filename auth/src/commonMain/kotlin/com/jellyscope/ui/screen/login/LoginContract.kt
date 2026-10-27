// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.login

import com.jellyscope.core.domain.model.ServerInfo

data class LoginUiState(
    val serverInfo: ServerInfo,
    val username: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: LoginError? = null,
)

enum class LoginError {
    InvalidCredentials,
    NotReachable,
    ServerError,
}

sealed interface LoginEvent {
    data object Success : LoginEvent
}

sealed interface LoginIntent {
    class UpdateUsername(
        val username: String,
    ) : LoginIntent

    class UpdatePassword(
        val password: String,
    ) : LoginIntent

    data object Submit : LoginIntent
}

internal sealed interface LoginChange {
    class UsernameChanged(
        val username: String,
    ) : LoginChange

    class PasswordChanged(
        val password: String,
    ) : LoginChange

    data object SubmitStarted : LoginChange

    data object SubmitSucceeded : LoginChange

    data class SubmitFailed(
        val error: LoginError,
    ) : LoginChange
}

internal fun reduceLogin(
    current: LoginUiState,
    change: LoginChange,
): LoginUiState =
    when (change) {
        is LoginChange.UsernameChanged -> current.copy(username = change.username, error = null)
        is LoginChange.PasswordChanged -> current.copy(password = change.password, error = null)
        LoginChange.SubmitStarted -> current.copy(isSubmitting = true, error = null)
        LoginChange.SubmitSucceeded -> current.copy(isSubmitting = false)
        is LoginChange.SubmitFailed -> current.copy(isSubmitting = false, error = change.error)
    }
