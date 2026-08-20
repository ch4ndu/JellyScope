// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.serverentry

import com.jellyscope.core.data.repository.AuthRepository
import com.jellyscope.core.data.repository.SessionRemovalAuthorization
import com.jellyscope.core.domain.action.ValidateServerAction
import com.jellyscope.core.domain.discovery.DiscoveredServer
import com.jellyscope.core.domain.discovery.ServerDiscovery
import com.jellyscope.core.domain.model.AuthError
import com.jellyscope.core.domain.model.ServerInfo
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.usecase.DiscoverServersUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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

class ServerEntryViewModelTest {
    @Test
    fun discoveryPopulatesStateAndScanningFlagTransitions() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val discoveryFinished = CompletableDeferred<Unit>()
                val viewModel =
                    serverEntryViewModel(
                        discovery =
                            FakeServerDiscovery {
                                flow {
                                    emit(discoveredServer)
                                    discoveryFinished.await()
                                }
                            },
                    )

                runCurrent()

                assertTrue(viewModel.state.value.isScanning)
                assertEquals(
                    listOf(DiscoveredServerUi("server-1", "Home Jellyfin", "http://192.168.1.10:8096")),
                    viewModel.state.value.discoveredServers,
                )

                discoveryFinished.complete(Unit)
                advanceUntilIdle()

                assertFalse(viewModel.state.value.isScanning)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun startsInScanningStateBeforeDiscoveryRuns() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel = serverEntryViewModel(discovery = FakeServerDiscovery { flowOf() })
                // The init scan coroutine has not executed yet (no runCurrent), but the
                // state must already read as scanning so the discovery region renders at
                // a stable height from the first frame.
                assertTrue(viewModel.state.value.isScanning)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun selectingDiscoveredServerFillsInputAndValidates() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val validateResult = CompletableDeferred<Result<ServerInfo>>()
                val authRepository = FakeAuthRepository(validateResult)
                val viewModel =
                    serverEntryViewModel(
                        authRepository = authRepository,
                        discovery = FakeServerDiscovery { flowOf(discoveredServer) },
                    )
                val event = async { viewModel.eventFlow.first() }
                advanceUntilIdle()

                viewModel.selectDiscoveredServer(
                    DiscoveredServerUi("server-1", "Home Jellyfin", "http://192.168.1.10:8096"),
                )
                runCurrent()

                assertEquals("http://192.168.1.10:8096", viewModel.state.value.input)
                assertTrue(viewModel.state.value.isValidating)

                validateResult.complete(Result.success(serverInfo))
                advanceUntilIdle()

                assertEquals(listOf("http://192.168.1.10:8096"), authRepository.validateInputs)
                assertFalse(viewModel.state.value.isValidating)
                assertIs<ServerEntryEvent.Validated>(event.await())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun discoveryFailureLeavesManualFlowUntouched() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val validateResult = CompletableDeferred(Result.failure<ServerInfo>(AuthError.InvalidUrl))
                val viewModel =
                    serverEntryViewModel(
                        authRepository = FakeAuthRepository(validateResult),
                        discovery =
                            FakeServerDiscovery {
                                flow {
                                    throw IllegalStateException("network unavailable")
                                }
                            },
                    )

                viewModel.updateInput("not-a-url")
                advanceUntilIdle()

                assertEquals("not-a-url", viewModel.state.value.input)
                assertFalse(viewModel.state.value.isScanning)
                assertEquals(emptyList(), viewModel.state.value.discoveredServers)

                viewModel.submit()
                advanceUntilIdle()

                assertEquals(ServerEntryError.InvalidUrl, viewModel.state.value.error)
            } finally {
                Dispatchers.resetMain()
            }
        }
}

private fun serverEntryViewModel(
    authRepository: AuthRepository = FakeAuthRepository(CompletableDeferred(Result.success(serverInfo))),
    discovery: ServerDiscovery = FakeServerDiscovery { flowOf(discoveredServer) },
) = ServerEntryViewModel(
    validateServerAction = ValidateServerAction(authRepository),
    discoverServersUseCase = DiscoverServersUseCase(discovery),
)

private class FakeServerDiscovery(
    private val discoveryFlow: () -> Flow<DiscoveredServer>,
) : ServerDiscovery {
    override fun discover(timeoutMs: Long): Flow<DiscoveredServer> = discoveryFlow()
}

private class FakeAuthRepository(
    private val validateResult: CompletableDeferred<Result<ServerInfo>>,
) : AuthRepository {
    val validateInputs = mutableListOf<String>()

    override suspend fun validateServer(input: String): Result<ServerInfo> {
        validateInputs += input
        return validateResult.await()
    }

    override suspend fun login(
        serverUrl: String,
        username: String,
        password: String,
    ): Result<Session> = Result.failure(UnsupportedOperationException())

    override suspend fun logout(authorization: SessionRemovalAuthorization): Result<Unit> = Result.success(Unit)
}

private val discoveredServer =
    DiscoveredServer(
        id = "server-1",
        name = "Home Jellyfin",
        address = "http://192.168.1.10:8096",
    )

private val serverInfo =
    ServerInfo(
        serverUrl = "http://192.168.1.10:8096",
        serverId = "server-1",
        serverName = "Home Jellyfin",
        version = "10.10.0",
        productName = "Jellyfin Server",
    )
