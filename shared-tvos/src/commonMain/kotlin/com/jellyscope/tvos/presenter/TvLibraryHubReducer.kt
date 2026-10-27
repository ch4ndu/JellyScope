// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.LibraryInnerView
import com.jellyscope.core.domain.model.LibraryRecommendationSection

internal sealed interface TvLibraryHubChange {
    data class ViewSelected(
        val view: LibraryInnerView,
    ) : TvLibraryHubChange

    data class SectionChanged(
        val section: LibraryRecommendationSection,
        val next: TvLibraryRecommendationSectionState,
    ) : TvLibraryHubChange
}

internal fun reduceTvLibraryHub(
    current: TvLibraryHubState,
    change: TvLibraryHubChange,
): TvLibraryHubState =
    when (change) {
        is TvLibraryHubChange.ViewSelected ->
            with(change) {
                current.copy(selectedView = view)
            }
        is TvLibraryHubChange.SectionChanged ->
            with(change) {
                current.copy(
                    recommendationSections =
                        current.recommendationSections.map { state ->
                            if (state.section == section) next else state
                        },
                )
            }
    }
