// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.RuntimeBucket
import com.jellyscope.core.domain.model.WatchedFilter

sealed interface TvSearchIntent {
    data class SetQuery(
        val query: String,
    ) : TvSearchIntent

    data class Submit(
        val query: String,
    ) : TvSearchIntent

    data object Retry : TvSearchIntent

    data class ToggleGenre(
        val genre: TvSearchGenre,
    ) : TvSearchIntent

    data class SetRuntimeBucket(
        val bucket: RuntimeBucket,
    ) : TvSearchIntent

    data class SetWatchedFilter(
        val filter: WatchedFilter,
    ) : TvSearchIntent

    data class SelectPerson(
        val person: TvSearchPerson,
    ) : TvSearchIntent

    data object ClearPerson : TvSearchIntent

    data class SelectResultCategory(
        val category: TvSearchResultCategory,
    ) : TvSearchIntent

    data object ClearRecents : TvSearchIntent

    data object Refresh : TvSearchIntent
}
