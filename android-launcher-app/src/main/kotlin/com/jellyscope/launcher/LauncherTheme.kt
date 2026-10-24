// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

internal object LauncherStyle {
    val backgroundEnd = Color(0xFF20132C)
    val background = Brush.horizontalGradient(listOf(Color(0xFF28182F), Color(0xFFCA7059)))
    val backgroundDepth = Brush.verticalGradient(listOf(Color.Transparent, backgroundEnd.copy(alpha = 0.35f), backgroundEnd))
    val text = Color(0xFFF2F3F5)
    val muted = Color(0xFFB2B7C0)
    val headerIconTint = Color(0xFFF6F1FA)
    val tile = Color(0xFF3B2946)
    val focusOutline = Color(0xFFF0E5F6).copy(alpha = 0.8f)
    val clear = Color.Transparent
    val menu = Color(0xFFE8E9EB)
    val menuText = Color(0xFF1A1C20)
    val menuFocus = Color(0xFFCBCED3)
    val scrim = Color.Black.copy(alpha = 0.58f)
    val surface = Color(0xFF35213E)
    val inset = 48.dp
    val headerHeight = 68.dp
    val contentTop = 24.dp
    val headerIcon = 24.dp
    val headerControl = 40.dp
    val headerGap = 24.dp
    val tabUnderline = 1.dp
    val focusedUnderline = 2.dp
    const val TAB_DWELL_MILLIS = 250L
    val appWidth = 116.dp
    val appHeight = 65.dp
    val appGap = 8.dp
    val appIcon = 36.dp
    val appLabelHeight = 36.dp
    val appLabelGap = 8.dp
    val appRowPadding = 12.dp
    val radius = 6.dp
    val focusBorder = 1.dp
    val noBorder = 0.dp
    const val APP_FOCUS_SCALE = 1.06f
    const val MEDIA_FOCUS_SCALE = 1.1f
    const val FOCUS_MILLIS = 180
    val mediaLabelGap = 8.dp
    val mediaProgressHeight = 4.dp
    val menuWidth = 230.dp
    val menuRow = 48.dp
    val menuTitle = 40.dp
    val menuPadding = 8.dp
    val heading = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
    val section = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
    val header = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val label = TextStyle(fontSize = 14.sp, lineHeight = 18.sp)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
}

@Composable
internal fun LauncherTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides LauncherStyle.text) {
        MaterialTheme(
            colorScheme =
                darkColorScheme(
                    primary = LauncherStyle.text,
                    onPrimary = LauncherStyle.backgroundEnd,
                    background = LauncherStyle.backgroundEnd,
                    onBackground = LauncherStyle.text,
                    surface = LauncherStyle.surface,
                    onSurface = LauncherStyle.text,
                ),
            typography =
                Typography(
                    headlineMedium = LauncherStyle.heading,
                    headlineSmall = LauncherStyle.heading,
                    titleLarge = LauncherStyle.section,
                    bodyMedium = LauncherStyle.body,
                ),
            content = content,
        )
    }
}
