// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.LibraryInnerView
import com.jellyscope.core.domain.model.LibraryRecommendationSection

sealed interface TvLibraryHubIntent {
    data class SelectView(
        val view: LibraryInnerView,
    ) : TvLibraryHubIntent

    data class RetryRecommendation(
        val section: LibraryRecommendationSection,
    ) : TvLibraryHubIntent

    data object RefreshRecommendedSilently : TvLibraryHubIntent
}
