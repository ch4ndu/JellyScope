// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.ImageQuality
import com.jellyscope.core.domain.model.TileSizeId

sealed interface TvAppearanceIntent {
    data class SetAppTheme(
        val appTheme: AppColorThemeId,
    ) : TvAppearanceIntent

    data class SetTileSize(
        val tileSize: TileSizeId,
    ) : TvAppearanceIntent

    data class SetImageQuality(
        val imageQuality: ImageQuality,
    ) : TvAppearanceIntent

    data object RetryAppTheme : TvAppearanceIntent

    data object RetryTileSize : TvAppearanceIntent

    data object RetryImageQuality : TvAppearanceIntent
}
