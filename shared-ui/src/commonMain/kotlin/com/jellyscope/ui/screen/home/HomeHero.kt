// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.adaptive.adaptiveHomeHeroAspectRatio
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.component.ImageDecode
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.component.isDirectlyPlayable
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.home_featured_cd
import com.jellyscope.ui.generated.resources.home_featured_info
import com.jellyscope.ui.generated.resources.home_featured_info_cd
import com.jellyscope.ui.generated.resources.home_featured_loading
import com.jellyscope.ui.generated.resources.home_featured_play
import com.jellyscope.ui.generated.resources.home_featured_play_cd
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun FeaturedHeroCarousel(
    featured: RowState,
    session: Session,
    onPlayItem: (String, Long) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val heroAspectRatio = adaptiveHomeHeroAspectRatio()

    when (featured) {
        RowState.Loading ->
            FeaturedHeroLoading(
                heroAspectRatio = heroAspectRatio,
                modifier = modifier,
            )
        is RowState.Content -> {
            val items = featured.items
            if (items.isEmpty()) {
                return
            }
            val pagerState = rememberPagerState(pageCount = { items.size })

            Column(modifier = modifier.fillMaxWidth()) {
                HorizontalPager(
                    state = pagerState,
                    key = { page -> items[page].id },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(heroAspectRatio),
                ) { page ->
                    FeaturedHeroPage(
                        item = items[page],
                        session = session,
                        onPlayItem = onPlayItem,
                        onItemSelected = onItemSelected,
                    )
                }
                // The page indicator sits BELOW the whole carousel (not overlaid on
                // it) so it never collides with the hero's Play/Info buttons, and it
                // stays fixed as pages slide underneath.
                if (items.size > 1) {
                    FeaturedHeroDots(
                        pageCount = items.size,
                        currentPage = { pagerState.currentPage },
                        modifier =
                            Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(vertical = Dimensions.contentSpacing),
                    )
                }
            }
        }
        RowState.Empty,
        RowState.Error,
        -> Unit
    }
}

@Composable
private fun FeaturedHeroLoading(
    heroAspectRatio: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(heroAspectRatio)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(Dimensions.progressIndicatorSize),
                strokeWidth = Dimensions.progressIndicatorStroke,
            )
            Text(
                text = stringResource(Res.string.home_featured_loading),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun FeaturedHeroPage(
    item: MediaCardUi,
    session: Session,
    onPlayItem: (String, Long) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentDescription = stringResource(Res.string.home_featured_cd, item.title)
    val playContentDescription = stringResource(Res.string.home_featured_play_cd, item.title)
    val infoContentDescription = stringResource(Res.string.home_featured_info_cd, item.title)
    val imageUrl = item.backdropUrl ?: item.imageUrl
    val playEnabled = item.supported && item.kind.isDirectlyPlayable()
    val backgroundColor = MaterialTheme.colorScheme.background
    val horizontalContentPadding = adaptiveHorizontalContentPadding()

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics { this.contentDescription = contentDescription },
    ) {
        imageUrl?.let { url ->
            AsyncImage(
                model = authenticatedImageRequest(url, session, ImageDecode.Backdrop),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    backgroundColor.copy(alpha = HOME_HERO_SCRIM_START_ALPHA),
                                    backgroundColor.copy(alpha = HOME_HERO_SCRIM_MID_ALPHA),
                                    backgroundColor.copy(alpha = HOME_HERO_SCRIM_END_ALPHA),
                                ),
                        ),
                    ),
        )
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = horizontalContentPadding.start,
                        end = horizontalContentPadding.end,
                        bottom = Dimensions.homeHeroContentBottomPadding,
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimensions.homeHeroTitleSpacing),
        ) {
            Text(
                text = item.title,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.heroMetadataLine?.let { metadataLine ->
                Text(
                    text = metadataLine,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimensions.homeHeroButtonSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onItemSelected(item) },
                    modifier =
                        Modifier
                            .heightIn(min = Dimensions.minTouchTarget)
                            .semantics {
                                this.contentDescription = infoContentDescription
                                role = Role.Button
                            },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        modifier = Modifier.size(Dimensions.detailActionButtonIconSize),
                    )
                    Spacer(Modifier.width(Dimensions.inlineSpacing))
                    Text(stringResource(Res.string.home_featured_info))
                }
                Button(
                    onClick = { onPlayItem(item.id, item.resumePositionTicks ?: 0L) },
                    enabled = playEnabled,
                    modifier =
                        Modifier
                            .heightIn(min = Dimensions.minTouchTarget)
                            .semantics {
                                this.contentDescription = playContentDescription
                                role = Role.Button
                            },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(Dimensions.detailActionButtonIconSize),
                    )
                    Spacer(Modifier.width(Dimensions.inlineSpacing))
                    Text(stringResource(Res.string.home_featured_play))
                }
            }
        }
    }
}

@Composable
private fun FeaturedHeroDots(
    pageCount: Int,
    currentPage: () -> Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.homeHeroDotSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val current = currentPage()
        repeat(pageCount) { index ->
            val dotColor =
                if (index == current) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = HOME_HERO_INACTIVE_DOT_ALPHA)
                }
            Box(
                modifier =
                    Modifier
                        .size(Dimensions.homeHeroDotSize)
                        .background(
                            color = dotColor,
                            shape = CircleShape,
                        ),
            )
        }
    }
}

private const val HOME_HERO_SCRIM_START_ALPHA = 0.08f
private const val HOME_HERO_SCRIM_MID_ALPHA = 0.42f
private const val HOME_HERO_SCRIM_END_ALPHA = 0.96f
private const val HOME_HERO_INACTIVE_DOT_ALPHA = 0.48f
