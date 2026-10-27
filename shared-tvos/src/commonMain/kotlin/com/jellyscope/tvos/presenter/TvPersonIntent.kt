// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

sealed interface TvPersonIntent {
    data object Load : TvPersonIntent

    data object RetryHeader : TvPersonIntent

    data object Retry : TvPersonIntent

    data class LoadMoreIfNeeded(
        val focusedIndex: Int,
    ) : TvPersonIntent
}
