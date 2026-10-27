// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.LoginAction
import com.jellyscope.core.domain.action.QuickConnectLoginAction
import com.jellyscope.core.domain.action.ValidateServerAction
import com.jellyscope.core.domain.model.QuickConnectLoginUpdate
import com.jellyscope.core.domain.model.ServerInfo
import com.jellyscope.core.domain.usecase.DiscoverServersUseCase
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvLoginPresenter(
    private val validateServerAction: ValidateServerAction,
    private val loginAction: LoginAction,
    private val quickConnectLoginAction: QuickConnectLoginAction,
    private val discoverServers: DiscoverServersUseCase,
    dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val _state = MutableStateFlow(TvLoginState(discoveryAvailable = discoverServers.isAvailable))
    val state: StateFlow<TvLoginState> = _state.asStateFlow()

    private var serverInfo: ServerInfo? = null
    private var intentGeneration = 0L
    private var validationGeneration = 0L
    private var credentialsGeneration = 0L
    private var quickConnectGeneration = 0L
    private var discoveryGeneration = 0L
    private var validationJob: Job? = null
    private var credentialsJob: Job? = null
    private var quickConnectJob: Job? = null
    private var discoveryJob: Job? = null

    private fun reduce(change: TvLoginChange) {
        _state.update { current -> reduceTvLogin(current, change) }
    }

    fun accept(intent: TvLoginIntent) {
        when (intent) {
            TvLoginIntent.StartDiscovery -> startDiscovery()
            TvLoginIntent.RetryDiscovery -> retryDiscovery()
            is TvLoginIntent.SubmitServer -> submitServer(intent.input)
            is TvLoginIntent.SubmitCredentials -> submitCredentials(intent.username, intent.password)
            TvLoginIntent.RetryQuickConnect -> retryQuickConnect()
            TvLoginIntent.ResetToServerEntry -> resetToServerEntry()
        }
    }

    fun watchState(onChange: (TvLoginState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    private fun startDiscovery() {
        if (!state.value.discoveryAvailable || state.value.phase != TvLoginPhase.EnterServer) {
            return
        }
        discoveryJob?.cancel()
        discoveryGeneration += 1
        val current = discoveryGeneration
        reduce(TvLoginChange.DiscoveryStarted)
        discoveryJob =
            scope.launch {
                tvLoginDiscoveryLogger.i { "stage=tvos-server-discovery event=started" }
                try {
                    discoverServers()
                        .flowOn(workDispatcher)
                        .collect { server ->
                            if (current != discoveryGeneration) {
                                return@collect
                            }
                            val discovered = TvDiscoveredServer(server.id, server.name, server.address)
                            val previous = state.value.discoveredServers
                            val updated =
                                withContext(workDispatcher) {
                                    previous.filterNot { item -> item.id == discovered.id } + discovered
                                }
                            if (current != discoveryGeneration) {
                                return@collect
                            }
                            reduce(TvLoginChange.ServersDiscovered(updated))
                        }
                    tvLoginDiscoveryLogger.i { "stage=tvos-server-discovery event=completed" }
                    if (current == discoveryGeneration) {
                        reduce(TvLoginChange.DiscoveryFinished)
                    }
                } catch (exception: CancellationException) {
                    tvLoginDiscoveryLogger.i { "stage=tvos-server-discovery event=cancelled" }
                    throw exception
                } catch (exception: Throwable) {
                    tvLoginDiscoveryLogger.w {
                        formatSafeFailureDiagnostic(
                            stage = "tvos-server-discovery",
                            event = "failed",
                            throwable = exception,
                        )
                    }
                    if (current == discoveryGeneration) {
                        reduce(TvLoginChange.DiscoveryFailed)
                    }
                }
            }
    }

    private fun retryDiscovery() {
        startDiscovery()
    }

    private fun submitServer(input: String) {
        if (state.value.phase != TvLoginPhase.EnterServer || validationJob?.isActive == true) {
            return
        }
        startServerIntent()
        validationGeneration += 1
        val currentValidation = validationGeneration
        val currentIntent = intentGeneration
        reduce(TvLoginChange.ValidationStarted)
        validationJob =
            scope.launch {
                val result =
                    try {
                        withContext(workDispatcher) { validateServerAction(input) }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Throwable) {
                        Result.failure<ServerInfo>(exception)
                    }
                if (currentIntent != intentGeneration || currentValidation != validationGeneration) {
                    return@launch
                }
                validationJob = null
                result
                    .onSuccess { info ->
                        serverInfo = info
                        reduce(TvLoginChange.ServerValidated(info))
                        startQuickConnect(info, currentIntent)
                    }.onFailure { error ->
                        reduce(TvLoginChange.ValidationFailed(error.toTvErrorKind()))
                    }
            }
    }

    private fun submitCredentials(
        username: String,
        password: String,
    ) {
        val info = serverInfo ?: return
        if (state.value.phase != TvLoginPhase.SignIn || credentialsJob?.isActive == true) {
            return
        }
        cancelQuickConnect()
        credentialsGeneration += 1
        val currentCredentials = credentialsGeneration
        val currentIntent = intentGeneration
        reduce(TvLoginChange.LoginStarted)
        credentialsJob =
            scope.launch {
                val result =
                    try {
                        withContext(workDispatcher) {
                            loginAction(
                                serverUrl = info.serverUrl,
                                username = username,
                                password = password,
                            )
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Throwable) {
                        Result.failure(exception)
                    }
                if (currentIntent != intentGeneration || currentCredentials != credentialsGeneration) {
                    return@launch
                }
                credentialsJob = null
                result
                    .onSuccess {
                        reduce(TvLoginChange.LoginSucceeded)
                    }.onFailure { error ->
                        reduce(TvLoginChange.LoginFailed(error.toTvErrorKind()))
                        if (serverInfo == info && currentIntent == intentGeneration) {
                            startQuickConnect(info, currentIntent)
                        }
                    }
            }
    }

    private fun retryQuickConnect() {
        val info = serverInfo ?: return
        if (state.value.phase == TvLoginPhase.SignIn) {
            startQuickConnect(info, intentGeneration)
        }
    }

    private fun resetToServerEntry() {
        invalidateIntent()
        serverInfo = null
        reduce(TvLoginChange.Reset)
    }

    override fun close() {
        invalidateIntent()
        super.close()
    }

    private fun startQuickConnect(
        info: ServerInfo,
        intent: Long,
    ) {
        if (intent != intentGeneration || serverInfo != info) {
            return
        }
        cancelQuickConnect()
        quickConnectGeneration += 1
        val currentQuickConnect = quickConnectGeneration
        reduce(TvLoginChange.QuickConnectStarted)
        quickConnectJob =
            scope.launch {
                try {
                    quickConnectLoginAction(info)
                        .flowOn(workDispatcher)
                        .collect { result ->
                            if (
                                intent != intentGeneration ||
                                currentQuickConnect != quickConnectGeneration ||
                                serverInfo != info
                            ) {
                                return@collect
                            }
                            result
                                .onSuccess { update ->
                                    when (update) {
                                        is QuickConnectLoginUpdate.CodeAvailable ->
                                            reduce(TvLoginChange.QuickConnectCodeReceived(update.code.code))
                                        is QuickConnectLoginUpdate.Polling ->
                                            reduce(TvLoginChange.QuickConnectCodeReceived(update.code.code))
                                        is QuickConnectLoginUpdate.Success -> {
                                            credentialsJob?.cancel()
                                            credentialsGeneration += 1
                                            quickConnectGeneration += 1
                                            reduce(TvLoginChange.QuickConnectSucceeded)
                                        }
                                    }
                                }.onFailure { error ->
                                    reduce(TvLoginChange.QuickConnectFailed(error.toTvErrorKind()))
                                }
                        }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    if (
                        intent == intentGeneration &&
                        currentQuickConnect == quickConnectGeneration &&
                        serverInfo == info
                    ) {
                        reduce(TvLoginChange.QuickConnectFailed(exception.toTvErrorKind()))
                    }
                }
            }
    }

    private fun startServerIntent() {
        intentGeneration += 1
        cancelAllJobs()
        serverInfo = null
    }

    private fun invalidateIntent() {
        intentGeneration += 1
        cancelAllJobs()
    }

    private fun cancelAllJobs() {
        validationGeneration += 1
        credentialsGeneration += 1
        discoveryGeneration += 1
        validationJob?.cancel()
        credentialsJob?.cancel()
        discoveryJob?.cancel()
        validationJob = null
        credentialsJob = null
        discoveryJob = null
        cancelQuickConnect()
    }

    private fun cancelQuickConnect() {
        quickConnectGeneration += 1
        quickConnectJob?.cancel()
        quickConnectJob = null
    }
}

private val tvLoginDiscoveryLogger = diagnosticLogger(DiagnosticTag.ServerEntryViewModel)
