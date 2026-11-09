// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.seerr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import com.jellyscope.core.domain.model.SeerrDetail
import com.jellyscope.ui.adaptive.LocalWindowWidthTier
import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.HeroDetailBackButton
import com.jellyscope.ui.component.MeasuredAsyncImage
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.seerr_back
import com.jellyscope.ui.generated.resources.seerr_untitled
import com.jellyscope.ui.screen.detail.DetailDimens
import com.jellyscope.ui.screen.detail.detailBackdropHorizontalFade
import com.jellyscope.ui.screen.detail.detailBackdropVerticalFade
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeerrDetailHero(
    detail: SeerrDetail,
    onBack: () -> Unit,
    showBack: Boolean = true,
    isTv: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    val compact = showBack && LocalWindowWidthTier.current == WindowWidthTier.Compact
    Box(Modifier.fillMaxWidth()) {
        MeasuredAsyncImage(
            model = seerrArtworkUrl(detail.backdropUrl ?: detail.media.posterUrl),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxWidth(if (compact) 1f else DetailDimens.detailBackdropWidthFraction)
                    .matchParentSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        if (!compact) drawRect(detailBackdropHorizontalFade, blendMode = BlendMode.DstIn)
                        drawRect(detailBackdropVerticalFade, blendMode = BlendMode.DstIn)
                    },
        )
        FlowRow(
            Modifier.fillMaxWidth().align(Alignment.TopStart).padding(
                start = Dimensions.screenPadding,
                end = Dimensions.screenPadding,
                top = if (showBack) Dimensions.minTouchTarget else Dimensions.contentSpacing,
                bottom = DetailDimens.detailHeroBottomPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.detailSectionSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
        ) {
            SeerrPoster(detail.media, Modifier.width(Dimensions.detailPosterWidth.tileScaled()))
            Column(
                Modifier.widthIn(max = Dimensions.formControlMaxWidth),
                verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
            ) {
                Text(
                    detail.media.title ?: stringResource(Res.string.seerr_untitled),
                    style = MaterialTheme.typography.headlineLarge,
                    maxLines = if (isTv) 2 else Int.MAX_VALUE,
                    overflow = if (isTv) TextOverflow.Ellipsis else TextOverflow.Clip,
                )
                Text(listOfNotNull(seerrMediaTypeText(detail.media.key.type), detail.media.year?.toString()).joinToString(" · "))
                detail.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (isTv) 3 else Int.MAX_VALUE,
                        overflow = if (isTv) TextOverflow.Ellipsis else TextOverflow.Clip,
                    )
                }
                actions()
            }
        }
        if (showBack) HeroDetailBackButton(stringResource(Res.string.seerr_back), onBack, Modifier.align(Alignment.TopStart))
    }
}
