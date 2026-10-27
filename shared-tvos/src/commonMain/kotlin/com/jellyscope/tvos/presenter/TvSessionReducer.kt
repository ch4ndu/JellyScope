// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.SessionState

internal sealed interface TvSessionChange {
    class Observed(
        val state: SessionState,
    ) : TvSessionChange
}

internal fun reduceTvSession(change: TvSessionChange): TvSessionState =
    when (change) {
        is TvSessionChange.Observed -> change.state.toTvSessionState()
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
                boundaryEpoch = boundaryEpoch,
            )
    }
