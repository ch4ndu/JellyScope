// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.login

import com.jellyscope.core.data.repository.AuthRepository
import com.jellyscope.core.domain.action.AuthError
import com.jellyscope.core.domain.action.LoginAction
import com.jellyscope.core.domain.action.SessionRemovalAuthorization
import com.jellyscope.core.domain.model.ServerInfo
import com.jellyscope.core.domain.model.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LoginViewModelTest {
    @Test
    fun submitTransitionsToSuccessEvent() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val loginResult = CompletableDeferred<Result<Session>>()
                val viewModel =
                    LoginViewModel(
                        initialUsername = "",
                        initialPassword = "",
                        serverInfo = serverInfo,
                        loginAction = LoginAction(FakeAuthRepository(loginResult)),
                        workDispatcher = dispatcher,
                    )
                val event = async { viewModel.eventFlow.first() }

                viewModel.updateUsername("demo-user")
                viewModel.updatePassword("pw")
                viewModel.submit()
                runCurrent()

                assertTrue(viewModel.state.value.isSubmitting)

                loginResult.complete(Result.success(session))
                advanceUntilIdle()

                assertFalse(viewModel.state.value.isSubmitting)
                assertEquals(null, viewModel.state.value.error)
                assertIs<LoginEvent.Success>(event.await())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun submitTransitionsToError() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val loginResult =
                    CompletableDeferred<Result<Session>>(
                        Result.failure(AuthError.InvalidCredentials),
                    )
                val viewModel =
                    LoginViewModel(
                        initialUsername = "",
                        initialPassword = "",
                        serverInfo = serverInfo,
                        loginAction = LoginAction(FakeAuthRepository(loginResult)),
                        workDispatcher = dispatcher,
                    )

                viewModel.submit()
                advanceUntilIdle()

                assertFalse(viewModel.state.value.isSubmitting)
                assertEquals(LoginError.InvalidCredentials, viewModel.state.value.error)
            } finally {
                Dispatchers.resetMain()
            }
        }
}

private class FakeAuthRepository(
    private val loginResult: CompletableDeferred<Result<Session>>,
) : AuthRepository {
    override suspend fun validateServer(input: String): Result<ServerInfo> = Result.success(serverInfo)

    override suspend fun login(
        serverUrl: String,
        username: String,
        password: String,
    ): Result<Session> = loginResult.await()

    override suspend fun logout(authorization: SessionRemovalAuthorization): Result<Unit> = Result.success(Unit)
}

private val serverInfo =
    ServerInfo(
        serverUrl = "https://jellyfin.example",
        serverId = "server-1",
        serverName = "Home Jellyfin",
        version = "10.10.0",
        productName = "Jellyfin Server",
    )

private val session =
    Session(
        serverUrl = "https://jellyfin.example",
        serverId = "server-1",
        serverName = "Home Jellyfin",
        userId = "user-1",
        userName = "Demo User",
        accessToken = "token-1",
        deviceId = "device-1",
    )
