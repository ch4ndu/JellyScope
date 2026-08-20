// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.component.MediaCardUi

@Composable
internal fun TvLibraryGridHero(
    session: Session,
    item: MediaCardUi?,
    contentStartPadding: Dp = 0.dp,
    contentTopPadding: Dp = 0.dp,
    contentEndPadding: Dp = 0.dp,
    height: Dp = TvDimens.heroHeight,
    showBackdrop: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth().height(height),
    ) {
        if (showBackdrop) {
            TvHeroBackdrop(
                session = session,
                item = item,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        TvHeroZone(
            item = item,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = contentStartPadding,
                        top = contentTopPadding,
                        end = contentEndPadding,
                    ),
        )
    }
}
