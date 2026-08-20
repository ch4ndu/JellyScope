// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.TileSizeId
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.settings_appearance
import com.jellyscope.ui.generated.resources.settings_picker_theme_subtitle
import com.jellyscope.ui.generated.resources.settings_picker_tile_size_subtitle
import com.jellyscope.ui.generated.resources.settings_remember_library_view
import com.jellyscope.ui.generated.resources.settings_theme
import com.jellyscope.ui.generated.resources.settings_theme_ember
import com.jellyscope.ui.generated.resources.settings_theme_midnight
import com.jellyscope.ui.generated.resources.settings_theme_ocean
import com.jellyscope.ui.generated.resources.settings_tile_size
import com.jellyscope.ui.generated.resources.settings_tile_size_large
import com.jellyscope.ui.generated.resources.settings_tile_size_medium
import com.jellyscope.ui.generated.resources.settings_tile_size_small
import com.jellyscope.ui.theme.Dimensions
import com.jellyscope.ui.theme.themeSwatchColors
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AppearanceSettingsSection(
    selectedTheme: AppColorThemeId,
    onSetAppTheme: (AppColorThemeId) -> Unit,
    selectedTileSize: TileSizeId,
    onSetTileSize: (TileSizeId) -> Unit,
    rememberLastLibraryView: Boolean,
    onRememberLastLibraryViewChange: (Boolean) -> Unit,
    openRow: SettingsRowId?,
    onOpenRow: (SettingsRowId) -> Unit,
    onDismiss: () -> Unit,
) {
    val themeOptions =
        AppColorThemeId.entries.map { theme ->
            SettingsPickerOption(
                value = theme,
                label = appThemeLabel(theme),
                leading = { ThemeSwatchRow(themeId = theme) },
            )
        }
    val tileSizeOptions =
        TileSizeId.entries.map { tileSize ->
            SettingsPickerOption(
                value = tileSize,
                label = tileSizeLabel(tileSize),
            )
        }

    SettingsSectionCard(
        title = stringResource(Res.string.settings_appearance),
        rows =
            listOf(
                {
                    SettingsRow(
                        icon = SettingsRowId.Theme.icon(),
                        iconRole = SettingsRowId.Theme.iconRole(),
                        title = stringResource(Res.string.settings_theme),
                        value = appThemeLabel(selectedTheme),
                        trailing = SettingsRowTrailing.Chevron,
                        onClick = { onOpenRow(SettingsRowId.Theme) },
                    )
                },
                {
                    SettingsRow(
                        icon = SettingsRowId.TileSize.icon(),
                        iconRole = SettingsRowId.TileSize.iconRole(),
                        title = stringResource(Res.string.settings_tile_size),
                        value = tileSizeLabel(selectedTileSize),
                        trailing = SettingsRowTrailing.Chevron,
                        onClick = { onOpenRow(SettingsRowId.TileSize) },
                    )
                },
                {
                    SettingsRow(
                        icon = SettingsRowId.RememberLastLibrary.icon(),
                        iconRole = SettingsRowId.RememberLastLibrary.iconRole(),
                        title = stringResource(Res.string.settings_remember_library_view),
                        value = settingsBooleanValue(rememberLastLibraryView),
                        trailing =
                            SettingsRowTrailing.Switch(
                                checked = rememberLastLibraryView,
                                onCheckedChange = onRememberLastLibraryViewChange,
                            ),
                    )
                },
            ),
    )

    if (openRow == SettingsRowId.Theme) {
        SettingsOptionPicker(
            title = stringResource(Res.string.settings_theme),
            subtitle = stringResource(Res.string.settings_picker_theme_subtitle),
            options = themeOptions,
            selectedValue = selectedTheme,
            onOptionSelected = onSetAppTheme,
            onDismiss = onDismiss,
        )
    }
    if (openRow == SettingsRowId.TileSize) {
        SettingsOptionPicker(
            title = stringResource(Res.string.settings_tile_size),
            subtitle = stringResource(Res.string.settings_picker_tile_size_subtitle),
            options = tileSizeOptions,
            selectedValue = selectedTileSize,
            onOptionSelected = onSetTileSize,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun appThemeLabel(theme: AppColorThemeId): String =
    when (theme) {
        AppColorThemeId.Ocean -> stringResource(Res.string.settings_theme_ocean)
        AppColorThemeId.Midnight -> stringResource(Res.string.settings_theme_midnight)
        AppColorThemeId.Ember -> stringResource(Res.string.settings_theme_ember)
    }

@Composable
private fun tileSizeLabel(tileSize: TileSizeId): String =
    when (tileSize) {
        TileSizeId.Small -> stringResource(Res.string.settings_tile_size_small)
        TileSizeId.Medium -> stringResource(Res.string.settings_tile_size_medium)
        TileSizeId.Large -> stringResource(Res.string.settings_tile_size_large)
    }

@Composable
private fun ThemeSwatchRow(
    themeId: AppColorThemeId,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.themeSwatchDotSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        themeSwatchColors(themeId).forEach { swatchColor ->
            ThemeSwatchDot(color = swatchColor)
        }
    }
}

@Composable
private fun ThemeSwatchDot(color: Color) {
    Box(
        modifier =
            Modifier
                .size(Dimensions.themeSwatchDotSize)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = Dimensions.themeSwatchDotBorder,
                    color = MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
    )
}
