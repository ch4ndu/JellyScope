// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.jellyscope.ui.theme.AmbientLaunchDimens
import com.jellyscope.ui.theme.AmbientLaunchTokens

/** Translucent fixed-palette container for compact Ambient launch controls. */
@Composable
fun AmbientGlassPanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(AmbientLaunchDimens.panelPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(AmbientLaunchDimens.panelRadius)

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(AmbientLaunchTokens.glassFill)
                .border(AmbientLaunchDimens.panelBorder, AmbientLaunchTokens.glassBorder, shape)
                .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.panelGap),
        content = content,
    )
}
