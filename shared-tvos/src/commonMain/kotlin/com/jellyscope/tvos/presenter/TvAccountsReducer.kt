// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

internal sealed interface TvAccountsChange {
    data class AccountsObserved(
        val summaries: List<TvAccountSummary>,
        val activeAccountId: String?,
    ) : TvAccountsChange

    data class RemovalStarted(
        val accountId: String,
    ) : TvAccountsChange

    data object RemovalConfirmed : TvAccountsChange

    data class RemovalDismissed(
        val previewMissing: Boolean,
    ) : TvAccountsChange

    data class PreviewLoaded(
        val confirmation: TvAccountRemovalConfirmation,
    ) : TvAccountsChange

    data class OperationStarted(
        val accountId: String,
    ) : TvAccountsChange

    data class OperationFinished(
        val error: TvAccountError?,
    ) : TvAccountsChange

    data class Failed(
        val error: TvAccountError,
        val keepOperation: Boolean,
    ) : TvAccountsChange
}

internal fun reduceTvAccounts(
    current: TvAccountsState,
    change: TvAccountsChange,
): TvAccountsState =
    when (change) {
        is TvAccountsChange.AccountsObserved ->
            with(change) {
                current.copy(
                    accounts = summaries,
                    activeAccountId = activeAccountId,
                )
            }
        is TvAccountsChange.RemovalStarted ->
            with(change) {
                current.copy(
                    operationInFlight = true,
                    operationAccountId = accountId,
                    isLoadingRemovalPreview = true,
                    removalConfirmation = null,
                    error = null,
                )
            }
        TvAccountsChange.RemovalConfirmed ->
            run {
                current.copy(isLoadingRemovalPreview = true, removalConfirmation = null, error = null)
            }
        is TvAccountsChange.RemovalDismissed ->
            with(change) {
                current.copy(removalConfirmation = null, isLoadingRemovalPreview = previewMissing)
            }
        is TvAccountsChange.PreviewLoaded ->
            with(change) {
                current.copy(
                    isLoadingRemovalPreview = false,
                    removalConfirmation =
                    confirmation,
                    error = null,
                )
            }
        is TvAccountsChange.OperationStarted ->
            with(change) {
                current.copy(
                    operationInFlight = true,
                    operationAccountId = accountId,
                    isLoadingRemovalPreview = false,
                    removalConfirmation = null,
                    error = null,
                )
            }
        is TvAccountsChange.OperationFinished ->
            with(change) {
                current.copy(
                    operationInFlight = false,
                    operationAccountId = null,
                    isLoadingRemovalPreview = false,
                    removalConfirmation = null,
                    error = error,
                )
            }
        is TvAccountsChange.Failed ->
            with(change) {
                current.copy(
                    operationInFlight = if (keepOperation) current.operationInFlight else false,
                    operationAccountId = if (keepOperation) current.operationAccountId else null,
                    isLoadingRemovalPreview = false,
                    removalConfirmation = null,
                    error = error,
                )
            }
    }
