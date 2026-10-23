// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.data.repository.SessionRepository

class ObserveSessionStateUseCase(
    private val sessionRepository: SessionRepository,
) {
    operator fun invoke() = sessionRepository.sessionState
}

class ObserveAccountsUseCase(
    private val sessionRepository: SessionRepository,
) {
    operator fun invoke() = sessionRepository.accounts
}
