// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

sealed interface TvLibrariesIntent {
    data object Load : TvLibrariesIntent
}
