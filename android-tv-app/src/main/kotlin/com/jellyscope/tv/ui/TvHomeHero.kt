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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import com.jellyscope.core.domain.model.Session
import com.jellyscope.hero.HeroBackdrop
import com.jellyscope.hero.HeroContentCrossfade
import com.jellyscope.tv.R
import com.jellyscope.ui.component.ImageDecode
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.TvHeroMetadata
import com.jellyscope.ui.component.authenticatedImageRequest
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
) {
    HeroBackdrop(
        target = item?.backdropUrl,
        assetKey = item?.backdropUrl,
        navy = LocalJellyfinPalette.current.navy,
        modifier =
            modifier
                .fillMaxWidth(TvDimens.heroBackdropWidthFraction)
                .height(TvDimens.heroBackdropHeight)
                .onSizeChanged(onSizeChanged),
    ) { imageUrl ->
        if (imageUrl != null) {
            AsyncImage(
                model = authenticatedImageRequest(imageUrl, session, ImageDecode.Backdrop),
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
) {
    HeroContentCrossfade(target = item, assetKey = item?.id, modifier = modifier) {
        TvHeroZoneContent(it)
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
            text = item?.title.orEmpty(),
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
