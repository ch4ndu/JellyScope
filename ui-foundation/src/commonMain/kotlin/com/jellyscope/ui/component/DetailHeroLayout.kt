// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import com.jellyscope.ui.screen.detail.DetailDimens
import com.jellyscope.ui.screen.detail.detailBackdropHorizontalFade
import com.jellyscope.ui.screen.detail.detailBackdropVerticalFade

/** Feature adapters own request authentication; this component owns shared artwork/fades. */
@Composable
fun DetailHeroBackdrop(
    artworkUrl: String?,
    imageRequest: @Composable (String) -> Any,
    onImageLoaded: (AmbientImageSource) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    imageLoader: ImageLoader = SingletonImageLoader.get(LocalPlatformContext.current),
) {
    Crossfade(
        targetState = artworkUrl,
        animationSpec = tween(durationMillis = 500),
        label = "adaptive-detail-backdrop",
        modifier =
            modifier
                .fillMaxWidth(if (compact) 1f else DetailDimens.detailBackdropWidthFraction)
                .height(DetailDimens.detailBackdropHeight)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (!compact) drawRect(brush = detailBackdropHorizontalFade, blendMode = BlendMode.DstIn)
                    drawRect(brush = detailBackdropVerticalFade, blendMode = BlendMode.DstIn)
                },
    ) { url ->
        if (url != null) {
            MeasuredAsyncImage(
                model = imageRequest(url),
                contentDescription = null,
                imageLoader = imageLoader,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = { onImageLoaded(AmbientImageSource(it.result, imageLoader, url)) },
            )
        }
    }
}

/** Shared text-led detail geometry; metadata and actions stay with each feature. */
@Composable
fun DetailHeroLayout(
    topPadding: Dp,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth(if (compact) 1f else DetailDimens.detailHeroTextWidthFraction)
                .padding(top = topPadding, bottom = DetailDimens.detailHeroBottomPadding),
        verticalArrangement = Arrangement.spacedBy(DetailDimens.detailHeroTextGap, Alignment.Top),
        content = content,
    )
}
