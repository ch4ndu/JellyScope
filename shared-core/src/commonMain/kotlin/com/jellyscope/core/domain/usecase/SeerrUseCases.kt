// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.data.repository.SeerrRepository
import com.jellyscope.core.domain.model.SeerrCatalog
import com.jellyscope.core.domain.model.SeerrCatalogPage
import com.jellyscope.core.domain.model.SeerrConnection
import com.jellyscope.core.domain.model.SeerrDetail
import com.jellyscope.core.domain.model.SeerrLibraryItem
import com.jellyscope.core.domain.model.SeerrMediaKey
import com.jellyscope.core.domain.model.SeerrOwner
import com.jellyscope.core.domain.model.SeerrPublicationToken
import com.jellyscope.core.domain.model.SeerrQuality
import com.jellyscope.core.domain.model.SeerrQuotas
import com.jellyscope.core.domain.model.SeerrRequestsPage
import com.jellyscope.core.domain.model.SeerrResult

class GetSeerrConnectionUseCase(
    private val repository: SeerrRepository,
) {
    suspend operator fun invoke(owner: SeerrOwner): SeerrResult<SeerrConnection> = repository.connection(owner)
}

class GetSeerrCatalogUseCase(
    private val repository: SeerrRepository,
) {
    suspend operator fun invoke(
        token: SeerrPublicationToken,
        catalog: SeerrCatalog,
        page: Int = 1,
        query: String = "",
    ): SeerrResult<SeerrCatalogPage> = repository.catalog(token, catalog, page, query)
}

class GetSeerrDetailUseCase(
    private val repository: SeerrRepository,
) {
    suspend operator fun invoke(
        token: SeerrPublicationToken,
        key: SeerrMediaKey,
    ): SeerrResult<SeerrDetail> = repository.detail(token, key)
}

class GetSeerrRequestsUseCase(
    private val repository: SeerrRepository,
) {
    suspend operator fun invoke(
        token: SeerrPublicationToken,
        skip: Int = 0,
    ): SeerrResult<SeerrRequestsPage> = repository.requests(token, skip)
}

class GetSeerrQuotaUseCase(
    private val repository: SeerrRepository,
) {
    suspend operator fun invoke(token: SeerrPublicationToken): SeerrResult<SeerrQuotas> = repository.quota(token)
}

class ResolveSeerrLibraryItemUseCase(
    private val repository: SeerrRepository,
) {
    suspend operator fun invoke(
        token: SeerrPublicationToken,
        key: SeerrMediaKey,
        quality: SeerrQuality,
    ): SeerrResult<SeerrLibraryItem> = repository.resolveLibraryItem(token, key, quality)
}

class ValidateSeerrPublicationUseCase(
    private val repository: SeerrRepository,
) {
    suspend operator fun invoke(
        token: SeerrPublicationToken,
        catalog: Boolean = true,
    ): Boolean = repository.isCurrent(token, catalog)

    /** Call on Main for presentation. The callback must not suspend or reenter a domain operation. */
    suspend fun publish(
        token: SeerrPublicationToken,
        catalog: Boolean = true,
        block: () -> Unit,
    ): Boolean = repository.publishIfCurrent(token, catalog, block)
}

class GetSeerrSimilarUseCase(
    private val repository: SeerrRepository,
) {
    suspend operator fun invoke(
        token: SeerrPublicationToken,
        key: SeerrMediaKey,
    ): SeerrResult<SeerrCatalogPage> = repository.similar(token, key)
}
