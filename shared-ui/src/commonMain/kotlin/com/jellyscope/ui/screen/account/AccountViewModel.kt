// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.data.repository.SessionRemovalAuthorization
import com.jellyscope.core.data.repository.SessionRemovalError
import com.jellyscope.core.data.repository.SessionRemovalScope
import com.jellyscope.core.domain.action.AddAccountAction
import com.jellyscope.core.domain.action.SignOutAccountAction
import com.jellyscope.core.domain.action.SwitchAccountAction
import com.jellyscope.core.domain.model.AccountSession
import com.jellyscope.core.domain.model.AuthError
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

data class AccountUiState(
    val accounts: List<AccountSession> = emptyList(),
    val isAddingAccount: Boolean = false,
    val switchingAccountId: String? = null,
    val signingOutAccountId: String? = null,
    val isLoadingRemovalPreview: Boolean = false,
    val removalPreview: DownloadRemovalPreview? = null,
    val removalPreviewAccountId: String? = null,
    val error: AccountUiError? = null,
)

sealed interface AccountUiError {
    data object InvalidCredentials : AccountUiError

    data object NotReachable : AccountUiError

    data object AccountNotFound : AccountUiError

    data object ServerError : AccountUiError

    data object RemovalFailed : AccountUiError
}

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
    private var removalScope: SessionRemovalScope.Account? = null

    init {
        viewModelScope.launch {
            observeAccountsUseCase().collect { accounts ->
                _state.update { state -> state.copy(accounts = accounts) }
            }
        }
    }

    fun addAccount(
        serverUrl: String,
        username: String,
        password: String,
    ) {
        if (_state.value.isAddingAccount) {
            return
        }

        viewModelScope.launch {
            _state.update { state -> state.copy(isAddingAccount = true, error = null) }
            val result =
                withContext(workDispatcher) {
                    addAccountAction(
                        serverUrl = serverUrl,
                        username = username,
                        password = password,
                    )
                }
            _state.update { state ->
                state.copy(
                    isAddingAccount = false,
                    error = result.exceptionOrNull()?.toUiError(),
                )
            }
        }
    }

    fun switchTo(accountId: String) {
        if (_state.value.switchingAccountId != null) {
            return
        }

        viewModelScope.launch {
            _state.update { state -> state.copy(switchingAccountId = accountId, error = null) }
            val result = withContext(workDispatcher) { switchAccountAction(accountId) }
            _state.update { state ->
                state.copy(
                    switchingAccountId = null,
                    error = result.exceptionOrNull()?.toUiError(),
                )
            }
        }
    }

    fun signOut(accountId: String) {
        if (_state.value.signingOutAccountId != null) {
            return
        }

        val account = _state.value.accounts.firstOrNull { item -> item.accountId == accountId } ?: return
        val scope = SessionRemovalScope.Account(accountIdentity(account))
        val previewUseCase = getDownloadRemovalPreviewUseCase
        val issueAuthorization = issueDownloadRemovalAuthorizationUseCase
        removalScope = scope
        viewModelScope.launch {
            _state.update { state ->
                state.copy(
                    signingOutAccountId = accountId,
                    isLoadingRemovalPreview = previewUseCase != null,
                    removalPreview = null,
                    removalPreviewAccountId = null,
                    error = null,
                )
            }
            if (previewUseCase == null || issueAuthorization == null) {
                signOutWithAuthorization(accountId, SessionRemovalAuthorization.None)
                return@launch
            }
            val preview = loadRemovalPreview(scope)
            _state.update { state -> state.copy(isLoadingRemovalPreview = false) }
            if (preview == null) {
                finishSignOut(accountId, AccountUiError.RemovalFailed)
            } else if (preview.confirmations.any { confirmation -> confirmation.recordCount > 0L }) {
                _state.update { state ->
                    state.copy(
                        removalPreview = preview,
                        removalPreviewAccountId = accountId,
                    )
                }
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

    fun confirmPendingRemoval() {
        val accountId = _state.value.removalPreviewAccountId ?: return
        val issueAuthorization = issueDownloadRemovalAuthorizationUseCase ?: return
        val preview = _state.value.removalPreview ?: return
        val scope = removalScope ?: return
        if (_state.value.signingOutAccountId == null) return
        viewModelScope.launch {
            _state.update { state -> state.copy(removalPreview = null, removalPreviewAccountId = null) }
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

    fun dismissPendingRemoval() {
        val preview = _state.value.removalPreview
        val scope = removalScope
        val release = releaseDownloadRemovalPreviewUseCase
        if (preview == null || scope == null || release == null) {
            removalScope = null
            _state.update { state ->
                state.copy(
                    signingOutAccountId = null,
                    isLoadingRemovalPreview = false,
                    removalPreview = null,
                    removalPreviewAccountId = null,
                )
            }
            return
        }

        // Start synchronously so the bounded non-cancellable release captures the only scope and
        // preview handles before ViewModel cancellation can run. The logical guard release must
        // finish; a host wake failure/cancellation is best effort and never keeps the dialog open.
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
                removalScope = null
                _state.update { state ->
                    state.copy(
                        signingOutAccountId = null,
                        isLoadingRemovalPreview = false,
                        removalPreview = null,
                        removalPreviewAccountId = null,
                    )
                }
            }
        }
    }

    private suspend fun signOutWithAuthorization(
        accountId: String,
        authorization: SessionRemovalAuthorization,
    ) {
        val result: Result<Unit> =
            try {
                withContext(workDispatcher) { signOutAccountAction(accountId, authorization) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Result.failure(throwable)
            }
        _state.update { state ->
            state.copy(
                signingOutAccountId = null,
                isLoadingRemovalPreview = false,
                removalPreview = null,
                removalPreviewAccountId = null,
                error = result.exceptionOrNull()?.toUiError(),
            )
        }
        if (result.exceptionOrNull() is SessionRemovalError.ConfirmationStale) {
            showFreshRemovalPreview(accountId, removalScope ?: return)
        } else if (result.isSuccess) {
            removalScope = null
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
        _state.update { state ->
            state.copy(
                signingOutAccountId = accountId,
                isLoadingRemovalPreview = false,
                removalPreview = preview,
                removalPreviewAccountId = accountId,
                error = null,
            )
        }
    }

    private fun finishSignOut(
        accountId: String,
        error: AccountUiError,
    ) {
        _state.update { state ->
            state.copy(
                signingOutAccountId = null,
                isLoadingRemovalPreview = false,
                removalPreview = null,
                removalPreviewAccountId = null,
                error = error,
            )
        }
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
