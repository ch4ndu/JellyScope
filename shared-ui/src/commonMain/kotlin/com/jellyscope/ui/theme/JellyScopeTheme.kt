// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.jellyscope.core.domain.model.AppColorThemeId

/**
 * Palette extracted from the OTT case-study mockups (user-provided images):
 * deep navy background with a subtle vertical gradient, white text, and one
 * confident cyan accent used for CTAs, selection, ratings, and progress.
 * The app is dark-only with selectable dark palettes. Change colors here only.
 */
object JellyfinColors {
    val GradientTop = Color(0xFF12293D)
    val GradientBottom = Color(0xFF081420)
    val Cyan = Color(0xFF22B8D4)
    val CyanDeep = Color(0xFF0E7C95)
    val Navy = Color(0xFF0D1F30)
    val SurfaceNavy = Color(0xFF142C41)
    val SurfaceRaised = Color(0xFF1D3A52)
    val TextPrimary = Color(0xFFF4F7FA)
    val TextSecondary = Color(0xFF9FB3C2)
    val Outline = Color(0xFF2E4A63)
    val Error = Color(0xFFFF6B6B)
    val OnFocusedLight = Color(0xFF001B24)

    // Complementary warm accents (secondary/tertiary), tuned against the Cyan
    // primary. Used for things that must read clearly over a focused/cyan surface
    // — e.g. episode playback-progress bars.
    val AccentAmber = Color(0xFFF2A63B)
    val AccentAmberDeep = Color(0xFFC7791A)
    val AccentCoral = Color(0xFFEF7A5A)
}

private object EmberColors {
    val GradientTop = Color(0xFF17130E)
    val GradientBottom = Color(0xFF000000)

    // Softer, warmer amber than the previous neon orange (0xFFFF7A18), which
    // read as too high-contrast against the near-black background.
    val Orange = Color(0xFFE9A44B)
    val OrangeDeep = Color(0xFFBE7C2A)
    val Navy = Color(0xFF0A0A0A)
    val SurfaceNavy = Color(0xFF161616)
    val SurfaceRaised = Color(0xFF232323)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFB5B5B5)
    val Outline = Color(0xFF3A3A3A)
    val Error = Color(0xFFFF5A5A)
    val OnFocusedLight = Color(0xFF1A0F00)
    val AccentAmber = Color(0xFFFFB347)
    val AccentAmberDeep = Color(0xFFD66E00)
    val AccentCoral = Color(0xFFFF8A4C)
}

@Immutable
data class JellyfinPalette(
    val gradientTop: Color,
    val gradientBottom: Color,
    val cyan: Color,
    val cyanDeep: Color,
    val navy: Color,
    val surfaceNavy: Color,
    val surfaceRaised: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val outline: Color,
    val error: Color,
    val onFocusedLight: Color,
    val accentAmber: Color,
    val accentAmberDeep: Color,
    val accentCoral: Color,
)

val OceanJellyfinPalette =
    JellyfinPalette(
        gradientTop = JellyfinColors.GradientTop,
        gradientBottom = JellyfinColors.GradientBottom,
        cyan = JellyfinColors.Cyan,
        cyanDeep = JellyfinColors.CyanDeep,
        navy = JellyfinColors.Navy,
        surfaceNavy = JellyfinColors.SurfaceNavy,
        surfaceRaised = JellyfinColors.SurfaceRaised,
        textPrimary = JellyfinColors.TextPrimary,
        textSecondary = JellyfinColors.TextSecondary,
        outline = JellyfinColors.Outline,
        error = JellyfinColors.Error,
        onFocusedLight = JellyfinColors.OnFocusedLight,
        accentAmber = JellyfinColors.AccentAmber,
        accentAmberDeep = JellyfinColors.AccentAmberDeep,
        accentCoral = JellyfinColors.AccentCoral,
    )

val MidnightJellyfinPalette =
    JellyfinPalette(
        gradientTop = Color(0xFF181826),
        gradientBottom = Color(0xFF0A0A11),
        cyan = Color(0xFF9B8CFF),
        cyanDeep = Color(0xFF5646A0),
        navy = Color(0xFF0B0B10),
        surfaceNavy = Color(0xFF16161F),
        surfaceRaised = Color(0xFF262633),
        textPrimary = Color(0xFFECECF3),
        textSecondary = Color(0xFFABABBC),
        outline = Color(0xFF3A3A4C),
        error = Color(0xFFF2555A),
        onFocusedLight = Color(0xFF211447),
        accentAmber = JellyfinColors.AccentAmber,
        accentAmberDeep = JellyfinColors.AccentAmberDeep,
        accentCoral = JellyfinColors.AccentCoral,
    )

val EmberJellyfinPalette =
    JellyfinPalette(
        gradientTop = EmberColors.GradientTop,
        gradientBottom = EmberColors.GradientBottom,
        cyan = EmberColors.Orange,
        cyanDeep = EmberColors.OrangeDeep,
        navy = EmberColors.Navy,
        surfaceNavy = EmberColors.SurfaceNavy,
        surfaceRaised = EmberColors.SurfaceRaised,
        textPrimary = EmberColors.TextPrimary,
        textSecondary = EmberColors.TextSecondary,
        outline = EmberColors.Outline,
        error = EmberColors.Error,
        onFocusedLight = EmberColors.OnFocusedLight,
        accentAmber = EmberColors.AccentAmber,
        accentAmberDeep = EmberColors.AccentAmberDeep,
        accentCoral = EmberColors.AccentCoral,
    )

enum class AppColorTheme {
    Ocean,
    Midnight,
    Ember,
}

/** App-wide background gradient drawn by [SafeAreaContent]-style roots. */
val JellyfinBackgroundBrush: Brush =
    Brush.verticalGradient(
        colors = listOf(JellyfinColors.GradientTop, JellyfinColors.GradientBottom),
    )

private val MidnightBackgroundBrush: Brush =
    Brush.verticalGradient(
        colors = listOf(Color(0xFF181826), Color(0xFF0A0A11)),
    )

val EmberBackgroundBrush: Brush =
    Brush.verticalGradient(
        colors = listOf(EmberColors.GradientTop, EmberColors.GradientBottom),
    )

val LocalAppBackgroundBrush = staticCompositionLocalOf { EmberBackgroundBrush }
val LocalJellyfinPalette = staticCompositionLocalOf { EmberJellyfinPalette }

private val OceanColorScheme: ColorScheme =
    darkColorScheme(
        primary = JellyfinColors.Cyan,
        onPrimary = Color(0xFF00232E),
        primaryContainer = JellyfinColors.CyanDeep,
        onPrimaryContainer = Color(0xFFCFF3FA),
        secondary = JellyfinColors.AccentCoral,
        onSecondary = Color(0xFF3A0F00),
        secondaryContainer = JellyfinColors.SurfaceRaised,
        onSecondaryContainer = JellyfinColors.TextPrimary,
        tertiary = JellyfinColors.AccentAmber,
        onTertiary = Color(0xFF2E1C00),
        background = JellyfinColors.Navy,
        onBackground = JellyfinColors.TextPrimary,
        surface = JellyfinColors.SurfaceNavy,
        onSurface = JellyfinColors.TextPrimary,
        surfaceVariant = JellyfinColors.SurfaceRaised,
        onSurfaceVariant = JellyfinColors.TextSecondary,
        outline = JellyfinColors.Outline,
        error = JellyfinColors.Error,
        onError = Color(0xFF2B0606),
    )

private val MidnightColorScheme: ColorScheme =
    darkColorScheme(
        primary = Color(0xFF9B8CFF),
        onPrimary = Color(0xFF211447),
        primaryContainer = Color(0xFF5646A0),
        onPrimaryContainer = Color(0xFFE8E3FF),
        secondary = Color(0xFFEF7A5A),
        onSecondary = Color(0xFF3A0F00),
        tertiary = Color(0xFFF2A63B),
        onTertiary = Color(0xFF2E1C00),
        background = Color(0xFF0B0B10),
        onBackground = Color(0xFFECECF3),
        surface = Color(0xFF16161F),
        onSurface = Color(0xFFECECF3),
        surfaceVariant = Color(0xFF262633),
        onSurfaceVariant = Color(0xFFABABBC),
        outline = Color(0xFF3A3A4C),
        error = Color(0xFFF2555A),
        onError = Color(0xFF2B0606),
    )

private val EmberColorScheme: ColorScheme =
    darkColorScheme(
        primary = EmberColors.Orange,
        onPrimary = EmberColors.OnFocusedLight,
        primaryContainer = EmberColors.OrangeDeep,
        onPrimaryContainer = Color(0xFFFFE1CC),
        secondary = EmberColors.AccentCoral,
        onSecondary = Color(0xFF331000),
        secondaryContainer = EmberColors.SurfaceRaised,
        onSecondaryContainer = EmberColors.TextPrimary,
        tertiary = EmberColors.AccentAmber,
        onTertiary = Color(0xFF2B1700),
        background = EmberColors.Navy,
        onBackground = EmberColors.TextPrimary,
        surface = EmberColors.SurfaceNavy,
        onSurface = EmberColors.TextPrimary,
        surfaceVariant = EmberColors.SurfaceRaised,
        onSurfaceVariant = EmberColors.TextSecondary,
        outline = EmberColors.Outline,
        error = EmberColors.Error,
        onError = Color(0xFF330000),
    )

@Composable
fun JellyScopeTheme(
    theme: AppColorTheme = AppColorTheme.Ocean,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when (theme) {
            AppColorTheme.Ocean -> OceanColorScheme
            AppColorTheme.Midnight -> MidnightColorScheme
            AppColorTheme.Ember -> EmberColorScheme
        }
    val backgroundBrush =
        when (theme) {
            AppColorTheme.Ocean -> JellyfinBackgroundBrush
            AppColorTheme.Midnight -> MidnightBackgroundBrush
            AppColorTheme.Ember -> EmberBackgroundBrush
        }
    val palette =
        when (theme) {
            AppColorTheme.Ocean -> OceanJellyfinPalette
            AppColorTheme.Midnight -> MidnightJellyfinPalette
            AppColorTheme.Ember -> EmberJellyfinPalette
        }

    CompositionLocalProvider(
        LocalAppBackgroundBrush provides backgroundBrush,
        LocalJellyfinPalette provides palette,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}

fun AppColorThemeId.toAppColorTheme(): AppColorTheme =
    when (this) {
        AppColorThemeId.Ocean -> AppColorTheme.Ocean
        AppColorThemeId.Midnight -> AppColorTheme.Midnight
        AppColorThemeId.Ember -> AppColorTheme.Ember
    }

fun themeSwatchColors(themeId: AppColorThemeId): List<Color> =
    when (themeId) {
        AppColorThemeId.Ocean ->
            listOf(
                OceanJellyfinPalette.navy,
                OceanJellyfinPalette.cyan,
                OceanJellyfinPalette.accentCoral,
            )
        AppColorThemeId.Midnight ->
            listOf(
                MidnightJellyfinPalette.navy,
                MidnightJellyfinPalette.cyan,
                MidnightJellyfinPalette.accentCoral,
            )
        AppColorThemeId.Ember ->
            listOf(
                EmberJellyfinPalette.navy,
                EmberJellyfinPalette.cyan,
                EmberJellyfinPalette.textPrimary,
            )
    }
