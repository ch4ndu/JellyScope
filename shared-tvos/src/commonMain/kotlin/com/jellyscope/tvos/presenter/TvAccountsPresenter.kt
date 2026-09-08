// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.AuthError
import com.jellyscope.core.domain.action.SessionRemovalAuthorization
import com.jellyscope.core.domain.action.SessionRemovalError
import com.jellyscope.core.domain.action.SessionRemovalScope
import com.jellyscope.core.domain.action.SignOutAccountAction
import com.jellyscope.core.domain.action.SwitchAccountAction
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.AccountSession
import com.jellyscope.core.domain.model.DownloadRemovalPreview
import com.jellyscope.core.domain.usecase.DownloadRemovalAuthorizationIssuer
import com.jellyscope.core.domain.usecase.DownloadRemovalPreviewReader
import com.jellyscope.core.domain.usecase.DownloadRemovalPreviewReleaser
import com.jellyscope.core.domain.usecase.ObserveAccountsUseCase
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvAccountsPresenter(
    observeAccounts: ObserveAccountsUseCase,
    switchAccount: SwitchAccountAction,
    signOutAccount: SignOutAccountAction,
    dispatchers: TvosDispatchers,
    private val getDownloadRemovalPreview: DownloadRemovalPreviewReader? = null,
    private val issueDownloadRemovalAuthorization: DownloadRemovalAuthorizationIssuer? = null,
    private val releaseDownloadRemovalPreview: DownloadRemovalPreviewReleaser? = null,
) : TvPresenter(dispatchers) {
    private val switchAccountAction = switchAccount
    private val signOutAccountAction = signOutAccount
    private val _state = MutableStateFlow(TvAccountsState())
    val state: StateFlow<TvAccountsState> = _state.asStateFlow()

    private var accountsById: Map<String, AccountSession> = emptyMap()
    private var removalScope: SessionRemovalScope.Account? = null
    private var removalPreview: DownloadRemovalPreview? = null
    private var removalAccount: AccountSession? = null
    private var removalCancelRequested = false
    private var closed = false

    init {
        scope.launch {
            observeAccounts().collect { accounts ->
                val projection =
                    withContext(workDispatcher) {
                        val accountSnapshot = accounts.toList()
                        val summaries =
                            accountSnapshot.map { account ->
                                TvAccountSummary(
                                    id = account.accountId,
                                    serverName = account.serverName,
                                    serverUrl = account.serverUrl,
                                    userName = account.userName,
                                    isActive = account.isActive,
                                )
                            }
                        AccountProjection(
                            accountsById = accountSnapshot.associateBy(AccountSession::accountId),
                            summaries = summaries,
                            activeAccountId = summaries.firstOrNull(TvAccountSummary::isActive)?.id,
                        )
                    }
                accountsById = projection.accountsById
                _state.update { current ->
                    current.copy(
                        accounts = projection.summaries,
                        activeAccountId = projection.activeAccountId,
                    )
                }
            }
        }
    }

    fun watchState(onChange: (TvAccountsState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun switchAccount(accountId: String) {
        if (state.value.operationInFlight || state.value.activeAccountId == accountId) return
        runSimpleOperation(accountId, "switch-failed") { switchAccountAction(accountId) }
    }

    fun signOutAccount(accountId: String) {
        if (state.value.operationInFlight) return
        val account = accountsById[accountId] ?: return publishError(TvAccountError.AccountNotFound)
        val reader = getDownloadRemovalPreview
        val issuer = issueDownloadRemovalAuthorization
        val releaser = releaseDownloadRemovalPreview
        if (reader == null && issuer == null && releaser == null) {
            runSimpleOperation(accountId, "sign-out-failed", DiagnosticOperation.AuthSignOut) { signOutAccountAction(accountId) }
            return
        }
        if (reader == null || issuer == null || releaser == null) {
            publishError(TvAccountError.Server)
            return
        }

        val scopeSnapshot = SessionRemovalScope.Account(account.identity())
        removalScope = scopeSnapshot
        removalAccount = account
        removalPreview = null
        removalCancelRequested = false
        _state.update { current ->
            current.copy(
                operationInFlight = true,
                operationAccountId = accountId,
                isLoadingRemovalPreview = true,
                removalConfirmation = null,
                error = null,
            )
        }
        scope.launch {
            val preview = readPreview(scopeSnapshot, reader, releaser)
            if (preview == null) {
                val wasCancelled = closed || removalCancelRequested || removalScope != scopeSnapshot
                finishRemoval(error = if (wasCancelled) null else TvAccountError.Server)
                return@launch
            }
            if (closed || removalCancelRequested || removalScope != scopeSnapshot) {
                releasePreview(scopeSnapshot, preview, releaser)
                finishRemoval()
                return@launch
            }
            removalPreview = preview
            continueWithPreview(account, scopeSnapshot, preview, refreshedAfterStale = false)
        }
    }

    fun confirmPendingRemoval() {
        val account = removalAccount ?: return
        val scopeSnapshot = removalScope ?: return
        val preview = removalPreview ?: return
        val issuer = issueDownloadRemovalAuthorization ?: return
        if (state.value.removalConfirmation?.accountId != account.accountId) return
        _state.update { current ->
            current.copy(isLoadingRemovalPreview = true, removalConfirmation = null, error = null)
        }
        scope.launch {
            if (!ownsPreview(scopeSnapshot, preview)) return@launch
            val authorization = issueAuthorization(scopeSnapshot, preview, issuer)
            if (!ownsPreview(scopeSnapshot, preview)) return@launch
            when {
                authorization != null -> signOutAuthorized(account.accountId, scopeSnapshot, preview, authorization)
                state.value.error == TvAccountError.RemovalConfirmationStale -> refreshStalePreview(account, scopeSnapshot, preview)
                else ->
                    finishRemovalReleasing(
                        scopeSnapshot = scopeSnapshot,
                        preview = preview,
                        error = state.value.error ?: TvAccountError.Server,
                    )
            }
        }
    }

    fun dismissPendingRemoval() {
        val scopeSnapshot = removalScope
        val preview = removalPreview
        val releaser = releaseDownloadRemovalPreview
        removalCancelRequested = true
        _state.update { current -> current.copy(removalConfirmation = null, isLoadingRemovalPreview = preview == null) }
        if (scopeSnapshot == null || preview == null || releaser == null) return
        removalPreview = null
        scope.launch {
            releasePreview(scopeSnapshot, preview, releaser)
            finishRemoval()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        removalCancelRequested = true
        val scopeSnapshot = removalScope
        val preview = removalPreview
        val releaser = releaseDownloadRemovalPreview
        removalPreview = null
        if (scopeSnapshot != null && preview != null && releaser != null) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                releasePreview(scopeSnapshot, preview, releaser)
            }
        }
        super.close()
    }

    private suspend fun continueWithPreview(
        account: AccountSession,
        scopeSnapshot: SessionRemovalScope.Account,
        preview: DownloadRemovalPreview,
        refreshedAfterStale: Boolean,
    ) {
        val confirmation = preview.confirmations.firstOrNull { item -> item.accountIdentity == scopeSnapshot.accountIdentity }
        if (confirmation == null) {
            finishRemovalReleasing(scopeSnapshot, preview, TvAccountError.Server)
            return
        }
        _state.update { current ->
            current.copy(
                isLoadingRemovalPreview = false,
                removalConfirmation =
                    TvAccountRemovalConfirmation(
                        accountId = account.accountId,
                        serverName = account.serverName,
                        userName = account.userName,
                        downloadCount = confirmation.recordCount,
                        displayedBytes = confirmation.displayedBytes,
                        refreshedAfterStale = refreshedAfterStale,
                    ),
                error = null,
            )
        }
    }

    private suspend fun refreshStalePreview(
        account: AccountSession,
        scopeSnapshot: SessionRemovalScope.Account,
        stalePreview: DownloadRemovalPreview?,
    ) {
        val releaser = releaseDownloadRemovalPreview ?: return finishRemoval(error = TvAccountError.Server)
        if (stalePreview != null) {
            if (removalPreview === stalePreview) removalPreview = null
            releasePreview(scopeSnapshot, stalePreview, releaser)
        }
        if (closed || removalCancelRequested || removalScope != scopeSnapshot) {
            finishRemoval()
            return
        }
        val reader = getDownloadRemovalPreview ?: return finishRemoval(error = TvAccountError.Server)
        val freshPreview = readPreview(scopeSnapshot, reader, releaser)
        if (freshPreview == null) {
            finishRemoval(error = TvAccountError.Server)
            return
        }
        if (closed || removalCancelRequested || removalScope != scopeSnapshot) {
            releaseDownloadRemovalPreview?.let { releaser -> releasePreview(scopeSnapshot, freshPreview, releaser) }
            finishRemoval()
            return
        }
        removalPreview = freshPreview
        continueWithPreview(account, scopeSnapshot, freshPreview, refreshedAfterStale = true)
    }

    private suspend fun signOutAuthorized(
        accountId: String,
        scopeSnapshot: SessionRemovalScope.Account,
        preview: DownloadRemovalPreview,
        authorization: SessionRemovalAuthorization,
    ) {
        if (!ownsPreview(scopeSnapshot, preview)) return
        removalPreview = null
        var actionStarted = false
        val result =
            try {
                withContext(workDispatcher) {
                    actionStarted = true
                    signOutAccountAction(accountId, authorization)
                }
            } catch (error: CancellationException) {
                if (!actionStarted) {
                    releaseDownloadRemovalPreview?.let { releaser -> releasePreview(scopeSnapshot, preview, releaser) }
                }
                throw error
            } catch (error: Throwable) {
                if (!actionStarted) {
                    releaseDownloadRemovalPreview?.let { releaser -> releasePreview(scopeSnapshot, preview, releaser) }
                }
                Result.failure(error)
            }
        if (closed || removalCancelRequested || removalScope != scopeSnapshot) {
            finishRemoval()
            return
        }
        val failure = result.exceptionOrNull()
        when (failure) {
            SessionRemovalError.ConfirmationStale -> {
                publishError(TvAccountError.RemovalConfirmationStale, keepOperation = true)
                val account = removalAccount
                if (account == null) {
                    finishRemoval(error = TvAccountError.Server)
                } else {
                    refreshStalePreview(account, scopeSnapshot, stalePreview = null)
                }
            }
            SessionRemovalError.ArtifactInUse -> finishRemoval(error = TvAccountError.ArtifactInUse)
            null -> finishRemoval()
            else -> {
                logAccountFailure("sign-out-failed", DiagnosticOperation.AuthSignOut, failure)
                finishRemoval(error = failure.toTvAccountError())
            }
        }
    }

    private suspend fun readPreview(
        scopeSnapshot: SessionRemovalScope.Account,
        reader: DownloadRemovalPreviewReader,
        releaser: DownloadRemovalPreviewReleaser,
    ): DownloadRemovalPreview? {
        var acquiredPreview: DownloadRemovalPreview? = null
        var handedOff = false
        return try {
            val preview =
                withContext(NonCancellable + workDispatcher) {
                    reader(scopeSnapshot).also { acquired -> acquiredPreview = acquired }
                }
            handedOff = true
            preview
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logAccountFailure("preview-failed", DiagnosticOperation.GetDownloadRemovalPreview, error)
            null
        } finally {
            if (!handedOff) {
                acquiredPreview?.let { preview -> releasePreview(scopeSnapshot, preview, releaser) }
            }
        }
    }

    private suspend fun issueAuthorization(
        scopeSnapshot: SessionRemovalScope.Account,
        preview: DownloadRemovalPreview,
        issuer: DownloadRemovalAuthorizationIssuer,
    ): SessionRemovalAuthorization? =
        try {
            withContext(workDispatcher) { issuer(scopeSnapshot, preview) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: SessionRemovalError.ConfirmationStale) {
            if (ownsPreview(scopeSnapshot, preview)) {
                publishError(TvAccountError.RemovalConfirmationStale, keepOperation = true)
            }
            null
        } catch (error: Throwable) {
            logAccountFailure("authorization-failed", DiagnosticOperation.AuthorizeDownloadRemoval, error)
            if (ownsPreview(scopeSnapshot, preview)) {
                publishError(TvAccountError.Server, keepOperation = true)
            }
            null
        }

    private suspend fun releasePreview(
        scopeSnapshot: SessionRemovalScope.Account,
        preview: DownloadRemovalPreview,
        releaser: DownloadRemovalPreviewReleaser,
    ) {
        try {
            withContext(NonCancellable + workDispatcher) { releaser(scopeSnapshot, preview) }
        } catch (_: Throwable) {
            Unit
        }
    }

    private suspend fun finishRemovalReleasing(
        scopeSnapshot: SessionRemovalScope.Account,
        preview: DownloadRemovalPreview,
        error: TvAccountError?,
    ) {
        if (removalPreview === preview) removalPreview = null
        releaseDownloadRemovalPreview?.let { releaser -> releasePreview(scopeSnapshot, preview, releaser) }
        finishRemoval(error)
    }

    private fun ownsPreview(
        scopeSnapshot: SessionRemovalScope.Account,
        preview: DownloadRemovalPreview,
    ): Boolean =
        !closed &&
            !removalCancelRequested &&
            removalScope == scopeSnapshot &&
            removalPreview === preview

    private fun runSimpleOperation(
        accountId: String,
        failureEvent: String,
        operationKind: DiagnosticOperation? = null,
        operation: suspend () -> Result<*>,
    ) {
        _state.update { current ->
            current.copy(
                operationInFlight = true,
                operationAccountId = accountId,
                isLoadingRemovalPreview = false,
                removalConfirmation = null,
                error = null,
            )
        }
        scope.launch {
            val result =
                try {
                    withContext(workDispatcher) { operation() }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Result.failure<Any?>(error)
                }
            val failure = result.exceptionOrNull()
            if (failure != null) logAccountFailure(failureEvent, operationKind, failure)
            finishRemoval(error = failure?.toTvAccountError())
        }
    }

    private fun finishRemoval(error: TvAccountError? = null) {
        removalScope = null
        removalPreview = null
        removalAccount = null
        removalCancelRequested = false
        _state.update { current ->
            current.copy(
                operationInFlight = false,
                operationAccountId = null,
                isLoadingRemovalPreview = false,
                removalConfirmation = null,
                error = error,
            )
        }
    }

    private fun publishError(
        error: TvAccountError,
        keepOperation: Boolean = false,
    ) {
        _state.update { current ->
            current.copy(
                operationInFlight = if (keepOperation) current.operationInFlight else false,
                operationAccountId = if (keepOperation) current.operationAccountId else null,
                isLoadingRemovalPreview = false,
                removalConfirmation = null,
                error = error,
            )
        }
    }
}

private data class AccountProjection(
    val accountsById: Map<String, AccountSession>,
    val summaries: List<TvAccountSummary>,
    val activeAccountId: String?,
)

private fun AccountSession.identity(): AccountIdentity = AccountIdentity(serverId, userId)

private fun Throwable.toTvAccountError(): TvAccountError =
    when (this) {
        AuthError.AccountNotFound -> TvAccountError.AccountNotFound
        SessionRemovalError.DownloadRemovalConfirmationRequired -> TvAccountError.RemovalConfirmationRequired
        SessionRemovalError.ConfirmationStale -> TvAccountError.RemovalConfirmationStale
        SessionRemovalError.ArtifactInUse -> TvAccountError.ArtifactInUse
        else -> TvAccountError.Server
    }

private fun logAccountFailure(
    stage: String,
    operation: DiagnosticOperation?,
    error: Throwable,
) {
    tvAccountsLogger.w {
        if (operation == null) {
            formatSafeFailureDiagnostic(stage = stage, event = "failed", throwable = error)
        } else {
            formatSafeFailureDiagnostic(
                stage = stage,
                event = "failed",
                operation = operation,
                throwable = error,
            )
        }
    }
}

private val tvAccountsLogger = diagnosticLogger(DiagnosticTag.TvSettingsPresenter)
