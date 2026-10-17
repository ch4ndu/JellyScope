// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.jellyscope.core.domain.model.RelatedGroupKind
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.DetailText
import com.jellyscope.ui.component.DetailTitleStyle
import com.jellyscope.ui.component.FocusableBox
import com.jellyscope.ui.component.MediaCard
import com.jellyscope.ui.component.MediaCardKind
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_related
import com.jellyscope.ui.generated.resources.detail_related_more_from
import com.jellyscope.ui.generated.resources.detail_related_more_genre
import com.jellyscope.ui.generated.resources.detail_related_more_like_this
import com.jellyscope.ui.generated.resources.detail_related_more_with
import com.jellyscope.ui.generated.resources.detail_related_next_up
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

// Resolves a related-shelf title from its group kind + dynamic label. Falls
// back to the generic "Related" label when a source has no label.
@Composable
internal fun relatedGroupTitle(
    kind: RelatedGroupKind,
    label: String?,
): String =
    when (kind) {
        RelatedGroupKind.NextUp -> stringResource(Res.string.detail_related_next_up)
        RelatedGroupKind.Similar -> stringResource(Res.string.detail_related_more_like_this)
        RelatedGroupKind.Cast ->
            label?.let { value -> stringResource(Res.string.detail_related_more_with, value) }
                ?: stringResource(Res.string.detail_related)
        RelatedGroupKind.Genre ->
            label?.let { value -> stringResource(Res.string.detail_related_more_genre, value) }
                ?: stringResource(Res.string.detail_related)
        RelatedGroupKind.Studio ->
            label?.let { value -> stringResource(Res.string.detail_related_more_from, value) }
                ?: stringResource(Res.string.detail_related)
    }

@Composable
internal fun RelatedSection(
    session: Session,
    title: String,
    items: List<MediaCardUi>,
    onItemSelected: (MediaCardUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()
    val rowListState = rememberLazyListState()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        SectionTitle(
            text = title,
            modifier =
                Modifier.padding(
                    start = horizontalContentPadding.start,
                    end = horizontalContentPadding.end,
                ),
        )
        LazyRow(
            state = rowListState,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .desktopScrollInput(rowListState, DesktopScrollOrientation.Horizontal),
            contentPadding = horizontalContentPadding.asPaddingValues(),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
        ) {
            items(
                items = items,
                key = { item -> item.id },
            ) { item ->
                MediaCard(
                    item = item,
                    session = session,
                    onClick = { onItemSelected(item) },
                )
            }
        }
    }
}

@Composable
internal fun AdaptiveRelatedShelf(
    session: Session,
    title: String,
    items: List<MediaCardUi>,
    horizontalBringIntoViewSpec: BringIntoViewSpec,
    onRelatedItemSelected: (MediaCardUi) -> Unit,
    onPlayRelatedDirect: ((MediaCardUi) -> Unit)? = null,
    modifier: Modifier = Modifier,
    relatedFocus: DetailFocusContainer? = null,
) {
    FocusableRibbon(
        title = title,
        items = items,
        key = { item -> item.id },
        horizontalBringIntoViewSpec = horizontalBringIntoViewSpec,
        modifier = modifier,
        focusContainer = relatedFocus,
    ) { item, focusModifier ->
        val playRelatedDirect = onPlayRelatedDirect
        val playModifier =
            if (playRelatedDirect != null && item.isRelatedDirectPlayable()) {
                Modifier.detailOnPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.MediaPlay || event.key == Key.MediaPlayPause)
                    ) {
                        playRelatedDirect(item)
                        true
                    } else {
                        false
                    }
                }
            } else {
                Modifier
            }
        AdaptiveMediaCard(
            session = session,
            item = item,
            wide = false,
            onClick = { onRelatedItemSelected(item) },
            focusModifier = focusModifier.then(playModifier),
        )
    }
}

private fun MediaCardUi.isRelatedDirectPlayable(): Boolean = kind == MediaCardKind.Movie || kind == MediaCardKind.Episode

// A single poster-sized loading tile with a centered spinner. Stays visible (as a
// trailing item after any already-loaded shelves) while more shelves are still
// being fetched. The "Related" header is shown only when no shelf has loaded yet.
@Composable
internal fun RelatedLoadingSection(
    showTitle: Boolean,
    modifier: Modifier = Modifier,
) {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        if (showTitle) {
            SectionTitle(
                text = stringResource(Res.string.detail_related),
                modifier =
                    Modifier.padding(
                        start = horizontalContentPadding.start,
                        end = horizontalContentPadding.end,
                    ),
            )
        }
        Box(
            modifier =
                Modifier
                    .padding(
                        start = horizontalContentPadding.start,
                        end = horizontalContentPadding.end,
                    ).width(Dimensions.posterCardWidth.tileScaled())
                    .aspectRatio(Dimensions.posterCardAspectRatio)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(Dimensions.progressIndicatorSize),
                strokeWidth = Dimensions.progressIndicatorStroke,
            )
        }
    }
}

@Composable
internal fun AdaptiveRelatedLoadingShelf(
    showTitle: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A single FOCUSABLE spinner tile (D-pad): otherwise it sits below the fold and
    // D-pad can't scroll to it, so the loading state would never be seen on TV.
    // Stays as a trailing item while more shelves stream in; onFocusChanged lets the
    // screen hand focus off once loading finishes. The header shows only when no
    // shelf has loaded yet.
    Column(
        modifier = Modifier.fillMaxWidth().onFocusChanged { state -> onFocusChanged(state.hasFocus) }.then(modifier),
        verticalArrangement = Arrangement.spacedBy(DetailDimens.detailShelfTitleGap),
    ) {
        if (showTitle) {
            DetailText(
                text = stringResource(Res.string.detail_related),
                modifier = Modifier.padding(horizontal = detailHorizontalInset()),
                style = DetailTitleStyle,
            )
        }
        FocusableBox(
            onClick = {},
            modifier =
                Modifier
                    .padding(horizontal = detailHorizontalInset())
                    .width(DetailDimens.posterWidth.tileScaled())
                    .height(DetailDimens.posterHeight.tileScaled()),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(Dimensions.progressIndicatorSize),
                strokeWidth = Dimensions.progressIndicatorStroke,
            )
        }
    }
}
