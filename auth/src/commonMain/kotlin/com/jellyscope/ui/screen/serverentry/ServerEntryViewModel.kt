// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.serverentry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.domain.action.AuthError
import com.jellyscope.core.domain.action.ValidateServerAction
import com.jellyscope.core.domain.discovery.DiscoveredServer
import com.jellyscope.core.domain.usecase.DiscoverServersUseCase
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ServerEntryViewModel(
    private val validateServerAction: ValidateServerAction,
    private val discoverServersUseCase: DiscoverServersUseCase,
) : ViewModel() {
    private val isDiscoveryAvailable = discoverServersUseCase.isAvailable

    // Available discovery starts in the scanning state because init launches an
    // immediate scan; this keeps the discovered-servers region stable from the
    // first frame instead of briefly rendering the empty state before the scan runs.
    private val _state =
        MutableStateFlow(
            ServerEntryUiState(
                isDiscoveryAvailable = isDiscoveryAvailable,
                isScanning = isDiscoveryAvailable,
            ),
        )
    val state: StateFlow<ServerEntryUiState> = _state.asStateFlow()

    private val events = Channel<ServerEntryEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private var scanJob: Job? = null
    private var scanGeneration = 0

    init {
        scanAgain()
    }

    fun accept(intent: ServerEntryIntent) {
        when (intent) {
            is ServerEntryIntent.UpdateInput -> reduce(ServerEntryChange.InputChanged(intent.input))
            ServerEntryIntent.Submit -> submit(_state.value.input)
            ServerEntryIntent.ScanAgain -> scanAgain()
            is ServerEntryIntent.SelectDiscoveredServer -> selectDiscoveredServer(intent.server)
        }
    }

    private fun reduce(change: ServerEntryChange) {
        _state.update { current -> reduceServerEntry(current, change) }
    }

    private fun scanAgain() {
        if (!isDiscoveryAvailable) {
            return
        }

        scanGeneration += 1
        val generation = scanGeneration
        scanJob?.cancel()
        scanJob =
            viewModelScope.launch {
                reduce(ServerEntryChange.ScanStarted)
                try {
                    discoverServersUseCase().collect { server ->
                        val serverUi = server.toUi()
                        reduce(ServerEntryChange.ServerFound(serverUi))
                    }
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                    serverEntryLogger.d {
                        formatSafeFailureDiagnostic(
                            stage = "server-discovery",
                            event = "failed",
                            throwable = throwable,
                        )
                    }
                } finally {
                    if (scanGeneration == generation) {
                        reduce(ServerEntryChange.ScanFinished)
                    }
                }
            }
    }

    private fun selectDiscoveredServer(server: DiscoveredServerUi) {
        reduce(ServerEntryChange.InputChanged(server.address))
        submit(server.address)
    }

    private fun submit(input: String) {
        if (_state.value.isValidating) {
            return
        }

        viewModelScope.launch {
            reduce(ServerEntryChange.SubmitStarted)
            val result = validateServerAction(input)
            result.fold(
                onSuccess = { serverInfo ->
                    reduce(ServerEntryChange.SubmitSucceeded)
                    events.send(ServerEntryEvent.Validated(serverInfo))
                },
                onFailure = { throwable ->
                    reduce(ServerEntryChange.SubmitFailed(throwable.toServerEntryError()))
                },
            )
        }
    }
}

private fun DiscoveredServer.toUi() =
    DiscoveredServerUi(
        id = id,
        name = name,
        address = address,
    )

private fun Throwable.toServerEntryError(): ServerEntryError =
    when (this) {
        AuthError.InvalidUrl -> ServerEntryError.InvalidUrl
        AuthError.NotReachable -> ServerEntryError.NotReachable
        else -> ServerEntryError.ServerError
    }

private val serverEntryLogger = diagnosticLogger(DiagnosticTag.ServerEntryViewModel)
