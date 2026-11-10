// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.Session
import com.jellyscope.hero.HeroBackdrop
import com.jellyscope.hero.HeroContentCrossfade
import com.jellyscope.tv.R
import com.jellyscope.ui.component.AmbientImageSource
import com.jellyscope.ui.component.ImageDecode
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.PublicArtworkOwner
import com.jellyscope.ui.component.TvHeroMetadata
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.component.mediaCardKindLabel
import com.jellyscope.ui.component.publicArtworkRequest
import com.jellyscope.ui.component.tvHeroMetadata
import com.jellyscope.ui.theme.LocalJellyfinPalette
import com.jellyscope.ui.component.DetailText as TvText
import com.jellyscope.ui.component.MeasuredAsyncImage as AsyncImage

@Composable
internal fun TvHeroBackdrop(
    session: Session,
    item: MediaCardUi?,
    modifier: Modifier = Modifier,
    onSizeChanged: (IntSize) -> Unit = {},
    publicArtworkEntry: String? = null,
    onArtworkLoaded: (AmbientImageSource) -> Unit = {},
) {
    val imageLoader = SingletonImageLoader.get(LocalPlatformContext.current)
    HeroBackdrop(
        target = item?.backdropUrl ?: item?.imageUrl,
        assetKey = item?.backdropUrl ?: item?.imageUrl,
        navy = LocalJellyfinPalette.current.navy,
        modifier =
            modifier
                .fillMaxWidth(TvDimens.heroBackdropWidthFraction)
                .height(TvDimens.heroBackdropHeight)
                .onSizeChanged(onSizeChanged),
    ) { imageUrl ->
        if (imageUrl != null) {
            AsyncImage(
                model =
                    if (publicArtworkEntry != null) {
                        publicArtworkRequest(
                            PublicArtworkOwner(AccountIdentity(session.serverId, session.userId), publicArtworkEntry, imageUrl),
                        )
                    } else {
                        authenticatedImageRequest(imageUrl, session, ImageDecode.Backdrop)
                    },
                imageLoader = imageLoader,
                onSuccess = { onArtworkLoaded(AmbientImageSource(it.result, imageLoader, imageUrl)) },
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
internal fun TvHeroZone(
    item: MediaCardUi?,
    modifier: Modifier = Modifier,
    textHeightBudget: Dp? = null,
    catalogMetadata: Boolean = false,
) {
    HeroContentCrossfade(target = item, assetKey = item?.id, modifier = modifier) {
        TvHeroZoneContent(it, textHeightBudget = textHeightBudget, catalogMetadata = catalogMetadata)
    }
}

@Composable
private fun TvHeroZoneContent(
    item: MediaCardUi?,
    modifier: Modifier = Modifier,
    textHeightBudget: Dp? = null,
    catalogMetadata: Boolean = false,
) {
    val constrained = textHeightBudget != null
    val titleStyle =
        if (constrained) {
            TvHeroTitleStyle.copy(fontSize = TvDimens.heroCompactTitleSize, lineHeight = TvDimens.heroCompactTitleLineHeight)
        } else {
            TvHeroTitleStyle
        }
    val metadataStyle =
        if (constrained) {
            TvHeroMetadataStyle.copy(fontSize = TvDimens.heroCompactBodySize, lineHeight = TvDimens.heroCompactMetadataLineHeight)
        } else {
            TvHeroMetadataStyle
        }
    val overviewStyle =
        if (constrained) {
            TvHeroOverviewStyle.copy(fontSize = TvDimens.heroCompactBodySize, lineHeight = TvDimens.heroCompactMetadataLineHeight)
        } else {
            TvHeroOverviewStyle
        }
    val gap = if (constrained) TvDimens.heroCompactGap else TvDimens.heroContentGap
    val top = if (constrained) TvDimens.heroCompactGap else TvDimens.heroContentTopPadding
    val genres = if (catalogMetadata) item?.genresLine?.takeIf { it.isNotBlank() } else item?.genresLine.orEmpty()
    val overview =
        if (catalogMetadata) {
            item?.overview?.takeIf { it.isNotBlank() }
        } else {
            item?.overview ?: stringResource(R.string.tv_overview_fallback)
        }
    // Explicit line heights include font scaling. Keep title and metadata intact;
    // spend the remaining measured budget on whole overview lines, never a clip.
    val overviewLines =
        with(LocalDensity.current) {
            if (textHeightBudget == null) {
                3
            } else {
                val fixed =
                    top + titleStyle.lineHeight.toDp() + metadataStyle.lineHeight.toDp() + gap * 2 +
                        (if (genres != null) metadataStyle.lineHeight.toDp() + gap else 0.dp)
                ((textHeightBudget - fixed) / overviewStyle.lineHeight.toDp()).toInt().coerceIn(1, 3)
            }
        }
    Column(
        modifier = modifier.padding(top = top),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        // Browse grids budget a single title line; unconstrained Home keeps two.
        TvText(
            text = item?.title.orEmpty(),
            style = titleStyle,
            maxLines = if (constrained) 1 else 2,
            modifier = Modifier.fillMaxWidth(TvDimens.heroTitleWidthFraction),
        )
        val selected = item
        if (selected != null) {
            Column(
                modifier = Modifier.fillMaxWidth(TvDimens.heroContentWidthFraction),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                TvText(
                    text =
                        if (catalogMetadata) {
                            metadataLine(listOfNotNull(selected.year, mediaCardKindLabel(selected.kind)))
                        } else {
                            selected.tvHeroMetadata().localizedLine()
                        },
                    style = metadataStyle,
                    color = LocalJellyfinPalette.current.textSecondary,
                    maxLines = 1,
                )
                if (genres != null) {
                    TvText(
                        text = genres,
                        style = metadataStyle,
                        color = LocalJellyfinPalette.current.textSecondary,
                        maxLines = 1,
                    )
                }
                if (overview != null) {
                    TvText(
                        text = overview,
                        style = overviewStyle,
                        color = LocalJellyfinPalette.current.textSecondary,
                        maxLines = overviewLines,
                    )
                }
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
