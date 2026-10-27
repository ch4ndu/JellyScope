// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.ServerInfo

internal sealed interface TvLoginChange {
    data object DiscoveryStarted : TvLoginChange

    data class ServersDiscovered(
        val servers: List<TvDiscoveredServer>,
    ) : TvLoginChange

    data object DiscoveryFinished : TvLoginChange

    data object DiscoveryFailed : TvLoginChange

    data object ValidationStarted : TvLoginChange

    data class ServerValidated(
        val info: ServerInfo,
    ) : TvLoginChange

    data class ValidationFailed(
        val error: TvErrorKind,
    ) : TvLoginChange

    data object LoginStarted : TvLoginChange

    data object LoginSucceeded : TvLoginChange

    data class LoginFailed(
        val error: TvErrorKind,
    ) : TvLoginChange

    data object Reset : TvLoginChange

    data object QuickConnectStarted : TvLoginChange

    class QuickConnectCodeReceived(
        val code: String,
    ) : TvLoginChange

    data object QuickConnectSucceeded : TvLoginChange

    data class QuickConnectFailed(
        val error: TvErrorKind,
    ) : TvLoginChange
}

internal fun reduceTvLogin(
    current: TvLoginState,
    change: TvLoginChange,
): TvLoginState =
    when (change) {
        TvLoginChange.DiscoveryStarted ->
            run {
                current.copy(isDiscovering = true, discoveryError = null)
            }
        is TvLoginChange.ServersDiscovered ->
            with(change) {
                current.copy(discoveredServers = servers)
            }
        TvLoginChange.DiscoveryFinished ->
            run {
                current.copy(isDiscovering = false)
            }
        TvLoginChange.DiscoveryFailed ->
            run {
                current.copy(
                    isDiscovering = false,
                    discoveryError = TvErrorKind.Network,
                )
            }
        TvLoginChange.ValidationStarted ->
            run {
                current.copy(
                    phase = TvLoginPhase.ValidatingServer,
                    serverName = null,
                    serverUrl = null,
                    quickConnectCode = null,
                    quickConnectError = null,
                    error = null,
                    isDiscovering = false,
                )
            }
        is TvLoginChange.ServerValidated ->
            with(change) {
                current.copy(
                    phase = TvLoginPhase.SignIn,
                    serverName = info.serverName,
                    serverUrl = info.serverUrl,
                    error = null,
                )
            }
        is TvLoginChange.ValidationFailed ->
            with(change) {
                current.copy(
                    phase = TvLoginPhase.EnterServer,
                    error = error,
                )
            }
        TvLoginChange.LoginStarted ->
            run {
                current.copy(phase = TvLoginPhase.LoggingIn, error = null)
            }
        TvLoginChange.LoginSucceeded ->
            run {
                current.copy(phase = TvLoginPhase.Done, error = null)
            }
        is TvLoginChange.LoginFailed ->
            with(change) {
                current.copy(
                    phase = TvLoginPhase.SignIn,
                    error = error,
                )
            }
        TvLoginChange.Reset ->
            run {
                TvLoginState(
                    discoveryAvailable = current.discoveryAvailable,
                    discoveredServers = current.discoveredServers,
                    discoveryError = current.discoveryError,
                )
            }
        TvLoginChange.QuickConnectStarted ->
            run {
                current.copy(quickConnectCode = null, quickConnectError = null)
            }
        is TvLoginChange.QuickConnectCodeReceived ->
            with(change) {
                current.copy(quickConnectCode = code)
            }
        TvLoginChange.QuickConnectSucceeded ->
            run {
                current.copy(phase = TvLoginPhase.Done)
            }
        is TvLoginChange.QuickConnectFailed ->
            with(change) {
                current.copy(
                    quickConnectCode = null,
                    quickConnectError = error,
                )
            }
    }
