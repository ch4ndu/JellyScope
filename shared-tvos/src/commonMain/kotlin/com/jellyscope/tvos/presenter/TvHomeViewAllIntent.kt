// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

sealed interface TvHomeViewAllIntent {
    data object Load : TvHomeViewAllIntent

    data object Retry : TvHomeViewAllIntent

    data object Refresh : TvHomeViewAllIntent
}
