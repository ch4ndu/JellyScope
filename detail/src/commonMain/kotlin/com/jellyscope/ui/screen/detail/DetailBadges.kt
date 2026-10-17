// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jellyscope.ui.component.DetailSecondaryStyle
import com.jellyscope.ui.component.DetailText
import com.jellyscope.ui.theme.LocalAppBackgroundBrush
import com.jellyscope.ui.theme.LocalJellyfinPalette

@Composable
internal fun InfoChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(DetailDimens.detailBadgeRadius))
                .background(LocalJellyfinPalette.current.surfaceRaised)
                .border(
                    width = 1.dp,
                    color = LocalJellyfinPalette.current.outline,
                    shape = RoundedCornerShape(DetailDimens.detailBadgeRadius),
                ).padding(
                    horizontal = DetailDimens.detailBadgeHorizontalPadding,
                    vertical = DetailDimens.detailBadgeVerticalPadding,
                ),
    ) {
        DetailText(
            text = text,
            style = DetailSecondaryStyle.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
    }
}

@Composable
internal fun SmallIconBadge(
    imageVector: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(
                    LocalJellyfinPalette.current.cyan,
                    shape = RoundedCornerShape(DetailDimens.detailBadgeRadius),
                ).padding(
                    horizontal = DetailDimens.detailBadgeHorizontalPadding,
                    vertical = DetailDimens.detailBadgeVerticalPadding,
                ),
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = LocalJellyfinPalette.current.navy,
            modifier = Modifier.size(DetailDimens.detailBadgeIconSize),
        )
    }
}

@Composable
internal fun AmbientBackground(
    ambientColor: Color?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val fallbackBrush = LocalAppBackgroundBrush.current
    val ambientNavy = LocalJellyfinPalette.current.navy
    val ambientGradientBottom = LocalJellyfinPalette.current.gradientBottom
    val animatedColor =
        animateColorAsState(
            targetValue = ambientColor ?: Color.Transparent,
            animationSpec = tween(durationMillis = 700),
            label = "detail-ambient-background",
        )

    Box(
        modifier =
            modifier.drawBehind {
                drawRect(fallbackBrush)
                val tint = animatedColor.value
                if (tint.alpha > 0.004f) {
                    drawRect(
                        Brush.verticalGradient(
                            colorStops =
                                arrayOf(
                                    0f to tint,
                                    0.48f to tint.copy(alpha = tint.alpha * 0.44f).compositeOver(ambientNavy),
                                    1f to ambientGradientBottom,
                                ),
                        ),
                    )
                }
            },
        content = content,
    )
}
