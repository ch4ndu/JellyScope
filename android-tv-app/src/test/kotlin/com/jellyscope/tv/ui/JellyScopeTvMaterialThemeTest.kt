// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.ui.graphics.Color
import com.jellyscope.ui.theme.EmberJellyfinPalette
import com.jellyscope.ui.theme.JellyfinPalette
import com.jellyscope.ui.theme.MidnightJellyfinPalette
import com.jellyscope.ui.theme.OceanJellyfinPalette
import org.junit.Test
import kotlin.test.assertEquals

class JellyScopeTvMaterialThemeTest {
    @Test
    fun mapsEveryPaletteRoleForEveryAppTheme() {
        val palettes =
            listOf(
                OceanJellyfinPalette,
                MidnightJellyfinPalette,
                EmberJellyfinPalette,
            )

        palettes.forEach { palette ->
            assertPaletteMapping(palette)
        }
    }
}

private fun assertPaletteMapping(palette: JellyfinPalette) {
    val tv = palette.toTvMaterialColorScheme()

    assertEquals(palette.cyan, tv.primary)
    assertEquals(palette.onFocusedLight, tv.onPrimary)
    assertEquals(palette.cyanDeep, tv.primaryContainer)
    assertEquals(palette.textPrimary, tv.onPrimaryContainer)
    assertEquals(palette.cyanDeep, tv.inversePrimary)
    assertEquals(palette.accentCoral, tv.secondary)
    assertEquals(palette.onFocusedLight, tv.onSecondary)
    assertEquals(palette.surfaceRaised, tv.secondaryContainer)
    assertEquals(palette.textPrimary, tv.onSecondaryContainer)
    assertEquals(palette.accentAmber, tv.tertiary)
    assertEquals(palette.onFocusedLight, tv.onTertiary)
    assertEquals(palette.accentAmberDeep, tv.tertiaryContainer)
    assertEquals(palette.textPrimary, tv.onTertiaryContainer)
    assertEquals(palette.navy, tv.background)
    assertEquals(palette.textPrimary, tv.onBackground)
    assertEquals(palette.surfaceNavy, tv.surface)
    assertEquals(palette.textPrimary, tv.onSurface)
    assertEquals(palette.surfaceRaised, tv.surfaceVariant)
    assertEquals(palette.textSecondary, tv.onSurfaceVariant)
    assertEquals(palette.cyan, tv.surfaceTint)
    assertEquals(palette.textPrimary, tv.inverseSurface)
    assertEquals(palette.onFocusedLight, tv.inverseOnSurface)
    assertEquals(palette.error, tv.error)
    assertEquals(palette.onFocusedLight, tv.onError)
    assertEquals(palette.error, tv.errorContainer)
    assertEquals(palette.onFocusedLight, tv.onErrorContainer)
    assertEquals(palette.outline, tv.border)
    assertEquals(palette.outline, tv.borderVariant)
    assertEquals(Color.Black, tv.scrim)
}
