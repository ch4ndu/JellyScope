// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.library

import com.jellyscope.core.domain.model.LibraryInnerView
import com.jellyscope.core.domain.model.LibraryRecommendationSection

/** Internal state transitions; section jobs and preference writes remain ViewModel-owned. */
internal sealed interface LibraryHubChange {
    data class ViewSelected(
        val view: LibraryInnerView,
    ) : LibraryHubChange

    data class RecommendationStarted(
        val section: LibraryRecommendationSection,
        val keepContent: Boolean,
    ) : LibraryHubChange

    data class RecommendationLoaded(
        val section: LibraryRecommendationSection,
        val rows: List<LibraryRecommendationRowUi>,
    ) : LibraryHubChange

    data class RecommendationFailed(
        val section: LibraryRecommendationSection,
        val keepContent: Boolean,
    ) : LibraryHubChange
}

internal fun reduceLibraryHub(
    current: LibraryHubUiState,
    change: LibraryHubChange,
): LibraryHubUiState =
    when (change) {
        is LibraryHubChange.ViewSelected -> current.copy(selectedView = change.view)
        is LibraryHubChange.RecommendationStarted ->
            if (change.keepContent && current.recommendationSections[change.section] is LibraryRecommendationRowState.Content) {
                current
            } else {
                current.withSection(change.section, LibraryRecommendationRowState.Loading)
            }
        is LibraryHubChange.RecommendationLoaded ->
            current.withSection(
                change.section,
                if (change.rows.isEmpty()) LibraryRecommendationRowState.Empty else LibraryRecommendationRowState.Content(change.rows),
            )
        is LibraryHubChange.RecommendationFailed ->
            if (change.keepContent && current.recommendationSections[change.section] is LibraryRecommendationRowState.Content) {
                current
            } else {
                current.withSection(change.section, LibraryRecommendationRowState.Error)
            }
    }

private fun LibraryHubUiState.withSection(
    section: LibraryRecommendationSection,
    state: LibraryRecommendationRowState,
): LibraryHubUiState = copy(recommendationSections = recommendationSections + (section to state))
