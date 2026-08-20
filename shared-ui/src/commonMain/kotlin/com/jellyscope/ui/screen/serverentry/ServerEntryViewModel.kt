// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.serverentry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.domain.action.ValidateServerAction
import com.jellyscope.core.domain.discovery.DiscoveredServer
import com.jellyscope.core.domain.model.AuthError
import com.jellyscope.core.domain.model.ServerInfo
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

data class ServerEntryUiState(
    val input: String = "",
    val isValidating: Boolean = false,
    val error: ServerEntryError? = null,
    val discoveredServers: List<DiscoveredServerUi> = emptyList(),
    val isDiscoveryAvailable: Boolean = true,
    val isScanning: Boolean = false,
)

data class DiscoveredServerUi(
    val id: String,
    val name: String,
    val address: String,
)

enum class ServerEntryError {
    InvalidUrl,
    NotReachable,
    ServerError,
}

sealed interface ServerEntryEvent {
    data class Validated(
        val serverInfo: ServerInfo,
    ) : ServerEntryEvent
}

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

    fun updateInput(input: String) {
        _state.update { current -> current.copy(input = input, error = null) }
    }

    fun submit() {
        submit(_state.value.input)
    }

    fun scanAgain() {
        if (!isDiscoveryAvailable) {
            return
        }

        scanGeneration += 1
        val generation = scanGeneration
        scanJob?.cancel()
        scanJob =
            viewModelScope.launch {
                _state.update { current ->
                    current.copy(
                        discoveredServers = emptyList(),
                        isScanning = true,
                    )
                }
                try {
                    discoverServersUseCase().collect { server ->
                        val serverUi = server.toUi()
                        _state.update { current ->
                            if (current.discoveredServers.any { it.id == serverUi.id }) {
                                current
                            } else {
                                current.copy(discoveredServers = current.discoveredServers + serverUi)
                            }
                        }
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
                        _state.update { current -> current.copy(isScanning = false) }
                    }
                }
            }
    }

    fun selectDiscoveredServer(server: DiscoveredServerUi) {
        _state.update { current -> current.copy(input = server.address, error = null) }
        submit(server.address)
    }

    private fun submit(input: String) {
        if (_state.value.isValidating) {
            return
        }

        viewModelScope.launch {
            _state.update { current -> current.copy(isValidating = true, error = null) }
            val result = validateServerAction(input)
            result.fold(
                onSuccess = { serverInfo ->
                    _state.update { current -> current.copy(isValidating = false) }
                    events.send(ServerEntryEvent.Validated(serverInfo))
                },
                onFailure = { throwable ->
                    _state.update { current ->
                        current.copy(
                            isValidating = false,
                            error = throwable.toServerEntryError(),
                        )
                    }
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
