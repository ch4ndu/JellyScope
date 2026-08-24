// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.login

import com.jellyscope.core.data.repository.AuthRepository
import com.jellyscope.core.domain.action.QuickConnectLoginAction
import com.jellyscope.core.domain.action.SessionRemovalAuthorization
import com.jellyscope.core.domain.model.QuickConnectCode
import com.jellyscope.core.domain.model.QuickConnectLoginUpdate
import com.jellyscope.core.domain.model.ServerInfo
import com.jellyscope.core.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QuickConnectViewModelTest {
    @Test
    fun startFormatsCodeInThreeCharacterGroups() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    QuickConnectViewModel(
                        serverInfo = serverInfo,
                        quickConnectLoginAction =
                            QuickConnectLoginAction(
                                FakeQuickConnectAuthRepository(
                                    flowOf(
                                        Result.success(
                                            QuickConnectLoginUpdate.CodeAvailable(
                                                QuickConnectCode(
                                                    secret = "secret-1",
                                                    code = "ABCDEF",
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        workDispatcher = dispatcher,
                    )

                viewModel.start()
                advanceUntilIdle()

                val state = assertIs<QuickConnectUiState.CodeShown>(viewModel.state.value)
                assertEquals("ABC DEF", state.displayCode)
            } finally {
                Dispatchers.resetMain()
            }
        }
}

private class FakeQuickConnectAuthRepository(
    private val quickConnectUpdates: Flow<Result<QuickConnectLoginUpdate>>,
) : AuthRepository {
    override suspend fun validateServer(input: String): Result<ServerInfo> = Result.success(serverInfo)

    override suspend fun login(
        serverUrl: String,
        username: String,
        password: String,
    ): Result<Session> = error("Unused")

    override fun loginWithQuickConnect(serverInfo: ServerInfo): Flow<Result<QuickConnectLoginUpdate>> = quickConnectUpdates

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
