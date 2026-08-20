// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.LogoutAction
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.usecase.ObserveSessionStateUseCase
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TvSessionPresenterTest {
    @Test
    fun mirrorsSessionStateTransitions() =
        runTest {
            val sessionRepository = FakeTvSessionRepository()
            val presenter =
                TvSessionPresenter(
                    observeSessionState = ObserveSessionStateUseCase(sessionRepository),
                    logoutAction = LogoutAction(FakeTvAuthRepository()),
                    dispatchers = testDispatchers(StandardTestDispatcher(testScheduler)),
                )
            runCurrent()
            assertEquals(TvSessionPhase.Restoring, presenter.state.value.phase)

            sessionRepository.update(SessionState.LoggedIn(testSession()))
            runCurrent()
            assertEquals(TvSessionPhase.LoggedIn, presenter.state.value.phase)
            assertEquals(testSession(), presenter.state.value.session)

            sessionRepository.update(SessionState.LoggedOut(serverUrl = "https://jellyfin.example"))
            runCurrent()
            assertEquals(TvSessionPhase.LoggedOut, presenter.state.value.phase)
            assertEquals("https://jellyfin.example", presenter.state.value.lastServerUrl)
            presenter.close()
        }

    @Test
    fun signOutInvokesLogout() =
        runTest {
            val authRepository = FakeTvAuthRepository()
            val presenter =
                TvSessionPresenter(
                    observeSessionState = ObserveSessionStateUseCase(FakeTvSessionRepository()),
                    logoutAction = LogoutAction(authRepository),
                    dispatchers = testDispatchers(StandardTestDispatcher(testScheduler)),
                )

            presenter.signOut()
            runCurrent()

            assertEquals(1, authRepository.logoutCount)
            presenter.close()
        }
}
