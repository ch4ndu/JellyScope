// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.find

import com.jellyscope.core.domain.model.RuntimeBucket
import com.jellyscope.core.domain.model.WatchedFilter

val findResultTabsInOrder: List<FindResultTab> = FindResultTab.entries.toList()

fun FindUiState.hasActiveFindUi(): Boolean =
    queryText.isNotBlank() ||
        selectedPerson != null ||
        selectedGenreNames.isNotEmpty() ||
        runtimeBucket != RuntimeBucket.Any ||
        watchedFilter != WatchedFilter.Any
