// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.library

import com.jellyscope.core.domain.model.LibraryInnerView
import com.jellyscope.core.domain.model.LibraryRecommendationSection
import com.jellyscope.ui.component.MediaCardUi

sealed interface LibraryRecommendationRowState {
    data object Loading : LibraryRecommendationRowState

    data object Error : LibraryRecommendationRowState

    data object Empty : LibraryRecommendationRowState

    data class Content(
        val rows: List<LibraryRecommendationRowUi>,
    ) : LibraryRecommendationRowState
}

data class LibraryRecommendationRowUi(
    val key: String,
    val section: LibraryRecommendationSection,
    val reason: com.jellyscope.core.domain.model.LibraryRecommendationReason?,
    val baselineItemName: String?,
    val items: List<MediaCardUi>,
)

data class LibraryHubUiState(
    val availableViews: List<LibraryInnerView>,
    val selectedView: LibraryInnerView,
    val recommendationSections: Map<LibraryRecommendationSection, LibraryRecommendationRowState>,
)

sealed interface LibraryHubIntent {
    data class SelectView(
        val view: LibraryInnerView,
    ) : LibraryHubIntent

    data class RetryRecommendation(
        val section: LibraryRecommendationSection,
    ) : LibraryHubIntent

    data object RefreshRecommended : LibraryHubIntent
}
