// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.LoginAction
import com.jellyscope.core.domain.action.QuickConnectLoginAction
import com.jellyscope.core.domain.action.ValidateServerAction
import com.jellyscope.core.domain.model.QuickConnectLoginUpdate
import com.jellyscope.core.domain.model.ServerInfo
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TvLoginPhase {
    EnterServer,
    ValidatingServer,
    SignIn,
    LoggingIn,
    Done,
}

data class TvLoginState(
    val phase: TvLoginPhase = TvLoginPhase.EnterServer,
    val serverName: String? = null,
    val serverUrl: String? = null,
    // Quick Connect is the primary 10-foot login path: the code shows as soon
    // as the server validates; password entry stays available alongside it.
    val quickConnectCode: String? = null,
    val quickConnectError: TvErrorKind? = null,
    val error: TvErrorKind? = null,
)

/**
 * Server entry + login (Quick Connect primary, password fallback). Successful
 * logins persist the session; the app switches screens on [TvSessionPresenter]
 * state, so [TvLoginPhase.Done] only means "this flow finished".
 */
class TvLoginPresenter(
    private val validateServerAction: ValidateServerAction,
    private val loginAction: LoginAction,
    private val quickConnectLoginAction: QuickConnectLoginAction,
    dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val _state = MutableStateFlow(TvLoginState())
    val state: StateFlow<TvLoginState> = _state.asStateFlow()

    private var serverInfo: ServerInfo? = null
    private var quickConnectJob: Job? = null

    fun watchState(onChange: (TvLoginState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun submitServer(input: String) {
        if (state.value.phase == TvLoginPhase.ValidatingServer) {
            return
        }
        cancelQuickConnect()
        _state.update { TvLoginState(phase = TvLoginPhase.ValidatingServer) }
        scope.launch {
            validateServerAction(input)
                .onSuccess { info ->
                    serverInfo = info
                    _state.update {
                        TvLoginState(
                            phase = TvLoginPhase.SignIn,
                            serverName = info.serverName,
                            serverUrl = info.serverUrl,
                        )
                    }
                    startQuickConnect(info)
                }.onFailure { error ->
                    _state.update {
                        TvLoginState(
                            phase = TvLoginPhase.EnterServer,
                            error = error.toTvErrorKind(),
                        )
                    }
                }
        }
    }

    fun submitCredentials(
        username: String,
        password: String,
    ) {
        val info = serverInfo ?: return
        if (state.value.phase != TvLoginPhase.SignIn) {
            return
        }
        _state.update { current -> current.copy(phase = TvLoginPhase.LoggingIn, error = null) }
        scope.launch {
            loginAction(
                serverUrl = info.serverUrl,
                username = username,
                password = password,
            ).onSuccess {
                // Session state is the single routing truth; a late Quick
                // Connect poll result must not race the completed login.
                cancelQuickConnect()
                _state.update { current -> current.copy(phase = TvLoginPhase.Done) }
            }.onFailure { error ->
                _state.update { current ->
                    current.copy(
                        phase = TvLoginPhase.SignIn,
                        error = error.toTvErrorKind(),
                    )
                }
            }
        }
    }

    fun retryQuickConnect() {
        val info = serverInfo ?: return
        startQuickConnect(info)
    }

    /** Back from the sign-in screen to server entry. */
    fun resetToServerEntry() {
        cancelQuickConnect()
        serverInfo = null
        _state.update { TvLoginState() }
    }

    override fun close() {
        cancelQuickConnect()
        super.close()
    }

    private fun startQuickConnect(info: ServerInfo) {
        cancelQuickConnect()
        _state.update { current -> current.copy(quickConnectCode = null, quickConnectError = null) }
        quickConnectJob =
            scope.launch {
                quickConnectLoginAction(info).collect { result ->
                    result
                        .onSuccess { update ->
                            when (update) {
                                is QuickConnectLoginUpdate.CodeAvailable ->
                                    _state.update { current -> current.copy(quickConnectCode = update.code.code) }
                                is QuickConnectLoginUpdate.Polling ->
                                    _state.update { current -> current.copy(quickConnectCode = update.code.code) }
                                is QuickConnectLoginUpdate.Success ->
                                    _state.update { current -> current.copy(phase = TvLoginPhase.Done) }
                            }
                        }.onFailure { error ->
                            _state.update { current ->
                                current.copy(
                                    quickConnectCode = null,
                                    quickConnectError = error.toTvErrorKind(),
                                )
                            }
                        }
                }
            }
    }

    private fun cancelQuickConnect() {
        quickConnectJob?.cancel()
        quickConnectJob = null
    }
}
