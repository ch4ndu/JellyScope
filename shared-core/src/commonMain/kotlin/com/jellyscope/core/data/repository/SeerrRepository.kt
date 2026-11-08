// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.domain.model.SeerrCatalog
import com.jellyscope.core.domain.model.SeerrCatalogPage
import com.jellyscope.core.domain.model.SeerrConnection
import com.jellyscope.core.domain.model.SeerrDetail
import com.jellyscope.core.domain.model.SeerrLibraryItem
import com.jellyscope.core.domain.model.SeerrLoginMethod
import com.jellyscope.core.domain.model.SeerrMediaKey
import com.jellyscope.core.domain.model.SeerrOwner
import com.jellyscope.core.domain.model.SeerrPublicationToken
import com.jellyscope.core.domain.model.SeerrQuality
import com.jellyscope.core.domain.model.SeerrQuickConnect
import com.jellyscope.core.domain.model.SeerrQuotas
import com.jellyscope.core.domain.model.SeerrRequestCreated
import com.jellyscope.core.domain.model.SeerrRequestIntent
import com.jellyscope.core.domain.model.SeerrRequestsPage
import com.jellyscope.core.domain.model.SeerrResult
import com.jellyscope.core.domain.model.SeerrServiceInfo

interface SeerrRepository {
    suspend fun connection(owner: SeerrOwner): SeerrResult<SeerrConnection>

    suspend fun restore(owner: SeerrOwner): SeerrResult<SeerrConnection>

    suspend fun validateService(
        owner: SeerrOwner,
        url: String,
    ): SeerrResult<SeerrServiceInfo>

    suspend fun connect(
        token: SeerrPublicationToken,
        method: SeerrLoginMethod,
        identifier: String,
        password: String,
    ): SeerrResult<SeerrConnection>

    suspend fun beginQuickConnect(token: SeerrPublicationToken): SeerrResult<SeerrQuickConnect>

    suspend fun completeQuickConnect(token: SeerrPublicationToken): SeerrResult<SeerrConnection>

    suspend fun cancelAuthentication(token: SeerrPublicationToken): SeerrResult<SeerrConnection>

    suspend fun disconnect(owner: SeerrOwner): SeerrResult<SeerrConnection>

    suspend fun catalog(
        token: SeerrPublicationToken,
        catalog: SeerrCatalog,
        page: Int,
        query: String,
    ): SeerrResult<SeerrCatalogPage>

    suspend fun similar(
        token: SeerrPublicationToken,
        key: SeerrMediaKey,
    ): SeerrResult<SeerrCatalogPage>

    suspend fun detail(
        token: SeerrPublicationToken,
        key: SeerrMediaKey,
    ): SeerrResult<SeerrDetail>

    suspend fun requests(
        token: SeerrPublicationToken,
        skip: Int,
    ): SeerrResult<SeerrRequestsPage>

    suspend fun quota(token: SeerrPublicationToken): SeerrResult<SeerrQuotas>

    suspend fun request(
        token: SeerrPublicationToken,
        intent: SeerrRequestIntent,
    ): SeerrResult<SeerrRequestCreated>

    suspend fun reconcileRequest(token: SeerrPublicationToken): SeerrResult<SeerrDetail>

    suspend fun resolveLibraryItem(
        token: SeerrPublicationToken,
        key: SeerrMediaKey,
        quality: SeerrQuality,
    ): SeerrResult<SeerrLibraryItem>

    suspend fun isCurrent(
        token: SeerrPublicationToken,
        catalog: Boolean = true,
    ): Boolean

    /** Runs on the caller's dispatcher under the ownership gate. Must be quick and non-reentrant. */
    suspend fun publishIfCurrent(
        token: SeerrPublicationToken,
        catalog: Boolean = true,
        publish: () -> Unit,
    ): Boolean
}
