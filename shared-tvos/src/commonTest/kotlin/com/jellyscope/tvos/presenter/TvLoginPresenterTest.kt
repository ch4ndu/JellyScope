// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.AuthError
import com.jellyscope.core.domain.action.LoginAction
import com.jellyscope.core.domain.action.QuickConnectLoginAction
import com.jellyscope.core.domain.action.ValidateServerAction
import com.jellyscope.core.domain.model.QuickConnectCode
import com.jellyscope.core.domain.model.QuickConnectLoginUpdate
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvLoginPresenterTest {
    @Test
    fun serverValidationFailureStaysOnServerEntryWithError() =
        runTest {
            val repository = FakeTvAuthRepository(validateResult = Result.failure(AuthError.NotReachable))
            val presenter = presenter(repository)

            presenter.submitServer("bad")
            runCurrent()

            assertEquals(TvLoginPhase.EnterServer, presenter.state.value.phase)
            assertEquals(TvErrorKind.NotReachable, presenter.state.value.error)
            presenter.close()
        }

    @Test
    fun serverValidationSuccessMovesToSignInAndStartsQuickConnect() =
        runTest {
            val repository = FakeTvAuthRepository()
            val presenter = presenter(repository)

            presenter.submitServer("https://jellyfin.example")
            runCurrent()
            repository.quickConnectUpdates.emit(
                Result.success(QuickConnectLoginUpdate.CodeAvailable(QuickConnectCode(secret = "s", code = "123456"))),
            )
            runCurrent()

            assertEquals(TvLoginPhase.SignIn, presenter.state.value.phase)
            assertEquals("Test Server", presenter.state.value.serverName)
            assertEquals("123456", presenter.state.value.quickConnectCode)
            assertEquals(1, repository.quickConnectCollections)
            presenter.close()
        }

    @Test
    fun quickConnectSuccessCompletesFlow() =
        runTest {
            val repository = FakeTvAuthRepository()
            val presenter = presenter(repository)
            presenter.submitServer("https://jellyfin.example")
            runCurrent()

            repository.quickConnectUpdates.emit(
                Result.success(QuickConnectLoginUpdate.Success(testSession())),
            )
            runCurrent()

            assertEquals(TvLoginPhase.Done, presenter.state.value.phase)
            presenter.close()
        }

    @Test
    fun quickConnectFailureSurfacesTypedErrorAndRetryRestartsPolling() =
        runTest {
            val repository = FakeTvAuthRepository()
            val presenter = presenter(repository)
            presenter.submitServer("https://jellyfin.example")
            runCurrent()

            repository.quickConnectUpdates.emit(Result.failure(AuthError.QuickConnectExpired))
            runCurrent()
            assertEquals(TvErrorKind.QuickConnectExpired, presenter.state.value.quickConnectError)

            presenter.retryQuickConnect()
            runCurrent()
            assertNull(presenter.state.value.quickConnectError)
            assertEquals(2, repository.quickConnectCollections)
            presenter.close()
        }

    @Test
    fun passwordLoginSuccessCancelsQuickConnectPolling() =
        runTest {
            val repository = FakeTvAuthRepository()
            val presenter = presenter(repository)
            presenter.submitServer("https://jellyfin.example")
            runCurrent()

            presenter.submitCredentials("user", "pass")
            runCurrent()

            assertEquals(TvLoginPhase.Done, presenter.state.value.phase)
            assertEquals(1, repository.quickConnectCancellations)
            presenter.close()
        }

    @Test
    fun staleQuickConnectResultAfterPasswordLoginIsDropped() =
        runTest {
            val repository = FakeTvAuthRepository()
            val presenter = presenter(repository)
            presenter.submitServer("https://jellyfin.example")
            runCurrent()
            presenter.submitCredentials("user", "pass")
            runCurrent()

            // Cancelled collection: a late poll result must not disturb state.
            repository.quickConnectUpdates.tryEmit(Result.failure(AuthError.QuickConnectExpired))
            runCurrent()

            assertEquals(TvLoginPhase.Done, presenter.state.value.phase)
            assertNull(presenter.state.value.quickConnectError)
            presenter.close()
        }

    @Test
    fun invalidCredentialsReturnToSignInWithError() =
        runTest {
            val repository = FakeTvAuthRepository(loginResult = Result.failure(AuthError.InvalidCredentials))
            val presenter = presenter(repository)
            presenter.submitServer("https://jellyfin.example")
            runCurrent()

            presenter.submitCredentials("user", "wrong")
            runCurrent()

            assertEquals(TvLoginPhase.SignIn, presenter.state.value.phase)
            assertEquals(TvErrorKind.InvalidCredentials, presenter.state.value.error)
            presenter.close()
        }

    private fun TestScope.presenter(repository: FakeTvAuthRepository): TvLoginPresenter =
        TvLoginPresenter(
            validateServerAction = ValidateServerAction(repository),
            loginAction = LoginAction(repository),
            quickConnectLoginAction = QuickConnectLoginAction(repository),
            dispatchers = testDispatchers(StandardTestDispatcher(testScheduler)),
        )
}
