// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.jellyscope.core.data.local.ClearableStore
import com.jellyscope.core.data.local.DiscoveryCache
import com.jellyscope.core.data.local.FakeSecureStore
import com.jellyscope.core.data.local.GateHeldBoundaryCommit
import com.jellyscope.core.data.local.ServerScopedClearableStore
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.local.SessionStore
import com.jellyscope.core.data.local.StoreCleanupException
import com.jellyscope.core.data.local.StoredSession
import com.jellyscope.core.data.local.accountId
import com.jellyscope.core.data.remote.AuthHeaderProvider
import com.jellyscope.core.data.remote.JellyfinApiException
import com.jellyscope.core.data.remote.KtorJellyfinApi
import com.jellyscope.core.domain.action.AuthError
import com.jellyscope.core.domain.action.LogoutAction
import com.jellyscope.core.domain.action.SessionRemovalAuthorization
import com.jellyscope.core.domain.action.SessionRemovalError
import com.jellyscope.core.domain.action.SessionRemovalScope
import com.jellyscope.core.domain.action.SignOutAccountAction
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.QuickConnectLoginUpdate
import com.jellyscope.core.domain.model.ServerInfo
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.platform.DeviceInfoProvider
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.LogScrubber
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthRepositoryTest {
    @Test
    fun supersededAuthenticationCommitIsCancelledBeforePersistence() =
        runTest {
            val fixture =
                repositoryFixture(
                    engine = MockEngine { error("Network should not be reached") },
                )
            advanceUntilIdle()
            val firstAttempt = fixture.coordinator.beginAuthenticationAttempt()
            fixture.coordinator.beginAuthenticationAttempt()

            assertFailsWith<CancellationException> {
                fixture.sessionRepository.setLoggedIn(
                    session = storedSession("server-1", "user-1").toDomain(),
                    authenticationAttempt = firstAttempt,
                )
            }
            assertNull(fixture.sessionStore.readSession())
        }

    @Test
    fun successLoginPersistsSessionAndFlipsState() =
        runTest {
            val fixture =
                repositoryFixture(
                    engine =
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/System/Info/Public" -> respondJson(publicInfoJson)
                                "/Users/AuthenticateByName" -> {
                                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                                    assertEquals(" demo-user", body.getValue("Username").jsonPrimitive.content)
                                    assertEquals(" pw", body.getValue("Pw").jsonPrimitive.content)
                                    respondJson(authJson)
                                }
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )
            advanceUntilIdle()

            val result =
                fixture.repository.login(
                    serverUrl = "  https://jellyfin.example///  ",
                    username = " demo-user\t ",
                    password = " pw\n ",
                )

            val session = result.getOrThrow()
            assertEquals("https://jellyfin.example", session.serverUrl)
            assertEquals("token-1", session.accessToken)
            assertEquals("Home Jellyfin", session.serverName)
            assertEquals("device-1", session.deviceId)
            assertFalse(session.enableContentDownloading)
            assertEquals(session.toStored(), fixture.sessionStore.readSession())
            assertEquals(
                SessionState.LoggedIn(session, boundaryEpoch = 1L),
                fixture.sessionRepository.sessionState.value,
            )
        }

    @Test
    fun optionalPublicDisplayFieldsUseTrimmedIdAndAuthorityFallbacks() =
        runTest {
            val fixture =
                repositoryFixture(
                    engine =
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/base/System/Info/Public" -> respondJson("""{"Id":"  server-1  "}""")
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )
            advanceUntilIdle()

            val result = fixture.repository.validateServer("https://jellyfin.example:8096/base/").getOrThrow()

            assertEquals("server-1", result.serverId)
            assertEquals("jellyfin.example:8096", result.serverName)
            assertEquals("", result.version)
            assertEquals("", result.productName)
        }

    @Test
    fun missingPublicIdFailsBeforePasswordAuthenticationOrSessionCommit() =
        runTest {
            var authenticationCalled = false
            val fixture =
                repositoryFixture(
                    engine =
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/System/Info/Public" -> respondJson("""{"ServerName":"No Id"}""")
                                "/Users/AuthenticateByName" -> {
                                    authenticationCalled = true
                                    respondJson(authJson)
                                }
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )
            advanceUntilIdle()

            val result = fixture.repository.login("https://jellyfin.example", "demo-user", "pw")

            assertIs<AuthError.ServerError>(result.exceptionOrNull())
            assertFalse(authenticationCalled)
            assertNull(fixture.sessionStore.readSession())
            assertIs<SessionState.LoggedOut>(fixture.sessionRepository.sessionState.value)
        }

    @Test
    fun passwordAuthMissingOrBlankServerIdUsesCanonicalPublicId() =
        runTest {
            listOf(null, "   ").forEach { authenticationServerId ->
                val fixture =
                    repositoryFixture(
                        engine =
                            MockEngine { request ->
                                when (request.url.encodedPath) {
                                    "/System/Info/Public" -> respondJson(publicInfoJson)
                                    "/Users/AuthenticateByName" ->
                                        respondJson(authJsonWithServerId(authenticationServerId))
                                    else -> error("Unexpected path ${request.url.encodedPath}")
                                }
                            },
                    )
                advanceUntilIdle()

                val session =
                    fixture.repository
                        .login("https://jellyfin.example", "demo-user", "pw")
                        .getOrThrow()

                assertEquals("server-1", session.serverId)
                assertEquals("server-1", fixture.sessionStore.readSession()?.serverId)
            }
        }

    @Test
    fun passwordAuthMismatchedServerIdFailsBeforeSessionCommit() =
        runTest {
            val fixture =
                repositoryFixture(
                    engine =
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/System/Info/Public" -> respondJson(publicInfoJson)
                                "/Users/AuthenticateByName" -> respondJson(authJsonWithServerId("other-server"))
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )
            advanceUntilIdle()

            val result = fixture.repository.login("https://jellyfin.example", "demo-user", "pw")

            assertIs<AuthError.ServerError>(result.exceptionOrNull())
            assertNull(fixture.sessionStore.readSession())
            assertIs<SessionState.LoggedOut>(fixture.sessionRepository.sessionState.value)
        }

    @Test
    fun loginMapsUnauthorizedToInvalidCredentials() =
        runTest {
            val fixture =
                repositoryFixture(
                    engine =
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/System/Info/Public" -> respondJson(publicInfoJson)
                                "/Users/AuthenticateByName" ->
                                    respondJson(
                                        content = "{}",
                                        status = HttpStatusCode.Unauthorized,
                                    )
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )
            advanceUntilIdle()

            val result =
                fixture.repository.login(
                    serverUrl = "https://jellyfin.example",
                    username = "demo-user",
                    password = "bad",
                )

            assertIs<AuthError.InvalidCredentials>(result.exceptionOrNull())
        }

    @Test
    fun quickConnectPollsAuthenticatesAndPersistsSession() =
        runTest {
            var pollCount = 0
            val fixture =
                repositoryFixture(
                    engine =
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/QuickConnect/Initiate" -> respondJson(quickConnectPendingJson)
                                "/QuickConnect/Connect" -> {
                                    assertEquals("secret-1", request.url.parameters["Secret"])
                                    pollCount += 1
                                    respondJson(
                                        if (pollCount == 1) {
                                            quickConnectPendingJson
                                        } else {
                                            quickConnectAuthenticatedJson
                                        },
                                    )
                                }
                                "/Users/AuthenticateWithQuickConnect" -> respondJson(authJson)
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )
            advanceUntilIdle()
            val updates = mutableListOf<Result<QuickConnectLoginUpdate>>()

            val collectJob =
                launch {
                    fixture.repository.loginWithQuickConnect(serverInfo).toList(updates)
                }
            advanceUntilIdle()
            collectJob.join()

            val successfulUpdates = updates.map { update -> update.getOrThrow() }
            assertIs<QuickConnectLoginUpdate.CodeAvailable>(successfulUpdates[0])
            assertIs<QuickConnectLoginUpdate.Polling>(successfulUpdates[1])
            assertIs<QuickConnectLoginUpdate.Polling>(successfulUpdates[2])
            val success = assertIs<QuickConnectLoginUpdate.Success>(successfulUpdates[3])
            assertEquals("token-1", success.session.accessToken)
            assertFalse(success.session.enableContentDownloading)
            assertEquals(success.session.toStored(), fixture.sessionStore.readSession())
            assertEquals(
                SessionState.LoggedIn(success.session, boundaryEpoch = 1L),
                fixture.sessionRepository.sessionState.value,
            )
        }

    @Test
    fun quickConnectMissingOrBlankServerIdUsesCanonicalPublicId() =
        runTest {
            listOf(null, "   ").forEach { authenticationServerId ->
                val fixture =
                    repositoryFixture(
                        engine =
                            MockEngine { request ->
                                when (request.url.encodedPath) {
                                    "/QuickConnect/Initiate" -> respondJson(quickConnectAuthenticatedJson)
                                    "/Users/AuthenticateWithQuickConnect" ->
                                        respondJson(authJsonWithServerId(authenticationServerId))
                                    else -> error("Unexpected path ${request.url.encodedPath}")
                                }
                            },
                    )
                advanceUntilIdle()
                val updates = mutableListOf<Result<QuickConnectLoginUpdate>>()

                val collectJob =
                    launch {
                        fixture.repository.loginWithQuickConnect(serverInfo).toList(updates)
                    }
                advanceUntilIdle()
                collectJob.join()

                val success = assertIs<QuickConnectLoginUpdate.Success>(updates.last().getOrThrow())
                assertEquals("server-1", success.session.serverId)
                assertEquals("server-1", fixture.sessionStore.readSession()?.serverId)
            }
        }

    @Test
    fun quickConnectMismatchedServerIdFailsBeforeSessionCommit() =
        runTest {
            val fixture =
                repositoryFixture(
                    engine =
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/QuickConnect/Initiate" -> respondJson(quickConnectAuthenticatedJson)
                                "/Users/AuthenticateWithQuickConnect" ->
                                    respondJson(authJsonWithServerId("other-server"))
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )
            advanceUntilIdle()
            val updates = mutableListOf<Result<QuickConnectLoginUpdate>>()

            val collectJob =
                launch {
                    fixture.repository.loginWithQuickConnect(serverInfo).toList(updates)
                }
            advanceUntilIdle()
            collectJob.join()

            assertIs<AuthError.ServerError>(updates.last().exceptionOrNull())
            assertNull(fixture.sessionStore.readSession())
            assertIs<SessionState.LoggedOut>(fixture.sessionRepository.sessionState.value)
        }

    @Test
    fun quickConnectExpiredCodeSurfacesAuthError() =
        runTest {
            val fixture =
                repositoryFixture(
                    engine =
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/QuickConnect/Initiate" -> respondJson(quickConnectPendingJson)
                                "/QuickConnect/Connect" ->
                                    respondJson(
                                        content = "{}",
                                        status = HttpStatusCode.BadRequest,
                                    )
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )
            advanceUntilIdle()
            val updates = mutableListOf<Result<QuickConnectLoginUpdate>>()

            val collectJob =
                launch {
                    fixture.repository.loginWithQuickConnect(serverInfo).toList(updates)
                }
            advanceUntilIdle()
            collectJob.join()

            assertIs<AuthError.QuickConnectExpired>(updates.last().exceptionOrNull())
            assertNull(fixture.sessionStore.readSession())
        }

    @Test
    fun quickConnectFailureEmitsOnlyTypedSafeDiagnostic() =
        runTest {
            val messages = mutableListOf<String>()
            val writer =
                object : LogWriter() {
                    override fun log(
                        severity: Severity,
                        message: String,
                        tag: String,
                        throwable: Throwable?,
                    ) {
                        if (tag == DiagnosticTag.AuthRepository.wireValue) {
                            messages += message
                            assertNull(throwable)
                        }
                    }
                }
            val fixture =
                repositoryFixture(
                    engine =
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/QuickConnect/Initiate" -> respondJson(quickConnectPendingJson)
                                "/QuickConnect/Connect" ->
                                    respondJson(
                                        content = "{}",
                                        status = HttpStatusCode.BadRequest,
                                    )
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )
            try {
                Logger.setLogWriters(writer)
                advanceUntilIdle()
                fixture.repository.loginWithQuickConnect(serverInfo).toList()
            } finally {
                Logger.setLogWriters(emptyList())
            }

            assertTrue(messages.isNotEmpty(), messages.toString())
            val message =
                messages.firstOrNull { value -> value.contains("operation=authQuickConnect") } ?: error(messages.joinToString(" | "))
            assertTrue(message.contains("stage=quick-connect event=failed"))
            assertTrue(message.contains("exceptionType=QuickConnectExpired"))
            assertEquals(message, LogScrubber.capture(DiagnosticTag.AuthRepository.wireValue, message))
            assertFalse(message.contains("secret-1"))
            assertFalse(message.contains("jellyfin.example"))
            assertFalse(message.contains("BadRequest"))
        }

    @Test
    fun validateServerMapsConnectFailureToNotReachable() =
        runTest {
            val fixture =
                repositoryFixture(
                    engine =
                        MockEngine {
                            throw IOException("offline")
                        },
                )
            advanceUntilIdle()

            val result = fixture.repository.validateServer("https://jellyfin.example")

            assertIs<AuthError.NotReachable>(result.exceptionOrNull())
        }

    @Test
    fun logoutClearsRegistryAndSetsLoggedOut() =
        runTest {
            val fakeStore = FakeClearableStore()
            val fixture =
                repositoryFixture(
                    engine =
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/System/Info/Public" -> respondJson(publicInfoJson)
                                "/Users/AuthenticateByName" -> respondJson(authJson)
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                    extraStore = fakeStore,
                )
            advanceUntilIdle()
            val session = fixture.repository.login("https://jellyfin.example", "demo-user", "pw").getOrThrow()
            fixture.discoveryCache.getOrLoad(session.accountIdentity(), 1L, "landing") { Result.success("before") }

            assertTrue(fixture.repository.logout().isSuccess)

            var reloadCount = 0
            val cachedAfterLogout =
                fixture.discoveryCache
                    .getOrLoad(session.accountIdentity(), 1L, "landing") {
                        reloadCount += 1
                        Result.success("after")
                    }
            assertTrue(fakeStore.wasCleared)
            assertIs<JellyfinApiException.Unauthorized>(cachedAfterLogout.exceptionOrNull())
            assertEquals(0, reloadCount)
            assertEquals(
                SessionState.LoggedOut("https://jellyfin.example"),
                fixture.sessionRepository.sessionState.value,
            )
            assertEquals(null, fixture.sessionStore.readSession())
        }

    @Test
    fun logoutFinishesCleanupWhenCallerIsCancelledAfterLoggedOutPublication() =
        runTest {
            val fakeStore = FakeClearableStore()
            val fixture =
                repositoryFixture(
                    engine =
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/System/Info/Public" -> respondJson(publicInfoJson)
                                "/Users/AuthenticateByName" -> respondJson(authJson)
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                    extraStore = fakeStore,
                )
            advanceUntilIdle()
            fixture.repository.login("https://jellyfin.example", "demo-user", "pw").getOrThrow()

            lateinit var logoutJob: Job
            val cancelOnLoggedOut =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    fixture.sessionRepository.sessionState.first { state -> state is SessionState.LoggedOut }
                    logoutJob.cancel()
                }
            logoutJob = launch { fixture.repository.logout() }

            advanceUntilIdle()
            cancelOnLoggedOut.join()
            logoutJob.join()

            assertTrue(logoutJob.isCancelled)
            assertTrue(fakeStore.wasCleared)
            assertNull(fixture.sessionStore.readSession())
        }

    @Test
    fun logoutContinuesPastRegistryFailureAndPublishesEmptyAccounts() =
        runTest {
            val failingStore = FakeClearableStore(IllegalStateException("cache failure"))
            val fixture =
                repositoryFixture(
                    engine =
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/System/Info/Public" -> respondJson(publicInfoJson)
                                "/Users/AuthenticateByName" -> respondJson(authJson)
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                    extraStore = failingStore,
                )
            advanceUntilIdle()
            val session = fixture.repository.login("https://jellyfin.example", "demo-user", "pw").getOrThrow()

            assertIs<StoreCleanupException>(fixture.repository.logout().exceptionOrNull())

            assertTrue(failingStore.wasCleared)
            assertNull(fixture.sessionStore.readSession())
            assertEquals(emptyList(), fixture.sessionRepository.accounts.value)
            assertEquals(
                SessionState.LoggedOut(session.serverUrl),
                fixture.sessionRepository.sessionState.value,
            )
        }

    @Test
    fun addAccountAndSwitchToUpdateActiveSessionAndAccounts() =
        runTest {
            var authCount = 0
            val fixture =
                repositoryFixture(
                    engine =
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/System/Info/Public" -> respondJson(publicInfoJson)
                                "/Users/AuthenticateByName" -> {
                                    authCount += 1
                                    respondJson(
                                        if (authCount == 1) {
                                            authJson
                                        } else {
                                            authJsonUser2
                                        },
                                    )
                                }
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )
            advanceUntilIdle()

            val first = fixture.repository.login("https://jellyfin.example", "demo-user", "pw").getOrThrow()
            val second = fixture.repository.addAccount("https://jellyfin.example", "kavya", "pw").getOrThrow()

            assertEquals(second, (fixture.sessionRepository.sessionState.value as SessionState.LoggedIn).session)
            assertEquals(2, fixture.sessionRepository.accounts.value.size)
            assertEquals(
                1,
                fixture.sessionRepository.accounts.value
                    .count { account -> account.isActive },
            )

            fixture.sessionRepository.switchTo(first.toStored().accountId()).getOrThrow()

            assertEquals(first, (fixture.sessionRepository.sessionState.value as SessionState.LoggedIn).session)
            assertEquals(
                first.userId,
                fixture.sessionRepository.accounts.value
                    .first { account -> account.isActive }
                    .userId,
            )
        }

    @Test
    fun signOutActiveAccountSwitchesToAnotherAndClearsOnlyRemovedServer() =
        runTest {
            val first = storedSession(serverId = "server-1", userId = "user-1")
            val second = storedSession(serverId = "server-2", userId = "user-2")
            val serverStore = FakeServerScopedStore()
            val fixture =
                repositoryFixtureWithStoredSessions(
                    first = first,
                    second = second,
                    activeAccountId = first.accountId(),
                    serverStore = serverStore,
                )
            advanceUntilIdle()

            val result = fixture.repository.signOut(first.accountId())

            assertTrue(result.isSuccess)
            assertEquals(
                SessionState.LoggedIn(second.toDomain(), boundaryEpoch = 2L),
                fixture.sessionRepository.sessionState.value,
            )
            assertEquals(listOf(second), fixture.sessionStore.readAccounts())
            assertEquals(listOf("server-1"), serverStore.clearedServerIds)
            assertFalse(serverStore.wasClearedAll)
        }

    @Test
    fun signOutSameServerAccountDoesNotClearSharedServerStores() =
        runTest {
            val first = storedSession(serverId = "server-1", userId = "user-1")
            val second = storedSession(serverId = "server-1", userId = "user-2")
            val serverStore = FakeServerScopedStore()
            val fixture =
                repositoryFixtureWithStoredSessions(
                    first = first,
                    second = second,
                    activeAccountId = first.accountId(),
                    serverStore = serverStore,
                )
            advanceUntilIdle()

            val result = fixture.repository.signOut(first.accountId())

            assertTrue(result.isSuccess)
            assertEquals(
                SessionState.LoggedIn(second.toDomain(), boundaryEpoch = 2L),
                fixture.sessionRepository.sessionState.value,
            )
            assertEquals(listOf(second), fixture.sessionStore.readAccounts())
            assertEquals(emptyList(), serverStore.clearedServerIds)
            assertFalse(serverStore.wasClearedAll)
        }

    @Test
    fun signOutLastAccountMovesToLoggedOut() =
        runTest {
            val first = storedSession(serverId = "server-1", userId = "user-1")
            val serverStore = FakeServerScopedStore()
            val fixture =
                repositoryFixtureWithStoredSessions(
                    first = first,
                    second = first,
                    activeAccountId = first.accountId(),
                    serverStore = serverStore,
                )
            advanceUntilIdle()

            val result = fixture.repository.signOut(first.accountId())

            assertTrue(result.isSuccess)
            assertEquals(
                SessionState.LoggedOut(first.serverUrl),
                fixture.sessionRepository.sessionState.value,
            )
            assertEquals(emptyList(), fixture.sessionStore.readAccounts())
            assertEquals(listOf("server-1"), serverStore.clearedServerIds)
        }

    @Test
    fun removalActionsCarryDefaultAuthorizationAndFailClosedBeforeCredentials() =
        runTest {
            val first = storedSession(serverId = "server-1", userId = "user-1")
            val second = storedSession(serverId = "server-2", userId = "user-2")
            val participant = RejectingAuthRemovalParticipant()
            val fixture =
                repositoryFixtureWithStoredSessions(
                    first = first,
                    second = second,
                    activeAccountId = first.accountId(),
                    serverStore = FakeServerScopedStore(),
                    participant = participant,
                )
            advanceUntilIdle()

            val signOutFailure =
                SignOutAccountAction(fixture.repository)(first.accountId()).exceptionOrNull()
            val logoutFailure = LogoutAction(fixture.repository)().exceptionOrNull()

            assertIs<SessionRemovalError.DownloadRemovalConfirmationRequired>(signOutFailure)
            assertIs<SessionRemovalError.DownloadRemovalConfirmationRequired>(logoutFailure)
            assertEquals(
                listOf(
                    SessionRemovalScope.Account(first.toDomain().accountIdentity()),
                    SessionRemovalScope.FullLogout(
                        listOf(
                            first.toDomain().accountIdentity(),
                            second.toDomain().accountIdentity(),
                        ),
                    ),
                ),
                participant.scopes,
            )
            assertEquals(
                listOf<SessionRemovalAuthorization>(
                    SessionRemovalAuthorization.None,
                    SessionRemovalAuthorization.None,
                ),
                participant.authorizations,
            )
            assertEquals(listOf(first, second), fixture.sessionStore.readAccounts())
            assertEquals(
                SessionState.LoggedIn(first.toDomain(), boundaryEpoch = 1L),
                fixture.sessionRepository.sessionState.value,
            )
        }

    @Test
    fun removalActionsCarryParticipantIssuedAuthorizationWithoutInterpretingIt() =
        runTest {
            val first = storedSession(serverId = "server-1", userId = "user-1")
            val participant = RejectingAuthRemovalParticipant(SessionRemovalError.ConfirmationStale)
            val fixture =
                repositoryFixtureWithStoredSessions(
                    first = first,
                    second = first,
                    activeAccountId = first.accountId(),
                    serverStore = FakeServerScopedStore(),
                    participant = participant,
                )
            advanceUntilIdle()
            val authorization = SessionRemovalAuthorization.confirmed(Any())

            val signOutFailure =
                SignOutAccountAction(fixture.repository)(first.accountId(), authorization).exceptionOrNull()
            val logoutFailure = LogoutAction(fixture.repository)(authorization).exceptionOrNull()

            assertIs<SessionRemovalError.ConfirmationStale>(signOutFailure)
            assertIs<SessionRemovalError.ConfirmationStale>(logoutFailure)
            assertEquals(2, participant.authorizations.size)
            assertTrue(participant.authorizations.all { carried -> carried === authorization })
            assertEquals(listOf(first), fixture.sessionStore.readAccounts())
        }
}

private fun TestScope.repositoryFixture(
    engine: MockEngine,
    extraStore: ClearableStore? = null,
): AuthRepositoryFixture {
    val sessionStore = SessionStore(FakeSecureStore(), Json)
    val registry = ServerScopedStoreRegistry()
    val discoveryCache = DiscoveryCache(registry)
    registry.register(sessionStore)
    registry.register(discoveryCache)
    extraStore?.let(registry::register)
    val workDispatcher = StandardTestDispatcher(testScheduler)
    val sessionTransitionCoordinator =
        SessionTransitionCoordinator(
            serverScopedStoreRegistry = registry,
            sessionStore = sessionStore,
            ioDispatcher = workDispatcher,
        )
    val sessionRepository =
        DefaultSessionRepository(
            sessionStore = sessionStore,
            sessionTransitionCoordinator = sessionTransitionCoordinator,
            scope = this,
            workDispatcher = workDispatcher,
        )
    val repository =
        DefaultAuthRepository(
            jellyfinApi =
                KtorJellyfinApi(
                    client = mockClient(engine),
                    authHeaderProvider =
                        object : AuthHeaderProvider {
                            override suspend fun authHeader(token: String?) = "MediaBrowser Test"
                        },
                ),
            sessionStore = sessionStore,
            sessionRepository = sessionRepository,
            sessionTransitionCoordinator = sessionTransitionCoordinator,
            deviceInfoProvider = FakeDeviceInfoProvider(),
            workDispatcher = workDispatcher,
        )

    return AuthRepositoryFixture(
        repository = repository,
        sessionStore = sessionStore,
        sessionRepository = sessionRepository,
        discoveryCache = discoveryCache,
        coordinator = sessionTransitionCoordinator,
    )
}

private suspend fun TestScope.repositoryFixtureWithStoredSessions(
    first: StoredSession,
    second: StoredSession,
    activeAccountId: String,
    serverStore: FakeServerScopedStore,
    participant: SessionBoundaryParticipant = NoOpSessionBoundaryParticipant,
): AuthRepositoryFixture {
    val sessionStore = SessionStore(FakeSecureStore(), Json)
    sessionStore.writeSession(first)
    sessionStore.writeSession(second)
    sessionStore.switchActiveAccount(activeAccountId)
    val registry = ServerScopedStoreRegistry()
    val discoveryCache = DiscoveryCache(registry)
    registry.register(sessionStore)
    registry.register(discoveryCache)
    registry.register(serverStore)
    val workDispatcher = StandardTestDispatcher(testScheduler)
    val sessionTransitionCoordinator =
        SessionTransitionCoordinator(
            serverScopedStoreRegistry = registry,
            sessionStore = sessionStore,
            sessionBoundaryParticipant = participant,
            ioDispatcher = workDispatcher,
        )
    val sessionRepository =
        DefaultSessionRepository(
            sessionStore = sessionStore,
            sessionTransitionCoordinator = sessionTransitionCoordinator,
            scope = this,
            workDispatcher = workDispatcher,
        )
    val repository =
        DefaultAuthRepository(
            jellyfinApi =
                KtorJellyfinApi(
                    client =
                        mockClient(
                            MockEngine { request ->
                                error("Unexpected path ${request.url.encodedPath}")
                            },
                        ),
                    authHeaderProvider =
                        object : AuthHeaderProvider {
                            override suspend fun authHeader(token: String?) = "MediaBrowser Test"
                        },
                ),
            sessionStore = sessionStore,
            sessionRepository = sessionRepository,
            sessionTransitionCoordinator = sessionTransitionCoordinator,
            deviceInfoProvider = FakeDeviceInfoProvider(),
            workDispatcher = workDispatcher,
        )

    return AuthRepositoryFixture(
        repository = repository,
        sessionStore = sessionStore,
        sessionRepository = sessionRepository,
        discoveryCache = discoveryCache,
        coordinator = sessionTransitionCoordinator,
    )
}

private data class AuthRepositoryFixture(
    val repository: DefaultAuthRepository,
    val sessionStore: SessionStore,
    val sessionRepository: DefaultSessionRepository,
    val discoveryCache: DiscoveryCache,
    val coordinator: SessionTransitionCoordinator,
)

private class FakeDeviceInfoProvider : DeviceInfoProvider {
    override val deviceName = "Test Device"

    override fun newDeviceId() = "device-1"
}

private class FakeClearableStore(
    private val failure: Throwable? = null,
) : ClearableStore {
    var wasCleared = false
        private set

    override suspend fun clearServerScoped() {
        wasCleared = true
        failure?.let { throw it }
    }
}

private class FakeServerScopedStore : ServerScopedClearableStore {
    var wasClearedAll = false
        private set
    val clearedServerIds = mutableListOf<String>()

    override suspend fun clearServerScoped() {
        wasClearedAll = true
    }

    override suspend fun clearServerScoped(serverId: String) {
        clearedServerIds += serverId
    }
}

private class RejectingAuthRemovalParticipant(
    private val failure: SessionRemovalError = SessionRemovalError.DownloadRemovalConfirmationRequired,
) : SessionBoundaryParticipant {
    val scopes = mutableListOf<SessionRemovalScope>()
    val authorizations = mutableListOf<SessionRemovalAuthorization>()

    override suspend fun beforeAccountSwitch(
        accountIdentity: AccountIdentity,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun prepareRemoval(
        scope: SessionRemovalScope,
        authorization: SessionRemovalAuthorization,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<PreparedSessionRemoval?> {
        scopes += scope
        authorizations += authorization
        return Result.failure(failure)
    }

    override suspend fun completeRemoval(
        removal: PreparedSessionRemoval,
        executor: SessionRemovalExecutor,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> = error("Rejected removal must not complete")

    override suspend fun resumeIncompleteRemovalOperations(
        executor: SessionRemovalExecutor,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> = Result.success(Unit)
}

private fun mockClient(engine: MockEngine): HttpClient =
    HttpClient(engine) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                },
            )
        }
    }

private fun MockRequestHandleScope.respondJson(
    content: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = content,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

private val serverInfo =
    ServerInfo(
        serverUrl = "https://jellyfin.example",
        serverId = "server-1",
        serverName = "Home Jellyfin",
        version = "10.10.0",
        productName = "Jellyfin Server",
    )

private val publicInfoJson =
    """
    {
      "ServerName": "Home Jellyfin",
      "Version": "10.10.0",
      "Id": "server-1",
      "ProductName": "Jellyfin Server"
    }
    """.trimIndent()

private val quickConnectPendingJson =
    """
    {
      "Secret": "secret-1",
      "Code": "ABCDEF",
      "Authenticated": false
    }
    """.trimIndent()

private val quickConnectAuthenticatedJson =
    """
    {
      "Secret": "secret-1",
      "Code": "ABCDEF",
      "Authenticated": true
    }
    """.trimIndent()

private val authJson =
    """
    {
      "AccessToken": "token-1",
      "User": {
        "Id": "user-1",
        "Name": "Demo User",
        "Policy": {
          "EnableContentDownloading": true,
          "EnableSubtitleManagement": true
        }
      },
      "ServerId": "server-1"
    }
    """.trimIndent()

private val authJsonUser2 =
    """
    {
      "AccessToken": "token-2",
      "User": {
        "Id": "user-2",
        "Name": "Kavya",
        "Policy": {
          "EnableContentDownloading": true,
          "EnableSubtitleManagement": true
        }
      },
      "ServerId": "server-1"
    }
    """.trimIndent()

private fun authJsonWithServerId(serverId: String?): String {
    val serverIdField = serverId?.let { value -> ",\n      \"ServerId\": \"$value\"" }.orEmpty()
    return """
        {
          "AccessToken": "token-1",
          "User": {
            "Id": "user-1",
            "Name": "Demo User"
          }$serverIdField
        }
        """.trimIndent()
}

private fun storedSession(
    serverId: String,
    userId: String,
): StoredSession =
    StoredSession(
        serverUrl = "https://$serverId.example",
        serverId = serverId,
        serverName = "Server $serverId",
        userId = userId,
        userName = "User $userId",
        accessToken = "token-$userId",
        deviceId = "device-1",
    )
