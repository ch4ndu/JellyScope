// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.ImageQuality
import com.jellyscope.core.domain.model.TileSizeId

data class TvAppearanceState(
    val appTheme: AppColorThemeId = AppColorThemeId.Ember,
    val imageQuality: ImageQuality = ImageQuality.High,
    val tileSize: TileSizeId = TileSizeId.Medium,
    val isSavingAppTheme: Boolean = false,
    val isSavingImageQuality: Boolean = false,
    val imageQualitySaveError: Boolean = false,
    val isSavingTileSize: Boolean = false,
    val appThemeSaveError: Boolean = false,
    val tileSizeSaveError: Boolean = false,
)
