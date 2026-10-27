// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

sealed interface TvHomeIntent {
    data object Load : TvHomeIntent

    data class Retry(
        val row: TvHomeRowKind,
    ) : TvHomeIntent
}
