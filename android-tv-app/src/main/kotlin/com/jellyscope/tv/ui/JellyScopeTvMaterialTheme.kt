// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.jellyscope.ui.theme.JellyfinPalette
import com.jellyscope.ui.theme.LocalJellyfinPalette

@Composable
internal fun JellyScopeTvMaterialTheme(content: @Composable () -> Unit) {
    val palette = LocalJellyfinPalette.current
    val tvColorScheme = remember(palette) { palette.toTvMaterialColorScheme() }

    MaterialTheme(
        colorScheme = tvColorScheme,
        content = content,
    )
}

internal fun JellyfinPalette.toTvMaterialColorScheme(): ColorScheme =
    darkColorScheme(
        primary = cyan,
        onPrimary = onFocusedLight,
        primaryContainer = cyanDeep,
        onPrimaryContainer = textPrimary,
        inversePrimary = cyanDeep,
        secondary = accentCoral,
        onSecondary = onFocusedLight,
        secondaryContainer = surfaceRaised,
        onSecondaryContainer = textPrimary,
        tertiary = accentAmber,
        onTertiary = onFocusedLight,
        tertiaryContainer = accentAmberDeep,
        onTertiaryContainer = textPrimary,
        background = navy,
        onBackground = textPrimary,
        surface = surfaceNavy,
        onSurface = textPrimary,
        surfaceVariant = surfaceRaised,
        onSurfaceVariant = textSecondary,
        surfaceTint = cyan,
        inverseSurface = textPrimary,
        inverseOnSurface = onFocusedLight,
        error = error,
        onError = onFocusedLight,
        errorContainer = error,
        onErrorContainer = onFocusedLight,
        border = outline,
        borderVariant = outline,
        scrim = Color.Black,
    )
