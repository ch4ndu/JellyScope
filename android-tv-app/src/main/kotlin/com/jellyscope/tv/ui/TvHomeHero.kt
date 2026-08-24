// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import com.jellyscope.core.domain.model.Session
import com.jellyscope.tv.R
import com.jellyscope.ui.component.ImageDecode
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.TvHeroMetadata
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.component.tvHeroMetadata
import com.jellyscope.ui.theme.LocalJellyfinPalette
import kotlin.math.abs
import kotlin.math.roundToInt
import com.jellyscope.ui.component.DetailText as TvText

@Composable
internal fun TvHeroBackdrop(
    session: Session,
    item: MediaCardUi?,
    modifier: Modifier = Modifier,
) {
    Crossfade(
        targetState = item?.backdropUrl,
        animationSpec = tween(durationMillis = TvDimens.heroBackdropCrossfadeDurationMs),
        label = "tv-hero-backdrop",
        modifier =
            modifier
                .fillMaxWidth(TvDimens.heroBackdropWidthFraction)
                .height(TvDimens.heroBackdropHeight)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val horizontalMask =
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.45f to Color.Black,
                        )
                    val verticalMask =
                        Brush.verticalGradient(
                            0f to Color.Black,
                            0.78f to Color.Black,
                            1f to Color.Transparent,
                        )
                    onDrawWithContent {
                        drawContent()
                        // The image self-fades on its left and bottom edges
                        // (alpha mask) so it melts into the background from the
                        // physical screen corner with no hard seams.
                        drawRect(
                            brush = horizontalMask,
                            blendMode = BlendMode.DstIn,
                        )
                        drawRect(
                            brush = verticalMask,
                            blendMode = BlendMode.DstIn,
                        )
                    }
                },
    ) { imageUrl ->
        if (imageUrl != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = authenticatedImageRequest(imageUrl, session, ImageDecode.Backdrop),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors =
                                        listOf(
                                            LocalJellyfinPalette.current.navy.copy(alpha = 0.55f),
                                            LocalJellyfinPalette.current.navy.copy(alpha = 0.2f),
                                            Color.Transparent,
                                        ),
                                ),
                            ),
                )
            }
        }
    }
}

@Composable
internal fun TvHeroZone(
    item: MediaCardUi?,
    modifier: Modifier = Modifier,
) {
    // Hand-rolled cross-fade: Crossfade retains recently shown content, so
    // stepping BACK onto it resumed a partial alpha and read as a snap. This
    // always runs the full fade, both directions.
    var current by remember { mutableStateOf(item) }
    var previous by remember { mutableStateOf<MediaCardUi?>(null) }
    val incomingAlpha = remember { Animatable(1f) }
    LaunchedEffect(item) {
        if (item != current) {
            previous = current
            current = item
            incomingAlpha.snapTo(0f)
            incomingAlpha.animateTo(
                1f,
                animationSpec = tween(durationMillis = TvDimens.heroContentCrossfadeDurationMs),
            )
            previous = null
        }
    }
    Box(modifier = modifier) {
        previous?.let { prev ->
            TvHeroZoneContent(
                item = prev,
                modifier = Modifier.graphicsLayer { alpha = 1f - incomingAlpha.value },
            )
        }
        TvHeroZoneContent(
            item = current,
            modifier = Modifier.graphicsLayer { alpha = incomingAlpha.value },
        )
    }
}

@Composable
private fun TvHeroZoneContent(
    item: MediaCardUi?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(top = TvDimens.heroContentTopPadding),
        verticalArrangement = Arrangement.spacedBy(TvDimens.heroContentGap),
    ) {
        // Title may run wider than the description block: ~50% of the screen
        // (0.61 of the content column), wrapping to two lines.
        TvText(
            text = item?.title ?: stringResource(R.string.tv_loading),
            style = TvHeroTitleStyle,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(TvDimens.heroTitleWidthFraction),
        )
        val selected = item
        if (selected != null) {
            Column(
                modifier = Modifier.fillMaxWidth(TvDimens.heroContentWidthFraction),
                verticalArrangement = Arrangement.spacedBy(TvDimens.heroContentGap),
            ) {
                TvText(
                    text = selected.tvHeroMetadata().localizedLine(),
                    style = TvHeroMetadataStyle,
                    color = LocalJellyfinPalette.current.textSecondary,
                    maxLines = 1,
                )
                TvText(
                    text = selected.genresLine.orEmpty(),
                    style = TvHeroMetadataStyle,
                    color = LocalJellyfinPalette.current.textSecondary,
                    maxLines = 1,
                )
                TvText(
                    text = selected.overview ?: stringResource(R.string.tv_overview_fallback),
                    style = TvHeroOverviewStyle,
                    color = LocalJellyfinPalette.current.textSecondary,
                    maxLines = 3,
                )
            }
        } else {
            TvText(
                text = "",
                color = LocalJellyfinPalette.current.textSecondary,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun TvHeroMetadata.localizedLine(): String {
    val seriesEpisodeLine = metadataLine(listOfNotNull(seriesName, episodeLabel))
    val parts =
        listOfNotNull(
            seriesEpisodeLine.takeIf { value -> value.isNotBlank() },
            yearOrFallback,
            runtimeText,
            progressPercent?.let { progress -> stringResource(R.string.tv_progress, progress) },
            if (watched) {
                stringResource(R.string.tv_watched)
            } else {
                null
            },
            if (unsupported) {
                stringResource(R.string.tv_unsupported)
            } else {
                null
            },
        )
    return metadataLine(parts)
}

@Composable
private fun metadataLine(parts: List<String>): String {
    var line = parts.firstOrNull() ?: return ""
    for (index in 1..parts.lastIndex) {
        line = stringResource(R.string.tv_metadata_separator, line, parts[index])
    }
    return line
}

private const val POSTER_BITMAP_CACHE_SIZE = 24
internal const val HERO_SETTLE_MS = 220L
internal const val BACKDROP_PREFETCH_DEBOUNCE_MS = 120L

// Retains the most recently decoded poster bitmaps keyed by item id. A single
// slot only held the last-loaded tile, so focusing a card whose poster had
// already decoded (overwritten by later tiles) found no bitmap and the ambient
// color never updated past the first item. A small keyed cache bridges the gap
// between "poster decoded" and "card focused later".
internal class PosterBitmapSlot {
    private val bitmaps = LruCache<String, Bitmap>(POSTER_BITMAP_CACHE_SIZE)

    fun update(
        itemId: String,
        bitmap: Bitmap,
    ) {
        bitmaps.put(itemId, bitmap)
    }

    fun bitmapFor(itemId: String): Bitmap? = bitmaps.get(itemId)
}

// Anchored so the standard poster-row pitch (~282dp) takes ~320ms.
private const val VERTICAL_SCROLL_DP_PER_MS = 0.88f
private const val VERTICAL_SCROLL_MIN_MS = 160
private const val VERTICAL_SCROLL_MAX_MS = 700

internal fun verticalScrollDurationMs(
    distancePx: Float,
    density: Float,
): Int =
    (abs(distancePx) / density / VERTICAL_SCROLL_DP_PER_MS)
        .roundToInt()
        .coerceIn(VERTICAL_SCROLL_MIN_MS, VERTICAL_SCROLL_MAX_MS)
