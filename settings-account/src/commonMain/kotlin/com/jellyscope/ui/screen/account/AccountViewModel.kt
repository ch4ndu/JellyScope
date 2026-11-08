// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.domain.action.AddAccountAction
import com.jellyscope.core.domain.action.AuthError
import com.jellyscope.core.domain.action.SessionRemovalAuthorization
import com.jellyscope.core.domain.action.SessionRemovalError
import com.jellyscope.core.domain.action.SessionRemovalScope
import com.jellyscope.core.domain.action.SignOutAccountAction
import com.jellyscope.core.domain.action.SwitchAccountAction
import com.jellyscope.core.domain.model.AccountSession
import com.jellyscope.core.domain.model.DownloadRemovalPreview
import com.jellyscope.core.domain.usecase.DownloadRemovalAuthorizationIssuer
import com.jellyscope.core.domain.usecase.DownloadRemovalPreviewReader
import com.jellyscope.core.domain.usecase.DownloadRemovalPreviewReleaser
import com.jellyscope.core.domain.usecase.ObserveAccountsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AccountViewModel(
    observeAccountsUseCase: ObserveAccountsUseCase,
    private val addAccountAction: AddAccountAction,
    private val switchAccountAction: SwitchAccountAction,
    private val signOutAccountAction: SignOutAccountAction,
    private val getDownloadRemovalPreviewUseCase: DownloadRemovalPreviewReader? = null,
    private val issueDownloadRemovalAuthorizationUseCase: DownloadRemovalAuthorizationIssuer? = null,
    private val releaseDownloadRemovalPreviewUseCase: DownloadRemovalPreviewReleaser? = null,
    private val workDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) : ViewModel() {
    private val _state = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = _state.asStateFlow()
    private var removal: AccountRemoval? = null

    private fun reduce(change: AccountChange) {
        removal =
            removal?.let { current ->
                when (change) {
                    is AccountChange.RemovalStarted -> current.copy(phase = AccountRemovalPhase.ReadingPreview)
                    AccountChange.PreviewFinished ->
                        current.copy(phase = AccountRemovalPhase.Authorizing(current.phase.confirmation))
                    AccountChange.RemovalConfirmed -> current.copy(phase = AccountRemovalPhase.Authorizing())
                    is AccountChange.PreviewLoaded ->
                        current.copy(phase = AccountRemovalPhase.AwaitingConfirmation(change.accountId, change.preview))
                    is AccountChange.PreviewRefreshed ->
                        current.copy(phase = AccountRemovalPhase.AwaitingConfirmation(change.accountId, change.preview))
                    is AccountChange.RemovalFinished -> current.copy(phase = AccountRemovalPhase.Finished)
                    AccountChange.RemovalDismissed -> null
                    else -> current
                }
            }
        _state.update { current -> reduceAccount(current, change) }
    }

    fun accept(intent: AccountIntent) {
        when (intent) {
            is AccountIntent.AddAccount -> addAccount(intent.serverUrl, intent.username, intent.password)
            is AccountIntent.SwitchTo -> switchTo(intent.accountId)
            is AccountIntent.SignOut -> signOut(intent.accountId)
            AccountIntent.ConfirmPendingRemoval -> confirmPendingRemoval()
            AccountIntent.DismissPendingRemoval -> dismissPendingRemoval()
        }
    }

    init {
        viewModelScope.launch {
            observeAccountsUseCase().collect { accounts ->
                reduce(AccountChange.AccountsObserved(accounts))
            }
        }
    }

    private fun addAccount(
        serverUrl: String,
        username: String,
        password: String,
    ) {
        if (_state.value.isAddingAccount) {
            return
        }

        viewModelScope.launch {
            reduce(AccountChange.AddStarted)
            val result =
                withContext(workDispatcher) {
                    addAccountAction(
                        serverUrl = serverUrl,
                        username = username,
                        password = password,
                    )
                }
            reduce(AccountChange.AddFinished(result.exceptionOrNull()?.toUiError()))
        }
    }

    private fun switchTo(accountId: String) {
        if (_state.value.switchingAccountId != null) {
            return
        }

        viewModelScope.launch {
            reduce(AccountChange.SwitchStarted(accountId))
            val result = withContext(workDispatcher) { switchAccountAction(accountId) }
            reduce(AccountChange.SwitchFinished(result.exceptionOrNull()?.toUiError()))
        }
    }

    private fun signOut(accountId: String) {
        if (_state.value.signingOutAccountId != null) {
            return
        }

        val account = _state.value.accounts.firstOrNull { item -> item.accountId == accountId } ?: return
        val scope = SessionRemovalScope.Account(accountIdentity(account))
        val previewUseCase = getDownloadRemovalPreviewUseCase
        val issueAuthorization = issueDownloadRemovalAuthorizationUseCase
        removal = AccountRemoval(accountId, scope, AccountRemovalPhase.ReadingPreview)
        viewModelScope.launch {
            reduce(AccountChange.RemovalStarted(accountId, previewUseCase != null))
            if (previewUseCase == null || issueAuthorization == null) {
                signOutWithAuthorization(accountId, SessionRemovalAuthorization.None)
                return@launch
            }
            val preview = loadRemovalPreview(scope)
            reduce(AccountChange.PreviewFinished)
            if (preview == null) {
                finishSignOut(accountId, AccountUiError.RemovalFailed)
            } else if (preview.confirmations.any { confirmation -> confirmation.recordCount > 0L }) {
                reduce(AccountChange.PreviewLoaded(preview, accountId))
            } else {
                val authorization =
                    try {
                        withContext(workDispatcher) { issueAuthorization(scope, preview) }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (stale: SessionRemovalError.ConfirmationStale) {
                        showFreshRemovalPreview(accountId, scope)
                        return@launch
                    } catch (_: Throwable) {
                        null
                    }
                if (authorization == null) {
                    finishSignOut(accountId, AccountUiError.RemovalFailed)
                } else {
                    signOutWithAuthorization(accountId, authorization)
                }
            }
        }
    }

    private fun confirmPendingRemoval() {
        val transaction = removal ?: return
        val pending = transaction.phase.confirmation ?: return
        val accountId = pending.accountId
        val issueAuthorization = issueDownloadRemovalAuthorizationUseCase ?: return
        val preview = pending.preview
        val scope = transaction.scope
        if (_state.value.signingOutAccountId == null) return
        viewModelScope.launch {
            reduce(AccountChange.RemovalConfirmed)
            val authorization =
                try {
                    withContext(workDispatcher) { issueAuthorization(scope, preview) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (stale: SessionRemovalError.ConfirmationStale) {
                    showFreshRemovalPreview(accountId, scope)
                    return@launch
                } catch (_: Throwable) {
                    null
                }
            if (authorization == null) {
                finishSignOut(accountId, AccountUiError.RemovalFailed)
            } else {
                signOutWithAuthorization(accountId, authorization)
            }
        }
    }

    private fun dismissPendingRemoval() {
        val transaction = removal
        val preview = transaction?.phase?.confirmation?.preview
        val scope = transaction?.scope
        val release = releaseDownloadRemovalPreviewUseCase
        if (preview == null || scope == null || release == null) {
            removal = null
            reduce(AccountChange.RemovalDismissed)
            return
        }

        // Capture this dismissal's cleanup obligation before ViewModel cancellation can run.
        // Keep the preview published until finally: confirmation and repeated dismissal remain admitted.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable + workDispatcher) { release(scope, preview) }
            } catch (_: CancellationException) {
                // The cleanup coordinator has already made the two guard removals atomic;
                // only the follow-up platform wake may have been cancelled.
            } catch (_: Throwable) {
                // The row is already in a resumable state; lifecycle recovery can retry
                // scheduling without keeping the destructive dialog visible.
            } finally {
                removal = null
                reduce(AccountChange.RemovalDismissed)
            }
        }
    }

    private suspend fun signOutWithAuthorization(
        accountId: String,
        authorization: SessionRemovalAuthorization,
    ) {
        removal = removal?.let { it.copy(phase = AccountRemovalPhase.Removing(it.phase.confirmation)) }
        val result: Result<Unit> =
            try {
                withContext(workDispatcher) { signOutAccountAction(accountId, authorization) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Result.failure(throwable)
            }
        reduce(AccountChange.RemovalFinished(result.exceptionOrNull()?.toUiError()))
        if (result.exceptionOrNull() is SessionRemovalError.ConfirmationStale) {
            showFreshRemovalPreview(accountId, removal?.scope ?: return)
        } else if (result.isSuccess) {
            removal = null
        }
    }

    private suspend fun loadRemovalPreview(scope: SessionRemovalScope.Account): DownloadRemovalPreview? {
        val previewUseCase = getDownloadRemovalPreviewUseCase ?: return null
        return try {
            withContext(workDispatcher) { previewUseCase(scope) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun showFreshRemovalPreview(
        accountId: String,
        scope: SessionRemovalScope.Account,
    ) {
        val preview = loadRemovalPreview(scope)
        if (preview == null) {
            finishSignOut(accountId, AccountUiError.RemovalFailed)
            return
        }
        if (preview.confirmations.none { confirmation -> confirmation.recordCount > 0L }) {
            val issuer = issueDownloadRemovalAuthorizationUseCase
            val authorization =
                try {
                    issuer?.let { candidate -> withContext(workDispatcher) { candidate(scope, preview) } }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    null
                }
            if (authorization != null) {
                signOutWithAuthorization(accountId, authorization)
                return
            }
        }
        reduce(AccountChange.PreviewRefreshed(accountId, preview))
    }

    private fun finishSignOut(
        accountId: String,
        error: AccountUiError,
    ) {
        reduce(AccountChange.RemovalFinished(error))
    }
}

private fun accountIdentity(account: AccountSession) =
    com.jellyscope.core.domain.model.AccountIdentity(
        serverId = account.serverId,
        userId = account.userId,
    )

private fun Throwable.toUiError(): AccountUiError =
    when (this) {
        AuthError.InvalidCredentials -> AccountUiError.InvalidCredentials
        AuthError.NotReachable -> AccountUiError.NotReachable
        AuthError.AccountNotFound -> AccountUiError.AccountNotFound
        is SessionRemovalError -> AccountUiError.RemovalFailed
        else -> AccountUiError.ServerError
    }

private data class AccountRemoval(
    val accountId: String,
    val scope: SessionRemovalScope.Account,
    val phase: AccountRemovalPhase,
)

private sealed interface AccountRemovalPhase {
    val confirmation: AwaitingConfirmation? get() = null

    data object ReadingPreview : AccountRemovalPhase

    data class AwaitingConfirmation(
        val accountId: String,
        val preview: DownloadRemovalPreview,
    ) : AccountRemovalPhase {
        override val confirmation: AwaitingConfirmation get() = this
    }

    // Overlapping callbacks may leave an earlier confirmation visible during authorization/removal.
    data class Authorizing(
        override val confirmation: AwaitingConfirmation? = null,
    ) : AccountRemovalPhase

    data class Removing(
        override val confirmation: AwaitingConfirmation?,
    ) : AccountRemovalPhase

    // Failures keep the scope available to the existing stale-preview path.
    data object Finished : AccountRemovalPhase
}
