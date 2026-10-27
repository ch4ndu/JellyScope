// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.ImageQuality
import com.jellyscope.core.domain.model.TileSizeId

internal sealed interface TvAppearanceChange {
    data class ThemeObserved(
        val appTheme: AppColorThemeId,
    ) : TvAppearanceChange

    data class TileObserved(
        val tileSize: TileSizeId,
    ) : TvAppearanceChange

    data class ImageObserved(
        val imageQuality: ImageQuality,
    ) : TvAppearanceChange

    data object ThemeStarted : TvAppearanceChange

    data object TileStarted : TvAppearanceChange

    data object ImageStarted : TvAppearanceChange

    data class ThemeSaved(
        val value: AppColorThemeId,
    ) : TvAppearanceChange

    data class ThemeFailed(
        val value: AppColorThemeId,
    ) : TvAppearanceChange

    data class TileSaved(
        val value: TileSizeId,
    ) : TvAppearanceChange

    data class TileFailed(
        val value: TileSizeId,
    ) : TvAppearanceChange

    data class ImageSaved(
        val value: ImageQuality,
    ) : TvAppearanceChange

    data class ImageFailed(
        val value: ImageQuality,
    ) : TvAppearanceChange
}

internal fun reduceTvAppearance(
    current: TvAppearanceState,
    change: TvAppearanceChange,
): TvAppearanceState =
    when (change) {
        is TvAppearanceChange.ThemeObserved ->
            with(change) {
                current.copy(appTheme = appTheme)
            }
        is TvAppearanceChange.TileObserved ->
            with(change) {
                current.copy(tileSize = tileSize)
            }
        is TvAppearanceChange.ImageObserved ->
            with(change) {
                current.copy(imageQuality = imageQuality)
            }
        TvAppearanceChange.ThemeStarted ->
            run {
                current.copy(
                    isSavingAppTheme = true,
                    appThemeSaveError = false,
                )
            }
        TvAppearanceChange.TileStarted ->
            run {
                current.copy(
                    isSavingTileSize = true,
                    tileSizeSaveError = false,
                )
            }
        TvAppearanceChange.ImageStarted ->
            run {
                current.copy(
                    isSavingImageQuality = true,
                    imageQualitySaveError = false,
                )
            }
        is TvAppearanceChange.ThemeSaved ->
            with(change) {
                current.copy(
                    appTheme = value,
                    isSavingAppTheme = false,
                    appThemeSaveError = false,
                )
            }
        is TvAppearanceChange.ThemeFailed ->
            with(change) {
                current.copy(
                    appTheme = value,
                    isSavingAppTheme = false,
                    appThemeSaveError = true,
                )
            }
        is TvAppearanceChange.TileSaved ->
            with(change) {
                current.copy(
                    tileSize = value,
                    isSavingTileSize = false,
                    tileSizeSaveError = false,
                )
            }
        is TvAppearanceChange.TileFailed ->
            with(change) {
                current.copy(
                    tileSize = value,
                    isSavingTileSize = false,
                    tileSizeSaveError = true,
                )
            }
        is TvAppearanceChange.ImageSaved ->
            with(change) {
                current.copy(
                    imageQuality = value,
                    isSavingImageQuality = false,
                    imageQualitySaveError = false,
                )
            }
        is TvAppearanceChange.ImageFailed ->
            with(change) {
                current.copy(
                    imageQuality = value,
                    isSavingImageQuality = false,
                    imageQualitySaveError = true,
                )
            }
    }
