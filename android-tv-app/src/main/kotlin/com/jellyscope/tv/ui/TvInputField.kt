// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jellyscope.tv.R
import com.jellyscope.ui.component.chromeFocusGlow
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.theme.LocalJellyfinPalette
import com.jellyscope.ui.component.DetailBodyStyle as TvBodyStyle
import com.jellyscope.ui.component.DetailText as TvText

data class TvInputFieldColors(
    val container: Color,
    val unfocusedBorder: Color,
    val focusedBorder: Color,
    val text: Color,
    val placeholder: Color,
    val label: Color,
    val focusedLabel: Color,
    val caption: Color,
)

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun TvInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    contentDescription: String = label,
    imeAction: ImeAction = ImeAction.Done,
    keyboardType: KeyboardType = KeyboardType.Text,
    onImeAction: () -> Unit = {},
    onEditingCommitted: () -> Unit = {},
    onEditingChange: (Boolean) -> Unit = {},
    exitEditingRequests: Int = 0,
    colors: TvInputFieldColors? = null,
    textStyle: TextStyle = TvBodyStyle,
    glowColor: Color? = null,
    glowElevation: Dp = 0.dp,
) {
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editorHadFocus by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvDimens.inputFieldOutlineRadius)
    val displayFocusRequester = remember { FocusRequester() }
    val editFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputColors = colors ?: defaultTvInputFieldColors()

    fun setEditing(nextEditing: Boolean) {
        if (editing != nextEditing) {
            editing = nextEditing
            onEditingChange(nextEditing)
        }
    }

    fun stopEditing(
        restoreDisplayFocus: Boolean,
        onComplete: () -> Unit = {},
    ) {
        if (!editing) {
            return
        }
        setEditing(false)
        editorHadFocus = false
        keyboardController?.hide()
        if (restoreDisplayFocus) {
            displayFocusRequester.requestFocusSafely()
        }
        onEditingCommitted()
        onComplete()
    }

    LaunchedEffect(editing, enabled) {
        if (editing && enabled) {
            editFocusRequester.requestFocusSafely()
            keyboardController?.show()
        } else if (!enabled && editing) {
            stopEditing(restoreDisplayFocus = false)
        }
    }

    LaunchedEffect(exitEditingRequests) {
        if (exitEditingRequests > 0 && editing) {
            stopEditing(restoreDisplayFocus = true)
        }
    }

    Box(
        modifier =
            modifier
                .height(TvDimens.inputFieldTotalHeight)
                .focusRequester(displayFocusRequester)
                .onFocusChanged { state -> focused = state.isFocused || state.hasFocus }
                // No separate .focusable(): clickable installs its own focus
                // target, and clickable only maps DPAD_CENTER/ENTER to a click
                // when THAT target is focused — a trailing focusable node takes
                // the D-pad focus instead and OK never opens the editor. The
                // requester must precede clickable so it binds to the same
                // target D-pad traversal lands on. Clickable stays enabled
                // during editing (guard in onClick) so the display box remains
                // a stable focus target for the stop-editing focus restore.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    onClick = { if (!editing) setEditing(true) },
                ).semantics {
                    this.contentDescription = contentDescription
                },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(TvDimens.inputFieldHeight)
                    .chromeFocusGlow(
                        color = if (focused) glowColor else null,
                        elevation = glowElevation,
                        shape = shape,
                    ).clip(shape)
                    .background(inputColors.container)
                    .border(
                        width = if (focused) TvDimens.focusBorder else TvDimens.inputFieldUnfocusedBorder,
                        color = if (focused) inputColors.focusedBorder else inputColors.unfocusedBorder,
                        shape = shape,
                    ).padding(
                        horizontal = TvDimens.inputFieldHorizontalPadding,
                        vertical = TvDimens.inputFieldVerticalPadding,
                    ),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (editing && enabled) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(editFocusRequester)
                            .onFocusChanged { state ->
                                // onFocusChanged emits isFocused=false once on
                                // attach, before the LaunchedEffect can request
                                // focus — treating that as external focus loss
                                // cancels editing in the same frame it started.
                                // Only auto-stop after the editor actually held
                                // focus.
                                if (state.isFocused) {
                                    editorHadFocus = true
                                } else if (editorHadFocus && editing) {
                                    stopEditing(restoreDisplayFocus = false)
                                }
                            }.onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                                    stopEditing(restoreDisplayFocus = true)
                                    true
                                } else {
                                    false
                                }
                            },
                    enabled = enabled,
                    textStyle = textStyle.copy(color = inputColors.text),
                    singleLine = true,
                    visualTransformation = visualTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                    keyboardActions =
                        KeyboardActions(
                            onSearch = { stopEditing(restoreDisplayFocus = true, onComplete = onImeAction) },
                            onDone = { stopEditing(restoreDisplayFocus = true, onComplete = onImeAction) },
                            onNext = { stopEditing(restoreDisplayFocus = true, onComplete = onImeAction) },
                            onGo = { stopEditing(restoreDisplayFocus = true, onComplete = onImeAction) },
                        ),
                )
            } else {
                val displayValue =
                    if (value.isBlank()) {
                        label
                    } else {
                        visualTransformation.filter(AnnotatedString(value)).text.text
                    }
                TvText(
                    text = displayValue,
                    color =
                        if (value.isBlank()) {
                            inputColors.placeholder
                        } else {
                            inputColors.text
                        },
                    style = textStyle,
                    maxLines = 1,
                )
            }
        }

        if (value.isNotBlank() || editing) {
            TvText(
                text = label,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(y = -TvDimens.inputFieldFloatingLabelOffset)
                        .padding(start = TvDimens.inputFieldFloatingLabelStartPadding)
                        .background(inputColors.container)
                        .padding(horizontal = TvDimens.inputFieldFloatingLabelHorizontalPadding),
                style = textStyle.copy(fontSize = TvDimens.inputFieldFloatingLabelTextSize),
                color = if (focused) inputColors.focusedLabel else inputColors.label,
                maxLines = 1,
            )
        }

        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(TvDimens.inputFieldCaptionHeight)
                    .padding(
                        start = TvDimens.inputFieldCaptionStartPadding,
                        top = TvDimens.inputFieldCaptionTopPadding,
                    ),
            contentAlignment = Alignment.TopStart,
        ) {
            if (focused && !editing) {
                TvText(
                    text = stringResource(R.string.tv_input_click_to_enter),
                    style = textStyle.copy(fontSize = TvDimens.inputFieldCaptionTextSize),
                    color = inputColors.caption,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun defaultTvInputFieldColors(): TvInputFieldColors {
    val palette = LocalJellyfinPalette.current
    return TvInputFieldColors(
        container = palette.surfaceNavy,
        unfocusedBorder = palette.outline,
        focusedBorder = palette.cyan,
        text = palette.textPrimary,
        placeholder = palette.textSecondary,
        label = palette.textSecondary,
        focusedLabel = palette.cyan,
        caption = palette.textSecondary,
    )
}
