// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.serverentry

import com.jellyscope.core.domain.model.ServerInfo

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

sealed interface ServerEntryIntent {
    class UpdateInput(
        val input: String,
    ) : ServerEntryIntent

    data object Submit : ServerEntryIntent

    data object ScanAgain : ServerEntryIntent

    data class SelectDiscoveredServer(
        val server: DiscoveredServerUi,
    ) : ServerEntryIntent
}

internal sealed interface ServerEntryChange {
    class InputChanged(
        val input: String,
    ) : ServerEntryChange

    data object ScanStarted : ServerEntryChange

    data class ServerFound(
        val server: DiscoveredServerUi,
    ) : ServerEntryChange

    data object ScanFinished : ServerEntryChange

    data object SubmitStarted : ServerEntryChange

    data object SubmitSucceeded : ServerEntryChange

    data class SubmitFailed(
        val error: ServerEntryError,
    ) : ServerEntryChange
}

internal fun reduceServerEntry(
    current: ServerEntryUiState,
    change: ServerEntryChange,
): ServerEntryUiState =
    when (change) {
        is ServerEntryChange.InputChanged -> current.copy(input = change.input, error = null)
        ServerEntryChange.ScanStarted -> current.copy(discoveredServers = emptyList(), isScanning = true)
        is ServerEntryChange.ServerFound ->
            if (current.discoveredServers.any { it.id == change.server.id }) {
                current
            } else {
                current.copy(
                    discoveredServers =
                        current.discoveredServers + change.server,
                )
            }
        ServerEntryChange.ScanFinished -> current.copy(isScanning = false)
        ServerEntryChange.SubmitStarted -> current.copy(isValidating = true, error = null)
        ServerEntryChange.SubmitSucceeded -> current.copy(isValidating = false)
        is ServerEntryChange.SubmitFailed -> current.copy(isValidating = false, error = change.error)
    }
