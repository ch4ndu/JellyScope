// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.account

import com.jellyscope.core.domain.model.AccountSession
import com.jellyscope.core.domain.model.DownloadRemovalPreview

internal sealed interface AccountChange {
    data class AccountsObserved(
        val accounts: List<AccountSession>,
    ) : AccountChange

    data object AddStarted : AccountChange

    data class AddFinished(
        val error: AccountUiError?,
    ) : AccountChange

    data class SwitchStarted(
        val accountId: String,
    ) : AccountChange

    data class SwitchFinished(
        val error: AccountUiError?,
    ) : AccountChange

    data class RemovalStarted(
        val accountId: String,
        val hasPreviewReader: Boolean,
    ) : AccountChange

    data object PreviewFinished : AccountChange

    data class PreviewLoaded(
        val preview: DownloadRemovalPreview,
        val accountId: String,
    ) : AccountChange

    data object RemovalConfirmed : AccountChange

    data object RemovalDismissed : AccountChange

    data class RemovalFinished(
        val error: AccountUiError?,
    ) : AccountChange

    data class PreviewRefreshed(
        val accountId: String,
        val preview: DownloadRemovalPreview,
    ) : AccountChange
}

internal fun reduceAccount(
    current: AccountUiState,
    change: AccountChange,
): AccountUiState =
    when (change) {
        is AccountChange.AccountsObserved ->
            with(change) {
                current.copy(accounts = accounts)
            }
        AccountChange.AddStarted -> current.copy(isAddingAccount = true, error = null)
        is AccountChange.AddFinished ->
            with(change) {
                current.copy(
                    isAddingAccount = false,
                    error = error,
                )
            }
        is AccountChange.SwitchStarted ->
            with(change) {
                current.copy(switchingAccountId = accountId, error = null)
            }
        is AccountChange.SwitchFinished ->
            with(change) {
                current.copy(
                    switchingAccountId = null,
                    error = error,
                )
            }
        is AccountChange.RemovalStarted ->
            with(change) {
                current.copy(
                    signingOutAccountId = accountId,
                    isLoadingRemovalPreview = hasPreviewReader,
                    removalPreview = null,
                    removalPreviewAccountId = null,
                    error = null,
                )
            }
        AccountChange.PreviewFinished -> current.copy(isLoadingRemovalPreview = false)
        is AccountChange.PreviewLoaded ->
            with(change) {
                current.copy(
                    removalPreview = preview,
                    removalPreviewAccountId = accountId,
                )
            }
        AccountChange.RemovalConfirmed -> current.copy(removalPreview = null, removalPreviewAccountId = null)
        AccountChange.RemovalDismissed ->
            current.copy(
                signingOutAccountId = null,
                isLoadingRemovalPreview = false,
                removalPreview = null,
                removalPreviewAccountId = null,
            )
        is AccountChange.RemovalFinished ->
            with(change) {
                current.copy(
                    signingOutAccountId = null,
                    isLoadingRemovalPreview = false,
                    removalPreview = null,
                    removalPreviewAccountId = null,
                    error = error,
                )
            }
        is AccountChange.PreviewRefreshed ->
            with(change) {
                current.copy(
                    signingOutAccountId = accountId,
                    isLoadingRemovalPreview = false,
                    removalPreview = preview,
                    removalPreviewAccountId = accountId,
                    error = null,
                )
            }
    }
