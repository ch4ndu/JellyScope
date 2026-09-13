// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.data.repository.AuthRepository
import com.jellyscope.core.data.repository.SessionRepository
import com.jellyscope.core.domain.model.ServerInfo
import com.jellyscope.core.domain.model.Session

class ValidateServerAction(
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(input: String) = authRepository.validateServer(input)
}

class LoginAction(
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(
        serverUrl: String,
        username: String,
        password: String,
    ) = authRepository.login(
        serverUrl = serverUrl,
        username = username,
        password = password,
    )
}

class QuickConnectLoginAction(
    private val authRepository: AuthRepository,
) {
    operator fun invoke(serverInfo: ServerInfo) = authRepository.loginWithQuickConnect(serverInfo)
}

class AddAccountAction(
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(
        serverUrl: String,
        username: String,
        password: String,
    ) = authRepository.addAccount(
        serverUrl = serverUrl,
        username = username,
        password = password,
    )
}

class SwitchAccountAction(
    private val sessionRepository: SessionRepository,
) {
    suspend operator fun invoke(accountId: String) = sessionRepository.switchTo(accountId)
}

class SignOutAccountAction(
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(
        accountId: String,
        authorization: SessionRemovalAuthorization = SessionRemovalAuthorization.None,
    ) = authRepository.signOut(accountId, authorization)
}

class LogoutAction(
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(authorization: SessionRemovalAuthorization = SessionRemovalAuthorization.None) =
        authRepository.logout(authorization)
}

class RefreshParentalRatingAction(
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(
        expectedSession: Session,
        expectedBoundaryEpoch: Long,
    ): Result<Unit> = authRepository.refreshParentalRating(expectedSession, expectedBoundaryEpoch)
}
