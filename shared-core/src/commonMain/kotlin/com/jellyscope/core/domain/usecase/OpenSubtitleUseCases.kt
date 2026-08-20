// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.data.repository.OpenSubtitlesRepository
import com.jellyscope.core.domain.model.OpenSubtitleSearchRequest
import com.jellyscope.core.domain.model.OpenSubtitleSearchResult

class SearchOpenSubtitlesUseCase(
    private val repository: OpenSubtitlesRepository,
) {
    suspend operator fun invoke(request: OpenSubtitleSearchRequest): List<OpenSubtitleSearchResult> = repository.search(request)
}

class DownloadOpenSubtitleUseCase(
    private val repository: OpenSubtitlesRepository,
) {
    suspend operator fun invoke(fileId: String) = repository.download(fileId)
}
