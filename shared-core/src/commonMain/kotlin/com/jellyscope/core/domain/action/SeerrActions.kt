// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.data.repository.SeerrRepository
import com.jellyscope.core.domain.model.SeerrConnection
import com.jellyscope.core.domain.model.SeerrDetail
import com.jellyscope.core.domain.model.SeerrLoginMethod
import com.jellyscope.core.domain.model.SeerrOwner
import com.jellyscope.core.domain.model.SeerrPublicationToken
import com.jellyscope.core.domain.model.SeerrQuickConnect
import com.jellyscope.core.domain.model.SeerrRequestCreated
import com.jellyscope.core.domain.model.SeerrRequestIntent
import com.jellyscope.core.domain.model.SeerrResult
import com.jellyscope.core.domain.model.SeerrServiceInfo

class ValidateSeerrServiceAction(
    private val repository: SeerrRepository,
) {
    suspend operator fun invoke(
        owner: SeerrOwner,
        url: String,
    ): SeerrResult<SeerrServiceInfo> = repository.validateService(owner, url)
}

class RestoreSeerrConnectionAction(
    private val repository: SeerrRepository,
) {
    suspend operator fun invoke(owner: SeerrOwner): SeerrResult<SeerrConnection> = repository.restore(owner)
}

class ConnectSeerrAction(
    private val repository: SeerrRepository,
) {
    suspend operator fun invoke(
        token: SeerrPublicationToken,
        method: SeerrLoginMethod,
        identifier: String,
        password: String,
    ): SeerrResult<SeerrConnection> = repository.connect(token, method, identifier, password)
}

class BeginSeerrQuickConnectAction(
    private val repository: SeerrRepository,
) {
    suspend operator fun invoke(token: SeerrPublicationToken): SeerrResult<SeerrQuickConnect> = repository.beginQuickConnect(token)
}

class CompleteSeerrQuickConnectAction(
    private val repository: SeerrRepository,
) {
    /** Suspends through bounded polling. Cancel its caller job when the auth owner is disposed. */
    suspend operator fun invoke(token: SeerrPublicationToken): SeerrResult<SeerrConnection> = repository.completeQuickConnect(token)
}

class CancelSeerrAuthenticationAction(
    private val repository: SeerrRepository,
) {
    suspend operator fun invoke(token: SeerrPublicationToken): SeerrResult<SeerrConnection> = repository.cancelAuthentication(token)
}

class DisconnectSeerrAction(
    private val repository: SeerrRepository,
) {
    suspend operator fun invoke(owner: SeerrOwner): SeerrResult<SeerrConnection> = repository.disconnect(owner)
}

class RequestSeerrMediaAction(
    private val repository: SeerrRepository,
) {
    suspend operator fun invoke(
        token: SeerrPublicationToken,
        intent: SeerrRequestIntent,
    ): SeerrResult<SeerrRequestCreated> = repository.request(token, intent)
}

class ReconcileSeerrRequestAction(
    private val repository: SeerrRepository,
) {
    suspend operator fun invoke(token: SeerrPublicationToken): SeerrResult<SeerrDetail> = repository.reconcileRequest(token)
}
