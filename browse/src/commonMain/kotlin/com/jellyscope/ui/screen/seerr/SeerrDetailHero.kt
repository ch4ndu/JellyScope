// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.seerr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
) {
    val compact = showBack && LocalWindowWidthTier.current == WindowWidthTier.Compact
    Box(Modifier.fillMaxWidth().heightIn(min = DetailDimens.detailBackdropHeight)) {
        MeasuredAsyncImage(
            model = seerrArtworkUrl(detail.backdropUrl ?: detail.media.posterUrl),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxWidth(if (compact) 1f else DetailDimens.detailBackdropWidthFraction)
                    .height(DetailDimens.detailBackdropHeight)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        if (!compact) drawRect(detailBackdropHorizontalFade, blendMode = BlendMode.DstIn)
                        drawRect(detailBackdropVerticalFade, blendMode = BlendMode.DstIn)
                    },
        )
        FlowRow(
            Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(
                start = Dimensions.screenPadding,
                end = Dimensions.screenPadding,
                top = Dimensions.minTouchTarget,
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
                Text(detail.media.title ?: stringResource(Res.string.seerr_untitled), style = MaterialTheme.typography.headlineLarge)
                Text(listOfNotNull(seerrMediaTypeText(detail.media.key.type), detail.media.year?.toString()).joinToString(" · "))
            }
        }
        if (showBack) HeroDetailBackButton(stringResource(Res.string.seerr_back), onBack, Modifier.align(Alignment.TopStart))
    }
}
