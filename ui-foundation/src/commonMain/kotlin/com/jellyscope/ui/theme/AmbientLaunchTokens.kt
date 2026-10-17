// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Fixed visual language for the unauthenticated Ambient launch flow.
 *
 * These values deliberately do not participate in [JellyScopeTheme] so a
 * selected Ocean, Midnight, or Ember palette cannot affect launch screens.
 */
object AmbientLaunchTokens {
    val groundTop = Color(0xFF12293D)
    val groundBottom = Color(0xFF081420)
    val accent = Color(0xFF22B8D4)
    val coral = Color(0xFFEF7A5A)
    val textPrimary = Color(0xFFF4F7FA)
    val textSecondary = Color(0xFF9FB3C2)

    // Neutral dark glass (no cyan tint) over the backdrop.
    val glassFill = Color(0xA60B0E12)

    // Opaque so the focus glow renders around the card, never tinting its surface.
    // Near-black to match the dark server cards in the mockup.
    val cardFill = Color(0xFF05080C)
    val glassBorder = Color(0x24FFFFFF)
    val codeBoxFill = Color(0x14FFFFFF)
    val focusGlow = accent

    // The faint glow paint drawn around focused controls (shared by mobile + TV).
    val focusGlowSoft = focusGlow.copy(alpha = 0.14f)
    val scrim = Color(0xFF000000)
    val error = Color(0xFFFF6B6B)
    val onAccent = textPrimary
}

/** Shared geometry for the reusable Ambient launch components. */
object AmbientLaunchDimens {
    val brandMarkCompact = 96.dp
    val brandMarkLandscape = 76.dp
    val brandMarkGap = 8.dp
    val brandMarkLandscapeGap = 16.dp
    val brandWordmarkSize = 28.sp
    val brandWordmarkLandscapeSize = 46.sp
    val brandSubtitleSize = 14.sp
    val panelRadius = 20.dp
    val panelBorder = 1.dp
    val panelPadding = 20.dp
    val panelGap = 16.dp
    val controlRadius = 14.dp
    val controlBorder = 1.dp
    val controlMinHeight = 52.dp
    val controlHorizontalPadding = 16.dp
    val controlVerticalPadding = 12.dp
    val controlGap = 8.dp
    val cardPadding = 14.dp
    val cardIconSize = 24.dp
    val cardChevronSize = 20.dp

    // Subtle focus glow spread (drawn via Modifier.chromeFocusGlow on every platform).
    val focusGlowElevation = 4.dp
    val buttonMinHeight = 52.dp
    val buttonHorizontalPadding = 20.dp
    val buttonVerticalPadding = 14.dp
    val buttonSpinnerSize = 20.dp
    val buttonSpinnerStroke = 2.dp

    // Alpha applied to a control's background/label while disabled or loading.
    val disabledAlpha = 0.5f
    val wideBrandUnderlineHeight = 2.dp
    val discoveredServerCardMinWidth = 256.dp
    val discoveredServerCardMaxWidth = 384.dp

    // Fixed height reserved for the discovered-servers region so scanning/results
    // toggles never resize the vertically-centered compact layout (anti-jump).
    val discoveredRegionHeight = 176.dp
    val expandedLoginMaxWidth = 860.dp
    val sectionTitleSize = 18.sp
    val bodyTextSize = 14.sp
    val quickConnectCodeSize = 32.sp
}
