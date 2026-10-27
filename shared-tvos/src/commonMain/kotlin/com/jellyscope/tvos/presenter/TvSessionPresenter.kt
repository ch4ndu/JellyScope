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
    val boundaryEpoch: Long = 0L,
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
                reduce(TvSessionChange.Observed(sessionState))
            }
        }
    }

    private fun reduce(change: TvSessionChange) {
        _state.value = reduceTvSession(change)
    }

    fun accept(intent: TvSessionIntent) {
        when (intent) {
            TvSessionIntent.SignOut -> signOut()
        }
    }

    fun watchState(onChange: (TvSessionState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    private fun signOut() {
        scope.launch { logoutAction() }
    }
}
