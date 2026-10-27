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

    fun accept(intent: LoginIntent) {
        when (intent) {
            is LoginIntent.UpdateUsername -> reduce(LoginChange.UsernameChanged(intent.username))
            is LoginIntent.UpdatePassword -> reduce(LoginChange.PasswordChanged(intent.password))
            LoginIntent.Submit -> submit()
        }
    }

    private fun reduce(change: LoginChange) {
        _state.update { current -> reduceLogin(current, change) }
    }

    private fun submit() {
        if (_state.value.isSubmitting) {
            return
        }

        viewModelScope.launch {
            val current = _state.value
            reduce(LoginChange.SubmitStarted)
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
                    reduce(LoginChange.SubmitSucceeded)
                    events.send(LoginEvent.Success)
                },
                onFailure = { throwable ->
                    reduce(LoginChange.SubmitFailed(throwable.toLoginError()))
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
