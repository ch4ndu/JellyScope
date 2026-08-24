// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

data class ServerInfo(
    val serverUrl: String,
    val serverId: String,
    val serverName: String,
    val version: String,
    val productName: String,
)

data class Session(
    val serverUrl: String,
    val serverId: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    val accessToken: String,
    val deviceId: String,
    val enableContentDownloading: Boolean = false,
)

data class AccountSession(
    val accountId: String,
    val serverUrl: String,
    val serverId: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    val avatarUserId: String,
    val isActive: Boolean,
)

data class QuickConnectCode(
    val secret: String,
    val code: String,
)

sealed interface QuickConnectLoginUpdate {
    data class CodeAvailable(
        val code: QuickConnectCode,
    ) : QuickConnectLoginUpdate

    data class Polling(
        val code: QuickConnectCode,
    ) : QuickConnectLoginUpdate

    data class Success(
        val session: Session,
    ) : QuickConnectLoginUpdate
}

sealed interface SessionState {
    data object Restoring : SessionState

    data class LoggedOut(
        val serverUrl: String?,
    ) : SessionState

    data class LoggedIn(
        val session: Session,
        val boundaryEpoch: Long = 0L,
    ) : SessionState
}
