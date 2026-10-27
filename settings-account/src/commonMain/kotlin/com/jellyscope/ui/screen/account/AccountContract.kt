// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.account

import com.jellyscope.core.domain.model.AccountSession
import com.jellyscope.core.domain.model.DownloadRemovalPreview

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
