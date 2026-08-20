// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.jellyscope.ui.screen.serverentry.DiscoveredServerUi
import com.jellyscope.ui.theme.AmbientLaunchDimens
import com.jellyscope.ui.theme.AmbientLaunchTokens
import com.jellyscope.ui.component.AdaptiveSpinner as TvSpinner
import com.jellyscope.ui.component.FocusableBox as TvFocusableBox

internal val AmbientTvInputFieldColors =
    TvInputFieldColors(
        container = AmbientLaunchTokens.glassFill,
        unfocusedBorder = AmbientLaunchTokens.glassBorder,
        focusedBorder = AmbientLaunchTokens.accent,
        text = AmbientLaunchTokens.textPrimary,
        placeholder = AmbientLaunchTokens.textSecondary,
        label = AmbientLaunchTokens.textSecondary,
        focusedLabel = AmbientLaunchTokens.accent,
        caption = AmbientLaunchTokens.textSecondary,
    )

internal val AmbientTvBodyTextStyle = TextStyle(fontSize = TvDimens.launchBodyTextSize)

/** Faint cyan focus glow shared by every focusable on the launch/login screens. */
internal val AmbientTvFocusGlowColor = AmbientLaunchTokens.focusGlowSoft

@Composable
internal fun TvAmbientLaunchTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TvDimens.launchTitleUnderlineGap),
    ) {
        Text(
            text = text,
            color = AmbientLaunchTokens.textPrimary,
            style =
                TextStyle(
                    fontSize = TvDimens.launchHeadlineSize,
                    fontWeight = FontWeight.SemiBold,
                ),
        )
        Box(
            modifier =
                Modifier
                    .size(
                        width = TvDimens.launchUnderlineWidth,
                        height = TvDimens.launchUnderlineHeight,
                    ).background(AmbientLaunchTokens.accent),
        )
    }
}

@Composable
internal fun TvAmbientSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TvDimens.settingsValueGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                modifier = Modifier.size(TvDimens.launchSectionIconSize),
                tint = AmbientLaunchTokens.accent,
            )
        }
        Text(
            text = text,
            color = AmbientLaunchTokens.textPrimary,
            style =
                TextStyle(
                    fontSize = TvDimens.launchSectionTitleSize,
                    fontWeight = FontWeight.SemiBold,
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun TvAmbientGlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(TvDimens.panelRadius)

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(AmbientLaunchTokens.glassFill)
                .border(TvDimens.inputFieldUnfocusedBorder, AmbientLaunchTokens.glassBorder, shape)
                .padding(TvDimens.launchPanelPadding),
        verticalArrangement = Arrangement.spacedBy(TvDimens.formGap),
        content = content,
    )
}

@Composable
internal fun TvAmbientPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    contentDescription: String = text,
) {
    TvAmbientButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !loading,
        loading = loading,
        contentDescription = contentDescription,
        backgroundColor = AmbientLaunchTokens.glassFill,
        focusedBackgroundColor = AmbientLaunchTokens.glassFill,
        contentColor = AmbientLaunchTokens.textPrimary,
    )
}

@Composable
internal fun TvAmbientSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String = text,
) {
    TvAmbientButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        loading = false,
        contentDescription = contentDescription,
        backgroundColor = AmbientLaunchTokens.glassFill,
        focusedBackgroundColor = AmbientLaunchTokens.glassFill,
        contentColor = AmbientLaunchTokens.textPrimary,
    )
}

@Composable
internal fun TvAmbientDiscoveredServerCard(
    server: DiscoveredServerUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(TvDimens.panelRadius)

    TvFocusableBox(
        onClick = onClick,
        modifier = modifier,
        contentDescription = "${server.name} ${server.address}",
        focusedScale = TvDimens.LAUNCH_FOCUSED_SCALE,
        backgroundColor = AmbientLaunchTokens.cardFill,
        focusedBackgroundColor = AmbientLaunchTokens.cardFill,
        unfocusedBorderColor = AmbientLaunchTokens.glassBorder,
        focusedBorderColor = AmbientLaunchTokens.accent,
        focusGlowColor = AmbientTvFocusGlowColor,
        focusGlowElevation = TvDimens.launchFocusGlowElevation,
        contentPadding = PaddingValues(TvDimens.launchCardPadding),
        shape = shape,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvAmbientWifiIcon()
            Column(
                modifier = Modifier.wrapContentWidth(),
                verticalArrangement = Arrangement.spacedBy(TvDimens.settingsValueGap),
            ) {
                Text(
                    text = server.name,
                    color = AmbientLaunchTokens.textPrimary,
                    style = TextStyle(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = server.address,
                    color = AmbientLaunchTokens.textSecondary,
                    style = AmbientTvBodyTextStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(TvDimens.drawerIconSize),
                tint = AmbientLaunchTokens.textSecondary,
            )
        }
    }
}

@Composable
internal fun TvAmbientWifiIcon() {
    Canvas(modifier = Modifier.size(TvDimens.drawerIconSize)) {
        val stroke = Stroke(width = TvDimens.focusBorder.toPx(), cap = StrokeCap.Round)
        drawArc(
            color = AmbientLaunchTokens.accent,
            startAngle = 220f,
            sweepAngle = 100f,
            useCenter = false,
            style = stroke,
        )
        drawArc(
            color = AmbientLaunchTokens.accent,
            startAngle = 230f,
            sweepAngle = 80f,
            useCenter = false,
            style = stroke,
            topLeft = Offset(size.width / 4f, size.height / 4f),
            size = Size(size.width / 2f, size.height / 2f),
        )
        val dotRadius = TvDimens.focusBorder.toPx()
        drawCircle(
            color = AmbientLaunchTokens.accent,
            radius = dotRadius,
            center = Offset(size.width / 2f, size.height - dotRadius),
        )
    }
}

@Composable
private fun TvAmbientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    loading: Boolean,
    contentDescription: String,
    backgroundColor: Color,
    focusedBackgroundColor: Color,
    contentColor: Color,
) {
    // Rectangular buttons (modest radius) to match the fields and server cards.
    val shape = RoundedCornerShape(TvDimens.panelRadius)

    TvFocusableBox(
        onClick = onClick,
        modifier = modifier.heightIn(min = TvDimens.launchButtonMinHeight),
        enabled = enabled,
        contentDescription = contentDescription,
        focusedScale = TvDimens.LAUNCH_FOCUSED_SCALE,
        backgroundColor = backgroundColor.copy(alpha = if (enabled) 1f else AmbientLaunchDimens.disabledAlpha),
        focusedBackgroundColor = focusedBackgroundColor,
        unfocusedBorderColor = AmbientLaunchTokens.glassBorder,
        focusedBorderColor = AmbientLaunchTokens.accent,
        focusGlowColor = AmbientTvFocusGlowColor,
        focusGlowElevation = TvDimens.launchFocusGlowElevation,
        contentPadding =
            PaddingValues(
                horizontal = TvDimens.launchButtonHorizontalPadding,
                vertical = TvDimens.launchButtonVerticalPadding,
            ),
        contentAlignment = Alignment.Center,
        shape = shape,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(TvDimens.settingsValueGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                TvSpinner(
                    size = TvDimens.launchSpinnerSize,
                    color = contentColor,
                )
            }
            Text(
                text = text,
                color = contentColor.copy(alpha = if (enabled) 1f else AmbientLaunchDimens.disabledAlpha),
                style = TextStyle(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
