// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import coil3.compose.AsyncImage
import coil3.toBitmap
import com.jellyscope.core.domain.model.Session
import com.jellyscope.tv.R
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.CardImageAspect
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.SmallBadge
import com.jellyscope.ui.component.WatchedBadge
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.component.cardImageDecode
import com.jellyscope.ui.component.mediaCardKindLabel
import com.jellyscope.ui.theme.LocalJellyfinPalette
import com.jellyscope.ui.component.AdaptiveProgressBar as TvProgressBar
import com.jellyscope.ui.component.DetailSecondaryStyle as TvSecondaryStyle
import com.jellyscope.ui.component.DetailText as TvText
import com.jellyscope.ui.component.FocusableBox as TvFocusableBox

@Composable
internal fun TvMediaCard(
    session: Session,
    item: MediaCardUi,
    wide: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPosterLoaded: ((Bitmap) -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    focusChildModifier: Modifier = Modifier,
    onPlayDirect: (() -> Unit)? = null,
) {
    val requester = focusRequester ?: remember { FocusRequester() }
    val focusedScale = if (LocalTvFocusZoomEnabled.current) 1.1f else 1f
    // Wide cards crop portrait posters badly; prefer the backdrop.
    val imageUrl = if (wide) item.backdropUrl ?: item.imageUrl else item.imageUrl
    val loadedPosterBitmap = remember(item.id, imageUrl) { mutableStateOf<Bitmap?>(null) }

    Column(
        modifier =
            modifier
                .width((if (wide) TvDimens.libraryWidth else TvDimens.posterWidth).tileScaled())
                .alpha(if (item.supported) 1f else 0.55f),
        verticalArrangement = Arrangement.spacedBy(TvDimens.cardTitleGap),
    ) {
        TvFocusableBox(
            onClick = onClick,
            focusedScale = focusedScale,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height((if (wide) TvDimens.libraryHeight else TvDimens.posterHeight).tileScaled())
                    .focusRequester(requester)
                    .then(focusChildModifier)
                    // Remote PLAY on a focused tile starts playback directly.
                    .onPreviewKeyEvent { event ->
                        event.type == KeyEventType.KeyDown &&
                            (event.key == Key.MediaPlay || event.key == Key.MediaPlayPause) &&
                            onPlayDirect != null &&
                            run {
                                onPlayDirect()
                                true
                            }
                    }.onFocusChanged { state ->
                        if (state.isFocused) {
                            // Coil does not re-run onSuccess merely because an
                            // already-composed card regains focus. Re-publish
                            // its decoded bitmap so the bounded ambient cache
                            // can recover after eviction without another fetch.
                            loadedPosterBitmap.value?.let { bitmap ->
                                onPosterLoaded?.invoke(bitmap)
                            }
                            onFocused()
                        }
                    },
            contentDescription = item.title,
        ) { focused ->
            Box(modifier = Modifier.fillMaxSize()) {
                if (imageUrl != null) {
                    // Decode at the size the card is drawn at. A 1080p TV draws this
                    // card ~210px wide; it used to decode every poster at a fixed
                    // 480x720 — 5.2x the pixels — by upscaling a 300px download and
                    // then letting the GPU scale it back down. Both the decode and
                    // the palette bitmap below shrink with it.
                    val density = LocalDensity.current
                    val aspect = if (wide) CardImageAspect.Wide else CardImageAspect.Poster
                    val cardWidth = (if (wide) TvDimens.libraryWidth else TvDimens.posterWidth).tileScaled()
                    val decode =
                        remember(cardWidth, density, aspect) {
                            cardImageDecode(
                                widthPx = with(density) { cardWidth.toPx() },
                                aspect = aspect,
                            )
                        }
                    // The descriptor only describes the primary image, so it is used
                    // when that is what is being rendered; a backdrop keeps its
                    // pre-built URL.
                    val usesPrimaryImage = !wide || item.backdropUrl == null
                    val request =
                        item.imageRef
                            ?.takeIf { usesPrimaryImage }
                            ?.let { ref -> authenticatedImageRequest(ref, session, decode) }
                            ?: authenticatedImageRequest(imageUrl, session, decode)
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        onSuccess = { state ->
                            onPosterLoaded?.let { callback ->
                                val image = state.result.image
                                val bitmap =
                                    image.toBitmap(
                                        width = image.width.coerceAtLeast(1),
                                        height = image.height.coerceAtLeast(1),
                                    )
                                loadedPosterBitmap.value = bitmap
                                callback(
                                    bitmap,
                                )
                            }
                        },
                    )
                } else {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(LocalJellyfinPalette.current.surfaceRaised),
                    )
                }
                val itemProgress = item.progressFraction
                if (itemProgress != null) {
                    TvProgressBar(
                        progress = itemProgress,
                        fillColor = LocalJellyfinPalette.current.accentAmber,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        focusedCardInset = focused,
                    )
                }
                when {
                    !item.supported ->
                        SmallBadge(
                            text = stringResource(R.string.tv_unsupported),
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(TvDimens.cardBadgePadding),
                            contentColor = LocalJellyfinPalette.current.onFocusedLight,
                            textStyle = TvSecondaryStyle,
                            shape = RoundedCornerShape(TvDimens.cardBadgeRadius),
                        )
                    // A compact check avoids a text pill covering too much
                    // of the poster.
                    item.watched ->
                        TvWatchedBadge(
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(TvDimens.watchedBadgePadding),
                        )
                    item.unplayedCount != null ->
                        SmallBadge(
                            text = item.unplayedCount.toString(),
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(TvDimens.cardBadgePadding),
                            contentColor = LocalJellyfinPalette.current.onFocusedLight,
                            textStyle = TvSecondaryStyle,
                            shape = RoundedCornerShape(TvDimens.cardBadgeRadius),
                        )
                }
            }
        }
        TvText(
            text = item.title,
            style = TvCardLabelStyle,
            maxLines = 1,
        )
        TvText(
            text = item.subtitle ?: mediaCardKindLabel(item.kind),
            style = TvSecondaryStyle,
            maxLines = 1,
        )
    }
}

@Composable
internal fun TvWatchedBadge(modifier: Modifier = Modifier) {
    // Delegates to the app-wide watched treatment so TV, shared tiles, and
    // adaptive detail cards render one identical badge.
    WatchedBadge(
        contentDescription = stringResource(R.string.tv_watched),
        modifier = modifier,
    )
}
