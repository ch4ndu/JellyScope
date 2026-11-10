// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.screen.detail.DetailDimens
import com.jellyscope.ui.theme.LocalJellyfinPalette

/** Artwork stays with its owner so public portraits never inherit session credentials. */
@Composable
fun PersonCard(
    name: String,
    role: String?,
    onClick: (() -> Unit)? = null,
    focusModifier: Modifier = Modifier,
    contentDescription: String = listOfNotNull(name, role).joinToString(". "),
    artwork: (@Composable BoxScope.() -> Unit)? = null,
) {
    Column(
        modifier =
            Modifier.width(DetailDimens.posterWidth.tileScaled()).semantics {
                this.contentDescription = contentDescription
                if (onClick != null) this.role = Role.Button
            },
        verticalArrangement = Arrangement.spacedBy(DetailDimens.detailPersonTextGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FocusableBox(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(DetailDimens.detailPersonImageHeight.tileScaled()).then(focusModifier),
            contentDescription = name,
            focusedScale = 1.06f,
            backgroundColor = LocalJellyfinPalette.current.surfaceRaised,
            focusedBackgroundColor = LocalJellyfinPalette.current.surfaceRaised,
            contentAlignment = Alignment.Center,
        ) {
            if (artwork != null) {
                artwork()
            } else {
                DetailText(name.take(1), style = DetailHeadlineStyle, color = LocalJellyfinPalette.current.textSecondary)
            }
        }
        DetailText(name, style = DetailBodyStyle, maxLines = 1)
        DetailText(role ?: " ", style = DetailSecondaryStyle, maxLines = 1)
    }
}
