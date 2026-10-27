// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

data class TvHomeViewAllState(
    val row: TvHomeRowKind,
    val status: TvHomeRowStatus = TvHomeRowStatus.Loading,
    val items: List<TvMediaCard> = emptyList(),
    val error: TvErrorKind? = null,
    val isRefreshing: Boolean = false,
)
