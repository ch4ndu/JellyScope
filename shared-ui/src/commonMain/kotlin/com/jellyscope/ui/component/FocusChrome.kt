// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import androidx.compose.animation.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jellyscope.ui.screen.detail.DetailInteractionMode
import com.jellyscope.ui.screen.detail.LocalDetailFocusZoomEnabled
import com.jellyscope.ui.screen.detail.LocalDetailInteractionMode
import com.jellyscope.ui.theme.LocalAppBackgroundBrush
import com.jellyscope.ui.theme.LocalJellyfinPalette

object ChromeDimens {
    val overscanHorizontal = 16.dp
    val overscanVertical = 14.dp
    val focusBorder = 2.dp
    val cardRadius = 8.dp
    val panelRadius = 8.dp
    val rowGap = 24.dp
    val itemGap = 16.dp
    val buttonHorizontalPadding = 12.dp
    val buttonVerticalPadding = 6.dp
    val posterWidth = 105.dp
    val posterHeight = 158.dp
    val detailBackdropHeight = 320.dp
    val detailBackdropWidthFraction = 0.62f
    val detailHeroTextWidthFraction = 0.55f
    val heroTopInset = 14.dp
    val detailHeroTopPadding = 14.dp
    val detailHeroBottomPadding = 6.dp
    val detailHeroTextGap = 7.dp
    val detailSectionGap = 20.dp
    val detailMetaGap = 8.dp
    val detailIconLabelGap = 7.dp
    val detailActionMinHeight = 36.dp
    val detailActionHeight = 44.dp
    val detailTrackPickerButtonWidth = 118.dp
    val detailTrackPickerArrowSize = 18.dp
    val detailBadgeGap = 8.dp
    val detailBadgeRadius = 5.dp
    val detailBadgeHorizontalPadding = 8.dp
    val detailBadgeVerticalPadding = 3.dp
    val detailShelfTitleGap = 16.dp
    val detailShelfBottomPadding = 16.dp
    val detailPersonImageHeight = 118.dp
    val detailPersonTextGap = 5.dp
    val detailBackButtonWidth = 220.dp
    val seasonTabsGap = 10.dp
    val seasonTabHorizontalPadding = 16.dp
    val seasonTabVerticalPadding = 8.dp
    val seasonTabUnderlineHeight = 3.dp
    val seasonTabRadius = 18.dp
    val seasonMetadataTextWidthFraction = 0.58f
    val seasonMetadataGap = 10.dp
    val seasonEpisodeCardWidth = 172.dp
    val seasonEpisodeCardHeight = 97.dp
    val seasonEpisodeTextGap = 6.dp
    val seasonEpisodeBadgePadding = 7.dp
    val seasonEpisodeCardBottomPadding = 12.dp
    val progressHeight = 4.dp
    val playerIconSize = 22.dp
    val playerPickerRowHeight = 54.dp
    val playerPanelBorder = 1.dp
    val detailTrackPickerWidth = 640.dp
    val detailTrackPickerMaxHeight = 620.dp
    val detailTrackPickerListMaxHeight = 500.dp
    val detailTrackPickerRowVerticalPadding = 8.dp
    val detailTrackPickerCheckSize = 18.dp
    val detailTrackPickerDividerVerticalPadding = 8.dp
    val detailTrackPickerDividerHeight = 1.dp
    val mediaInfoDialogWidth = 640.dp
    val mediaInfoDialogMaxHeight = 620.dp
    val mediaInfoDialogSectionGap = 18.dp
    val mediaInfoDialogLineGap = 6.dp
    val playerPickerPadding = 24.dp
    val spinnerSize = 44.dp
    val spinnerStroke = 4.dp
}

val DetailHeadlineStyle: TextStyle
    @Composable get() =
        TextStyle(
            color = LocalJellyfinPalette.current.textPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )

val DetailTitleStyle: TextStyle
    @Composable get() =
        TextStyle(
            color = LocalJellyfinPalette.current.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )

val DetailBodyStyle: TextStyle
    @Composable get() =
        TextStyle(
            color = LocalJellyfinPalette.current.textPrimary,
            fontSize = 13.sp,
        )

val DetailSecondaryStyle: TextStyle
    @Composable get() =
        TextStyle(
            color = LocalJellyfinPalette.current.textSecondary,
            fontSize = 11.sp,
        )

@Composable
fun DetailText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = DetailBodyStyle,
    maxLines: Int = Int.MAX_VALUE,
    color: Color = style.color,
    minLines: Int = 1,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = style.copy(color = color),
        maxLines = maxLines,
        minLines = minLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Soft focus glow drawn with plain Canvas (concentric fading rounded-rect
 * strokes). Used instead of `Modifier.shadow`'s ambient/spot colors, which
 * require API 28+ and render as an invisible black shadow on JellyScope's
 * API-26+ Android floor over dark surfaces. No-op when [color] is null or
 * [elevation] is zero.
 */
fun Modifier.chromeFocusGlow(
    color: Color?,
    elevation: Dp,
    shape: RoundedCornerShape,
): Modifier =
    if (color == null || elevation <= 0.dp) {
        this
    } else {
        drawBehind {
            val corner = shape.topStart.toPx(size, this)
            val maxSpread = elevation.toPx()
            val layers = 6
            for (i in layers downTo 1) {
                val t = i / layers.toFloat()
                val spread = maxSpread * t
                drawRoundRect(
                    color = color.copy(alpha = color.alpha * (1f - t)),
                    topLeft = Offset(-spread, -spread),
                    size = Size(size.width + spread * 2f, size.height + spread * 2f),
                    cornerRadius = CornerRadius(corner + spread),
                    style = Stroke(width = maxSpread * 0.5f),
                )
            }
        }
    }

@Composable
fun FocusableBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusableWhenDisabled: Boolean = false,
    contentDescription: String? = null,
    focusedScale: Float = 1.1f,
    backgroundColor: Color = LocalJellyfinPalette.current.surfaceNavy,
    focusedBackgroundColor: Color = backgroundColor,
    unfocusedBorderColor: Color = LocalJellyfinPalette.current.outline,
    focusedBorderColor: Color = LocalJellyfinPalette.current.cyan,
    focusGlowColor: Color? = null,
    focusGlowElevation: Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    contentAlignment: Alignment = Alignment.TopStart,
    shape: RoundedCornerShape = RoundedCornerShape(ChromeDimens.cardRadius),
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val dpad = isChromeDpadMode()
    var focused by remember { mutableStateOf(false) }
    val focusZoom = LocalDetailFocusZoomEnabled.current
    val scale by animateFloatAsState(
        targetValue = if (dpad && focused && focusZoom) focusedScale else 1f,
        label = "detail-focus-scale",
        animationSpec = tween(),
    )
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.chromeFocusGlow(
                    color = if (dpad && focused) focusGlowColor else null,
                    elevation = focusGlowElevation,
                    shape = shape,
                ).clip(shape)
                .background(if (dpad && focused) focusedBackgroundColor else backgroundColor)
                .then(
                    if (dpad) {
                        Modifier.border(
                            width = if (focused) ChromeDimens.focusBorder else 1.dp,
                            color = if (focused) focusedBorderColor else unfocusedBorderColor,
                            shape = shape,
                        )
                    } else {
                        Modifier
                    },
                ).then(
                    if (dpad) {
                        Modifier.onFocusChanged { state -> focused = state.isFocused }
                    } else {
                        Modifier
                    },
                ).clickable(
                    interactionSource = interactionSource,
                    indication = if (dpad) null else LocalIndication.current,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ).then(
                    if (dpad) {
                        Modifier.focusable(enabled = enabled || focusableWhenDisabled)
                    } else {
                        Modifier
                    },
                ).then(
                    if (contentDescription != null) {
                        Modifier.semantics {
                            this.contentDescription = contentDescription
                            role = Role.Button
                        }
                    } else {
                        Modifier
                    },
                ).padding(contentPadding),
        contentAlignment = contentAlignment,
    ) {
        content(dpad && focused)
    }
}

@Composable
fun SmallBadge(
    text: String,
    modifier: Modifier = Modifier,
    contentColor: Color = LocalJellyfinPalette.current.navy,
    textStyle: TextStyle = DetailSecondaryStyle.copy(fontWeight = FontWeight.SemiBold),
    shape: RoundedCornerShape = RoundedCornerShape(ChromeDimens.detailBadgeRadius),
    contentPadding: PaddingValues =
        PaddingValues(
            horizontal = ChromeDimens.detailBadgeHorizontalPadding,
            vertical = ChromeDimens.detailBadgeVerticalPadding,
        ),
) {
    Box(
        modifier =
            modifier
                .background(
                    LocalJellyfinPalette.current.cyan,
                    shape = shape,
                ).padding(contentPadding),
    ) {
        DetailText(
            text = text,
            color = contentColor,
            style = textStyle,
            maxLines = 1,
        )
    }
}

@Composable
fun AdaptiveProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = LocalJellyfinPalette.current.outline,
    fillColor: Color = LocalJellyfinPalette.current.cyan,
    height: Dp = ChromeDimens.progressHeight,
    secondaryProgress: Float? = null,
    secondaryFillColor: Color = Color.White.copy(alpha = 0.35f),
    focusedCardInset: Boolean = false,
) {
    val insetModifier =
        if (focusedCardInset) {
            Modifier.padding(
                start = ChromeDimens.focusBorder,
                end = ChromeDimens.focusBorder,
                bottom = ChromeDimens.focusBorder,
            )
        } else {
            Modifier
        }

    Box(
        modifier =
            modifier
                .then(insetModifier)
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(trackColor),
    ) {
        secondaryProgress?.let { buffered ->
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(buffered.coerceIn(0f, 1f))
                        .background(secondaryFillColor),
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .background(fillColor),
        )
    }
}

private const val SPINNER_PERIOD_MS = 900L

@Composable
fun AdaptiveSpinner(
    modifier: Modifier = Modifier,
    size: Dp = ChromeDimens.spinnerSize,
    color: Color = Color.White,
) {
    // Drive the rotation from the frame clock instead of a tween-based infinite
    // transition. Tween durations are multiplied by the system animator
    // duration scale, which is 0 on many Fire TV devices (animations disabled),
    // collapsing a tween to a static frame. Frame-clock time ignores that
    // scale, so this buffering/loading spinner keeps spinning as functional
    // feedback even when the user has turned system animations off.
    var angle by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        val startMillis = withInfiniteAnimationFrameMillis { it }
        while (true) {
            withInfiniteAnimationFrameMillis { frameMillis ->
                val elapsed = (frameMillis - startMillis).mod(SPINNER_PERIOD_MS)
                angle = elapsed / SPINNER_PERIOD_MS.toFloat() * 360f
            }
        }
    }
    Canvas(modifier = modifier.size(size)) {
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 270f,
            useCenter = false,
            style =
                Stroke(
                    width = ChromeDimens.spinnerStroke.toPx(),
                    cap = StrokeCap.Round,
                ),
        )
    }
}

@Composable
fun AdaptiveCenteredSpinner(
    modifier: Modifier = Modifier,
    drawBackground: Boolean = true,
) {
    val baseModifier =
        if (drawBackground) {
            modifier
                .fillMaxSize()
                .background(LocalAppBackgroundBrush.current)
        } else {
            modifier.fillMaxSize()
        }
    Box(
        modifier = baseModifier,
        contentAlignment = Alignment.Center,
    ) {
        AdaptiveSpinner()
    }
}

@Composable
fun AmbientLayer(
    ambientColor: Color?,
    animationOwnerKey: Any? = null,
    clearWhenColorMissing: Boolean = true,
) {
    val legacyAnimatedColor =
        if (animationOwnerKey == null) {
            animateColorAsState(
                targetValue = ambientColor ?: Color.Transparent,
                animationSpec = tween(durationMillis = 700),
                label = "detail-ambient-layer",
            )
        } else {
            null
        }
    val ownerAnimatedColor =
        if (animationOwnerKey != null) {
            remember { Animatable(Color.Transparent) }
        } else {
            null
        }
    LaunchedEffect(ownerAnimatedColor, animationOwnerKey, clearWhenColorMissing, ambientColor) {
        val animatedColor = ownerAnimatedColor ?: return@LaunchedEffect
        // A non-empty owner with no color is pending, not an instruction to
        // clear the layer. Retain the current rendered tint and crossfade only
        // when that owner's accepted color arrives.
        if (ambientColor == null && !clearWhenColorMissing) return@LaunchedEffect
        animatedColor.animateTo(
            targetValue = ambientColor ?: Color.Transparent,
            animationSpec = tween(durationMillis = 700),
        )
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val color =
                        ownerAnimatedColor?.value ?: legacyAnimatedColor?.value ?: Color.Transparent
                    drawRect(
                        Brush.verticalGradient(
                            colorStops =
                                arrayOf(
                                    0f to color.copy(alpha = 0.55f),
                                    0.55f to color.copy(alpha = 0.22f),
                                    1f to color.copy(alpha = 0.08f),
                                ),
                        ),
                    )
                },
    )
}

@Composable
private fun isChromeDpadMode(): Boolean = LocalDetailInteractionMode.current == DetailInteractionMode.Dpad
