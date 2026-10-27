// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.account

sealed interface AccountIntent {
    class AddAccount(
        val serverUrl: String,
        val username: String,
        val password: String,
    ) : AccountIntent

    data class SwitchTo(
        val accountId: String,
    ) : AccountIntent

    data class SignOut(
        val accountId: String,
    ) : AccountIntent

    data object ConfirmPendingRemoval : AccountIntent

    data object DismissPendingRemoval : AccountIntent
}
