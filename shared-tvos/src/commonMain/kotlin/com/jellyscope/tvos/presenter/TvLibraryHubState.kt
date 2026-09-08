// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.LibraryInnerView
import com.jellyscope.core.domain.model.LibraryRecommendationReason
import com.jellyscope.core.domain.model.LibraryRecommendationSection

enum class TvLibraryRecommendationStatus {
    Loading,
    Error,
    Empty,
    Content,
}

data class TvLibraryRecommendationRow(
    val stableId: String,
    val section: LibraryRecommendationSection,
    val reason: LibraryRecommendationReason?,
    val baselineItemName: String?,
    val items: List<TvMediaCard>,
)

data class TvLibraryRecommendationSectionState(
    val stableId: String,
    val section: LibraryRecommendationSection,
    val status: TvLibraryRecommendationStatus = TvLibraryRecommendationStatus.Loading,
    val rows: List<TvLibraryRecommendationRow> = emptyList(),
)

data class TvLibraryHubState(
    val availableViews: List<LibraryInnerView>,
    val selectedView: LibraryInnerView,
    val recommendationSections: List<TvLibraryRecommendationSectionState>,
)
