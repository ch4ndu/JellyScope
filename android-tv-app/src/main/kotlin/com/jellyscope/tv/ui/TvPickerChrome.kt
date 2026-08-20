// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Icon
import com.jellyscope.tv.ui.focus.TvFocusTrapEffect
import com.jellyscope.ui.theme.LocalJellyfinPalette
import com.jellyscope.ui.component.DetailText as TvText

@Composable
fun TvPickerOverlay(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp? = null,
    scrollable: Boolean = false,
    opaque: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    TvFocusTrapEffect()

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
    ) {
        Column(
            modifier =
                modifier
                    .align(Alignment.BottomCenter)
                    .width(TvDimens.playerPickerWidth)
                    .then(maxHeight?.let { Modifier.heightIn(max = it) } ?: Modifier)
                    .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier)
                    .clip(
                        RoundedCornerShape(
                            topStart = TvDimens.panelRadius,
                            topEnd = TvDimens.panelRadius,
                        ),
                    ).background(
                        if (opaque) {
                            LocalJellyfinPalette.current.gradientBottom
                        } else {
                            LocalJellyfinPalette.current.gradientBottom.copy(alpha = 0.95f)
                        },
                    ).padding(
                        start = TvDimens.playerPickerPadding,
                        end = TvDimens.playerPickerPadding,
                        top = TvDimens.playerPickerPadding,
                        bottom = TvDimens.playerPickerPadding / 2,
                    ),
            verticalArrangement = Arrangement.spacedBy(TvDimens.progressHeight),
        ) {
            TvText(
                text = title,
                style = TvPickerTitleStyle,
                maxLines = 1,
            )
            TvPickerDivider()
            content()
        }
    }
}

@Composable
fun TvPickerDivider(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = TvDimens.detailBadgeGap)
                .height(Dp.Hairline)
                .background(Color.White.copy(alpha = 0.12f)),
    )
}

@Composable
fun TvPickerRow(
    title: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(TvDimens.panelRadius),
    contentDescription: String = title,
) {
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(TvDimens.playerPickerRowHeight)
                .clip(shape)
                .background(
                    if (focused) {
                        Color.White.copy(alpha = 0.14f)
                    } else {
                        Color.Transparent
                    },
                ).then(
                    focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
                ).onFocusChanged { state -> focused = state.isFocused }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ).focusable()
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                }.padding(horizontal = TvDimens.formGap),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvText(
            text = title,
            maxLines = 1,
        )
        if (selected) {
            Icon(
                imageVector = TvIcons.CheckBold,
                contentDescription = null,
                tint = LocalJellyfinPalette.current.cyan,
                modifier = Modifier.size(TvDimens.playerPickerCheckSize),
            )
        }
    }
}
