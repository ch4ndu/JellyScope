// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jellyscope.tv.R
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.theme.LocalJellyfinPalette
import com.jellyscope.ui.component.DetailBodyStyle as TvBodyStyle
import com.jellyscope.ui.component.DetailText as TvText
import com.jellyscope.ui.component.DetailTitleStyle as TvTitleStyle
import com.jellyscope.ui.component.FocusableBox as TvFocusableBox

internal data class TvSettingsDialogOption<T>(
    val value: T,
    val label: String,
    val enabled: Boolean = true,
    val unavailableReason: String? = null,
    val swatchColors: List<Color> = emptyList(),
    val description: String? = null,
)

/** Window-backed dialog so the Settings grid and drawer cannot receive focus. */
@Composable
internal fun TvSettingsDialogFrame(
    title: String,
    onDismiss: () -> Unit,
    dismissFocusRequester: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalJellyfinPalette.current
    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = TvDimens.settingsPickerScrimAlpha))
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                            onDismiss()
                            true
                        } else {
                            false
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier =
                    Modifier
                        .width(TvDimens.settingsDialogWidth)
                        .heightIn(max = TvDimens.settingsDialogMaxHeight)
                        .then(
                            dismissFocusRequester?.let { requester ->
                                Modifier
                                    .focusRequester(requester)
                                    .focusGroup()
                                    .focusable()
                            } ?: Modifier.focusGroup(),
                        ).background(
                            color = palette.gradientBottom.copy(alpha = TvDimens.settingsPickerPanelAlpha),
                            shape = RoundedCornerShape(TvDimens.panelRadius),
                        ).border(
                            width = TvDimens.settingsCardOutlineWidth,
                            color = palette.outline.copy(alpha = TvDimens.settingsPickerOutlineAlpha),
                            shape = RoundedCornerShape(TvDimens.panelRadius),
                        ).padding(TvDimens.settingsDialogContentPadding),
                verticalArrangement = Arrangement.spacedBy(TvDimens.settingsDialogActionGap),
            ) {
                TvText(text = title, style = TvTitleStyle, maxLines = 2)
                content()
                TvText(
                    // Text-only dialogs (dismissFocusRequester set) have nothing to select, so
                    // they only advertise Back; dialogs with focusable options/actions get the
                    // full "Select to apply · Back to cancel" hint.
                    text =
                        stringResource(
                            if (dismissFocusRequester == null) {
                                R.string.tv_settings_dialog_footer
                            } else {
                                R.string.tv_settings_dialog_back_hint
                            },
                        ),
                    modifier = Modifier.align(Alignment.End),
                    style = TvBodyStyle.copy(fontStyle = FontStyle.Italic),
                    color = palette.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun <T> TvSettingsChoiceDialog(
    title: String,
    selected: T,
    options: List<TvSettingsDialogOption<T>>,
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    description: String? = null,
    initialFocusValue: T = selected,
) {
    val focusValue =
        tvSettingsDialogInitialSelection(
            initialFocusValue,
            options.map { option -> option.value to option.enabled },
        )
    val requesters = remember(options) { List(options.size) { FocusRequester() } }
    var applied by remember { mutableStateOf(false) }
    LaunchedEffect(focusValue) {
        val index = options.indexOfFirst { it.value == focusValue }
        if (index >= 0) requestTvFocusWithRetry { requesters[index].requestFocusSafely() }
    }
    TvSettingsDialogFrame(title = title, onDismiss = onDismiss) {
        description?.let { text ->
            TvText(
                text = text,
                color = LocalJellyfinPalette.current.textSecondary,
                maxLines = 3,
            )
        }
        options.forEachIndexed { index, option ->
            TvSettingsChoiceRow(
                option = option,
                selected = option.value == selected,
                focusRequester = requesters[index],
                isFirst = index == 0,
                isLast = index == options.lastIndex,
                onClick = {
                    if (tvSettingsDialogApplies(TvSettingsDialogEvent.Select, option.enabled, applied)) {
                        applied = true
                        onSelected(option.value)
                    }
                },
            )
        }
    }
}

@Composable
private fun <T> TvSettingsChoiceRow(
    option: TvSettingsDialogOption<T>,
    selected: Boolean,
    focusRequester: FocusRequester,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalJellyfinPalette.current
    TvFocusableBox(
        onClick = onClick,
        enabled = option.enabled,
        focusableWhenDisabled = true,
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    event.type == KeyEventType.KeyDown &&
                        (
                            event.key == Key.DirectionLeft ||
                                event.key == Key.DirectionRight ||
                                (isFirst && event.key == Key.DirectionUp) ||
                                (isLast && event.key == Key.DirectionDown)
                        )
                },
        contentDescription = listOfNotNull(option.label, option.description).joinToString(". "),
        focusedScale = 1f,
        backgroundColor = palette.surfaceRaised.copy(alpha = 0.82f),
        focusedBackgroundColor = palette.surfaceRaised,
        focusedBorderColor = palette.cyan,
        focusGlowColor = palette.cyan.copy(alpha = TvDimens.SETTINGS_FOCUS_GLOW_ALPHA),
        focusGlowElevation = TvDimens.settingsPanelFocusGlow,
        contentPadding = TvDimens.settingsPickerRowPadding,
    ) { focused ->
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TvDimens.settingsDialogOptionIndicatorGap),
            ) {
                TvText(
                    text = option.label,
                    modifier = Modifier.weight(1f),
                    style = TvBodyStyle.copy(fontWeight = FontWeight.SemiBold),
                    color =
                        when {
                            focused -> palette.textPrimary
                            selected -> palette.cyan
                            option.enabled -> palette.textPrimary
                            else -> palette.textSecondary
                        },
                    maxLines = 1,
                )
                if (option.swatchColors.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(TvDimens.settingsThemeSwatchGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        option.swatchColors.forEach { color ->
                            Box(
                                modifier =
                                    Modifier
                                        .size(TvDimens.settingsThemeSwatchSize)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            width = TvDimens.settingsThemeSwatchBorder,
                                            color = palette.outline,
                                            shape = CircleShape,
                                        ),
                            )
                        }
                    }
                }
                if (selected) {
                    TvText(
                        text = stringResource(R.string.tv_settings_dialog_current),
                        color = palette.cyan,
                        maxLines = 1,
                    )
                } else {
                    Spacer(Modifier.width(TvDimens.settingsDialogCurrentLabelReserve))
                }
                Box(
                    modifier =
                        Modifier
                            .size(TvDimens.settingsDialogOptionIndicatorSize)
                            .clip(CircleShape)
                            .border(
                                width = TvDimens.settingsCardOutlineWidth,
                                color = if (selected) palette.cyan else palette.textPrimary,
                                shape = CircleShape,
                            ).padding(TvDimens.settingsDialogOptionIndicatorInset),
                ) {
                    if (selected) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(palette.cyan),
                        )
                    }
                }
            }
            option.description?.let { description ->
                TvText(text = description, color = palette.textSecondary, maxLines = 3)
            }
            option.unavailableReason?.takeIf { !option.enabled }?.let { reason ->
                TvText(text = reason, color = palette.textSecondary, maxLines = 2)
            }
        }
    }
}

@Composable
internal fun TvSettingsDetailDialog(
    title: String,
    detail: String,
    onDismiss: () -> Unit,
    maxLines: Int = 8,
) {
    val dismissFocusRequester = remember { FocusRequester() }
    LaunchedEffect(dismissFocusRequester) { requestTvFocusWithRetry { dismissFocusRequester.requestFocusSafely() } }
    TvSettingsDialogFrame(
        title = title,
        onDismiss = onDismiss,
        dismissFocusRequester = dismissFocusRequester,
    ) {
        TvText(text = detail, color = LocalJellyfinPalette.current.textSecondary, maxLines = maxLines)
    }
}

@Composable
internal fun TvSettingsDialogAction(
    text: String,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    selected: Boolean? = null,
    labelAlignment: TextAlign = TextAlign.Start,
) {
    val palette = LocalJellyfinPalette.current
    TvFocusableBox(
        onClick = onClick,
        enabled = enabled,
        focusableWhenDisabled = true,
        modifier = modifier.fillMaxWidth().focusRequester(focusRequester),
        contentDescription = text,
        focusedScale = 1f,
        backgroundColor = palette.surfaceRaised.copy(alpha = 0.82f),
        focusedBackgroundColor = palette.surfaceRaised,
        focusedBorderColor = if (destructive) palette.error else palette.cyan,
        focusGlowColor =
            (if (destructive) palette.error else palette.cyan).copy(
                alpha = TvDimens.SETTINGS_FOCUS_GLOW_ALPHA,
            ),
        focusGlowElevation = TvDimens.settingsPanelFocusGlow,
        contentPadding = TvDimens.settingsPickerRowPadding,
    ) { focused ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvDimens.settingsDialogOptionIndicatorGap),
        ) {
            TvText(
                text = text,
                modifier = Modifier.weight(1f),
                style = TvBodyStyle.copy(fontWeight = FontWeight.SemiBold, textAlign = labelAlignment),
                color =
                    if (focused) {
                        palette.textPrimary
                    } else if (destructive) {
                        palette.error
                    } else {
                        palette.textPrimary
                    },
                maxLines = 1,
            )
            selected?.let { isSelected ->
                if (isSelected) {
                    TvText(
                        text = stringResource(R.string.tv_settings_dialog_current),
                        color = palette.cyan,
                        maxLines = 1,
                    )
                } else {
                    Spacer(Modifier.width(TvDimens.settingsDialogCurrentLabelReserve))
                }
                Box(
                    modifier =
                        Modifier
                            .size(TvDimens.settingsDialogOptionIndicatorSize)
                            .clip(CircleShape)
                            .border(
                                width = TvDimens.settingsCardOutlineWidth,
                                color = if (isSelected) palette.cyan else palette.textPrimary,
                                shape = CircleShape,
                            ).padding(TvDimens.settingsDialogOptionIndicatorInset),
                ) {
                    if (isSelected) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(palette.cyan),
                        )
                    }
                }
            }
        }
    }
}
