// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import kotlinx.coroutines.flow.StateFlow

fun interface ObserveTvFocusedCardZoomUseCase {
    operator fun invoke(): StateFlow<Boolean>
}

fun interface ObserveTvLibraryGridHeroUseCase {
    operator fun invoke(): StateFlow<Boolean>
}

fun interface SetTvFocusedCardZoomAction {
    suspend operator fun invoke(enabled: Boolean)
}

fun interface SetTvLibraryGridHeroAction {
    suspend operator fun invoke(enabled: Boolean)
}
