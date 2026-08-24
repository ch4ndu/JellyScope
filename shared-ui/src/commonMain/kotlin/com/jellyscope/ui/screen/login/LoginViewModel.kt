// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.domain.action.AuthError
import com.jellyscope.core.domain.action.LoginAction
import com.jellyscope.core.domain.model.ServerInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

class LoginViewModel(
    serverInfo: ServerInfo,
    initialUsername: String,
    initialPassword: String,
    private val loginAction: LoginAction,
    private val workDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            LoginUiState(
                serverInfo = serverInfo,
                username = initialUsername,
                password = initialPassword,
            ),
        )
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private val events = Channel<LoginEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    fun updateUsername(username: String) {
        _state.update { current -> current.copy(username = username, error = null) }
    }

    fun updatePassword(password: String) {
        _state.update { current -> current.copy(password = password, error = null) }
    }

    fun submit() {
        if (_state.value.isSubmitting) {
            return
        }

        viewModelScope.launch {
            val current = _state.value
            _state.update { state -> state.copy(isSubmitting = true, error = null) }
            val result =
                withContext(workDispatcher) {
                    loginAction(
                        current.serverInfo.serverUrl,
                        current.username,
                        current.password,
                    )
                }
            result.fold(
                onSuccess = {
                    _state.update { state -> state.copy(isSubmitting = false) }
                    events.send(LoginEvent.Success)
                },
                onFailure = { throwable ->
                    _state.update { state ->
                        state.copy(
                            isSubmitting = false,
                            error = throwable.toLoginError(),
                        )
                    }
                },
            )
        }
    }
}

private fun Throwable.toLoginError(): LoginError =
    when (this) {
        AuthError.InvalidCredentials -> LoginError.InvalidCredentials
        AuthError.NotReachable -> LoginError.NotReachable
        else -> LoginError.ServerError
    }
