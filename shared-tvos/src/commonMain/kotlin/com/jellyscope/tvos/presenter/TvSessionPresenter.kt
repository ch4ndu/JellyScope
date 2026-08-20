// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.LogoutAction
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.usecase.ObserveSessionStateUseCase
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TvSessionPhase {
    Restoring,
    LoggedOut,
    LoggedIn,
}

data class TvSessionState(
    val phase: TvSessionPhase,
    val session: Session? = null,
    val lastServerUrl: String? = null,
)

/**
 * App-root routing truth: mirrors the shared [SessionState] so the SwiftUI
 * shell switches between restoring/login/logged-in purely on this state.
 */
class TvSessionPresenter(
    private val observeSessionState: ObserveSessionStateUseCase,
    private val logoutAction: LogoutAction,
    dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val _state = MutableStateFlow(TvSessionState(phase = TvSessionPhase.Restoring))
    val state: StateFlow<TvSessionState> = _state.asStateFlow()

    init {
        scope.launch {
            observeSessionState().collect { sessionState ->
                _state.value = sessionState.toTvSessionState()
            }
        }
    }

    fun watchState(onChange: (TvSessionState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun signOut() {
        scope.launch { logoutAction() }
    }
}

private fun SessionState.toTvSessionState(): TvSessionState =
    when (this) {
        SessionState.Restoring -> TvSessionState(phase = TvSessionPhase.Restoring)
        is SessionState.LoggedOut ->
            TvSessionState(
                phase = TvSessionPhase.LoggedOut,
                lastServerUrl = serverUrl,
            )
        is SessionState.LoggedIn ->
            TvSessionState(
                phase = TvSessionPhase.LoggedIn,
                session = session,
            )
    }
