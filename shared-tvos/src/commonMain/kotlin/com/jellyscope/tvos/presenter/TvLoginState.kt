// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

enum class TvLoginPhase {
    EnterServer,
    ValidatingServer,
    SignIn,
    LoggingIn,
    Done,
}

data class TvDiscoveredServer(
    val id: String,
    val name: String,
    val address: String,
)

data class TvLoginState(
    val phase: TvLoginPhase = TvLoginPhase.EnterServer,
    val serverName: String? = null,
    val serverUrl: String? = null,
    val quickConnectCode: String? = null,
    val quickConnectError: TvErrorKind? = null,
    val error: TvErrorKind? = null,
    val discoveryAvailable: Boolean = false,
    val isDiscovering: Boolean = false,
    val discoveredServers: List<TvDiscoveredServer> = emptyList(),
    val discoveryError: TvErrorKind? = null,
)
