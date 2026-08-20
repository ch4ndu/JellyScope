// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.TileSizeId
import com.jellyscope.tv.R

@Composable
internal fun tvThemeLabel(theme: AppColorThemeId): String =
    when (theme) {
        AppColorThemeId.Ocean -> stringResource(R.string.tv_settings_theme_ocean)
        AppColorThemeId.Midnight -> stringResource(R.string.tv_settings_theme_midnight)
        AppColorThemeId.Ember -> stringResource(R.string.tv_settings_theme_ember)
    }

@Composable
internal fun tvTileSizeLabel(tileSize: TileSizeId): String =
    when (tileSize) {
        TileSizeId.Small -> stringResource(R.string.tv_settings_tile_size_small)
        TileSizeId.Medium -> stringResource(R.string.tv_settings_tile_size_medium)
        TileSizeId.Large -> stringResource(R.string.tv_settings_tile_size_large)
    }
