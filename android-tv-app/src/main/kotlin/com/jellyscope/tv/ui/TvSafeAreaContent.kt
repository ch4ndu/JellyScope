// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jellyscope.ui.theme.LocalAppBackgroundBrush

@Composable
fun TvSafeAreaContent(
    drawAppBackground: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .then(
                    if (drawAppBackground) {
                        Modifier.background(LocalAppBackgroundBrush.current)
                    } else {
                        Modifier
                    },
                ).padding(
                    horizontal = TvDimens.overscanHorizontal,
                    vertical = TvDimens.overscanVertical,
                ),
        content = content,
    )
}
