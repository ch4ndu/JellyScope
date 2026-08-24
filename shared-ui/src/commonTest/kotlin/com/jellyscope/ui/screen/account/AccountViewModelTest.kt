// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.account

import androidx.lifecycle.viewModelScope
import com.jellyscope.core.data.repository.AuthRepository
import com.jellyscope.core.data.repository.SessionRepository
import com.jellyscope.core.domain.action.AddAccountAction
import com.jellyscope.core.domain.action.SessionRemovalAuthorization
import com.jellyscope.core.domain.action.SessionRemovalError
import com.jellyscope.core.domain.action.SessionRemovalScope
import com.jellyscope.core.domain.action.SignOutAccountAction
import com.jellyscope.core.domain.action.SwitchAccountAction
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.AccountSession
import com.jellyscope.core.domain.model.DownloadRemovalConfirmation
import com.jellyscope.core.domain.model.DownloadRemovalPreview
import com.jellyscope.core.domain.model.ServerInfo
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.usecase.DownloadRemovalAuthorizationIssuer
import com.jellyscope.core.domain.usecase.DownloadRemovalPreviewReader
import com.jellyscope.core.domain.usecase.DownloadRemovalPreviewReleaser
import com.jellyscope.core.domain.usecase.ObserveAccountsUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AccountViewModelTest {
    @Test
    fun failedSignOutClearsBusyStateAndSurfacesRemovalFailure() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val account =
                    AccountSession(
                        accountId = "server-1:user-1",
                        serverUrl = "https://jellyfin.example",
                        serverId = "server-1",
                        serverName = "Jellyfin",
                        userId = "user-1",
                        userName = "Murali",
                        avatarUserId = "user-1",
                        isActive = true,
                    )
                val sessionRepository = TestSessionRepository(account)
                val authRepository =
                    TestAuthRepository(
                        signOutResult = Result.failure(SessionRemovalError.ArtifactInUse),
                    )
                val viewModel =
                    AccountViewModel(
                        observeAccountsUseCase = ObserveAccountsUseCase(sessionRepository),
                        addAccountAction = AddAccountAction(authRepository),
                        switchAccountAction = SwitchAccountAction(sessionRepository),
                        signOutAccountAction = SignOutAccountAction(authRepository),
                        workDispatcher = dispatcher,
                    )

                advanceUntilIdle()
                viewModel.signOut(account.accountId)
                advanceUntilIdle()

                assertEquals(1, authRepository.signOutCalls)
                assertFalse(viewModel.state.value.signingOutAccountId != null)
                assertEquals(AccountUiError.RemovalFailed, viewModel.state.value.error)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun accountRemovalConfirmationUsesDisplayedPreviewAndDismissalReleasesIt() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val account =
                    AccountSession(
                        accountId = "server-1:user-1",
                        serverUrl = "https://jellyfin.example",
                        serverId = "server-1",
                        serverName = "Jellyfin",
                        userId = "user-1",
                        userName = "Murali",
                        avatarUserId = "user-1",
                        isActive = true,
                    )
                val preview =
                    DownloadRemovalPreview(
                        membershipRevision = 3L,
                        confirmations =
                            listOf(
                                DownloadRemovalConfirmation(
                                    accountIdentity = AccountIdentity("server-1", "user-1"),
                                    membershipRevision = 3L,
                                    recordCount = 1L,
                                    displayedBytes = 64L,
                                ),
                            ),
                    )
                val reader = FakeRemovalPreviewReader(preview)
                val issuer = FakeRemovalAuthorizationIssuer()
                val releaser = FakeRemovalPreviewReleaser()
                val authRepository = TestAuthRepository(Result.success(Unit))
                val viewModel =
                    AccountViewModel(
                        observeAccountsUseCase = ObserveAccountsUseCase(TestSessionRepository(account)),
                        addAccountAction = AddAccountAction(authRepository),
                        switchAccountAction = SwitchAccountAction(TestSessionRepository(account)),
                        signOutAccountAction = SignOutAccountAction(authRepository),
                        getDownloadRemovalPreviewUseCase = reader,
                        issueDownloadRemovalAuthorizationUseCase = issuer,
                        releaseDownloadRemovalPreviewUseCase = releaser,
                        workDispatcher = dispatcher,
                    )

                advanceUntilIdle()
                viewModel.signOut(account.accountId)
                advanceUntilIdle()
                assertSame(preview, viewModel.state.value.removalPreview)

                viewModel.confirmPendingRemoval()
                advanceUntilIdle()
                assertSame(preview, issuer.preview)
                assertEquals(1, authRepository.signOutCalls)

                // A second preview is needed to exercise the explicit dismissal release path.
                viewModel.signOut(account.accountId)
                advanceUntilIdle()
                viewModel.dismissPendingRemoval()
                advanceUntilIdle()
                assertEquals(1, releaser.calls)
                assertEquals(preview, releaser.preview)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun dismissalReleaseSurvivesImmediateViewModelCancellation() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val account =
                    AccountSession(
                        accountId = "server-1:user-1",
                        serverUrl = "https://jellyfin.example",
                        serverId = "server-1",
                        serverName = "Jellyfin",
                        userId = "user-1",
                        userName = "Murali",
                        avatarUserId = "user-1",
                        isActive = true,
                    )
                val preview =
                    DownloadRemovalPreview(
                        membershipRevision = 3L,
                        confirmations =
                            listOf(
                                DownloadRemovalConfirmation(
                                    accountIdentity = AccountIdentity("server-1", "user-1"),
                                    membershipRevision = 3L,
                                    recordCount = 1L,
                                    displayedBytes = 64L,
                                ),
                            ),
                    )
                val releaser = BlockingRemovalPreviewReleaser()
                val authRepository = TestAuthRepository(Result.success(Unit))
                val viewModel =
                    AccountViewModel(
                        observeAccountsUseCase = ObserveAccountsUseCase(TestSessionRepository(account)),
                        addAccountAction = AddAccountAction(authRepository),
                        switchAccountAction = SwitchAccountAction(TestSessionRepository(account)),
                        signOutAccountAction = SignOutAccountAction(authRepository),
                        getDownloadRemovalPreviewUseCase = FakeRemovalPreviewReader(preview),
                        issueDownloadRemovalAuthorizationUseCase = FakeRemovalAuthorizationIssuer(),
                        releaseDownloadRemovalPreviewUseCase = releaser,
                        workDispatcher = dispatcher,
                    )

                advanceUntilIdle()
                viewModel.signOut(account.accountId)
                advanceUntilIdle()
                viewModel.dismissPendingRemoval()
                viewModel.viewModelScope.cancel()
                runCurrent()
                assertTrue(releaser.started.isCompleted)

                releaser.finish.complete(Unit)
                advanceUntilIdle()

                assertEquals(1, releaser.calls)
                assertEquals(null, viewModel.state.value.removalPreview)
                assertEquals(null, viewModel.state.value.removalPreviewAccountId)
            } finally {
                Dispatchers.resetMain()
            }
        }
}

private class FakeRemovalPreviewReader(
    private val value: DownloadRemovalPreview,
) : DownloadRemovalPreviewReader {
    override suspend fun invoke(scope: SessionRemovalScope): DownloadRemovalPreview = value
}

private class FakeRemovalAuthorizationIssuer : DownloadRemovalAuthorizationIssuer {
    var preview: DownloadRemovalPreview? = null

    override suspend fun invoke(
        scope: SessionRemovalScope,
        preview: DownloadRemovalPreview,
    ): SessionRemovalAuthorization {
        this.preview = preview
        return SessionRemovalAuthorization.None
    }
}

private class FakeRemovalPreviewReleaser : DownloadRemovalPreviewReleaser {
    var calls = 0
    var preview: DownloadRemovalPreview? = null

    override suspend fun invoke(
        scope: SessionRemovalScope,
        preview: DownloadRemovalPreview,
    ): Boolean {
        calls += 1
        this.preview = preview
        return true
    }
}

private class BlockingRemovalPreviewReleaser : DownloadRemovalPreviewReleaser {
    val started = CompletableDeferred<Unit>()
    val finish = CompletableDeferred<Unit>()
    var calls = 0

    override suspend fun invoke(
        scope: SessionRemovalScope,
        preview: DownloadRemovalPreview,
    ): Boolean {
        calls += 1
        started.complete(Unit)
        finish.await()
        return true
    }
}

private class TestSessionRepository(
    account: AccountSession,
) : SessionRepository {
    override val sessionState = MutableStateFlow<SessionState>(SessionState.LoggedOut(serverUrl = null))
    override val accounts = MutableStateFlow(listOf(account))

    override suspend fun setLoggedIn(session: Session) = Unit

    override suspend fun setLoggedOut(
        serverUrl: String?,
        authorization: SessionRemovalAuthorization,
    ): Result<Unit> = Result.success(Unit)
}

private class TestAuthRepository(
    private val signOutResult: Result<Unit>,
) : AuthRepository {
    var signOutCalls: Int = 0

    override suspend fun validateServer(input: String): Result<ServerInfo> = Result.failure(UnsupportedOperationException())

    override suspend fun login(
        serverUrl: String,
        username: String,
        password: String,
    ): Result<Session> = Result.failure(UnsupportedOperationException())

    override suspend fun signOut(
        accountId: String,
        authorization: SessionRemovalAuthorization,
    ): Result<Unit> {
        signOutCalls += 1
        return signOutResult
    }

    override suspend fun logout(authorization: SessionRemovalAuthorization): Result<Unit> = Result.success(Unit)
}
