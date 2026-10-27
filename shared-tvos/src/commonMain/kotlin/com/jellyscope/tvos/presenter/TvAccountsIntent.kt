// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

sealed interface TvAccountsIntent {
    data class SwitchAccount(
        val accountId: String,
    ) : TvAccountsIntent

    data class SignOutAccount(
        val accountId: String,
    ) : TvAccountsIntent

    data object ConfirmPendingRemoval : TvAccountsIntent

    data object DismissPendingRemoval : TvAccountsIntent
}
