// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import com.jellyscope.ui.theme.LocalJellyfinPalette
import com.jellyscope.ui.component.DetailBodyStyle as TvBodyStyle
import com.jellyscope.ui.component.DetailText as TvText
import com.jellyscope.ui.component.FocusableBox as TvFocusableBox

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    contentDescription: String = text,
) {
    TvFocusableBox(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = TvDimens.minButtonHeight),
        enabled = enabled,
        contentDescription = contentDescription,
        focusedScale = 1.05f,
        backgroundColor = LocalJellyfinPalette.current.surfaceRaised,
        focusedBackgroundColor = Color.White,
        focusedBorderColor = Color.White,
        contentPadding =
            PaddingValues(
                horizontal = TvDimens.buttonHorizontalPadding,
                vertical = TvDimens.buttonVerticalPadding,
            ),
        contentAlignment = Alignment.Center,
        shape = RoundedCornerShape(percent = 50),
    ) { focused ->
        TvText(
            text = text,
            style = TvBodyStyle.copy(fontWeight = FontWeight.SemiBold),
            color =
                when {
                    focused -> LocalJellyfinPalette.current.onFocusedLight
                    selected -> LocalJellyfinPalette.current.cyan
                    else -> LocalJellyfinPalette.current.textPrimary
                },
            maxLines = 1,
        )
    }
}
