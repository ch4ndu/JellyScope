// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

data class TvAccountSummary(
    val id: String,
    val serverName: String,
    val serverUrl: String,
    val userName: String,
    val isActive: Boolean,
)

data class TvAccountRemovalConfirmation(
    val accountId: String,
    val serverName: String,
    val userName: String,
    val downloadCount: Long,
    val displayedBytes: Long,
    val refreshedAfterStale: Boolean = false,
)

data class TvAccountsState(
    val accounts: List<TvAccountSummary> = emptyList(),
    val activeAccountId: String? = null,
    val operationInFlight: Boolean = false,
    val operationAccountId: String? = null,
    val isLoadingRemovalPreview: Boolean = false,
    val removalConfirmation: TvAccountRemovalConfirmation? = null,
    val error: TvAccountError? = null,
)

enum class TvAccountError {
    AccountNotFound,
    RemovalConfirmationRequired,
    RemovalConfirmationStale,
    ArtifactInUse,
    Server,
}
