// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.Session
import com.jellyscope.tv.TvUiPreferencesStore
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.DetailSecondaryStyle
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.PublicArtworkOwner
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

@Composable
internal fun TvLibraryGridHero(
    session: Session,
    item: MediaCardUi?,
    contentStartPadding: Dp = 0.dp,
    contentTopPadding: Dp = 0.dp,
    contentEndPadding: Dp = 0.dp,
    height: Dp = TvDimens.heroHeight,
    showBackdrop: Boolean = true,
    reserveGridViewport: Boolean = false,
    gridTopReserve: Dp = TvDimens.libraryGridContentTopReserve,
    gridBottomReserve: Dp = TvDimens.overscanVertical + TvDimens.rowGap,
    catalogMetadata: Boolean = false,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val labelStyle = TvCardLabelStyle
        val secondaryStyle = DetailSecondaryStyle
        val labelHeight =
            with(LocalDensity.current) {
                (labelStyle.lineHeight.takeIf { it.isSp } ?: (labelStyle.fontSize * 1.3f)).toDp() +
                    (secondaryStyle.lineHeight.takeIf { it.isSp } ?: (secondaryStyle.fontSize * 1.3f)).toDp()
            }
        val gridReserve =
            TvDimens.posterHeight.tileScaled() + labelHeight + TvDimens.cardTitleGap * 2 +
                gridTopReserve + gridBottomReserve
        val resolvedHeight =
            if (reserveGridViewport) height.coerceAtMost((maxHeight - gridReserve).coerceAtLeast(0.dp)) else height
        Box(
            Modifier
                .fillMaxWidth()
                .height(resolvedHeight),
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
                textHeightBudget = if (reserveGridViewport) (resolvedHeight - contentTopPadding).coerceAtLeast(0.dp) else null,
                catalogMetadata = catalogMetadata,
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
}

/** Preference, focus settlement and artwork ownership shared by media browsing surfaces. */
internal data class TvBrowseHeroState(
    val enabled: Boolean,
    val item: MediaCardUi?,
    val ambient: TvHeroAmbientState,
    val artworkEntry: String,
)

@Composable
internal fun rememberTvBrowseHero(
    session: Session,
    focusedItem: MediaCardUi?,
    surface: TvHeroAmbientSurface,
    entry: String,
    publicArtwork: Boolean = false,
): TvBrowseHeroState {
    val artworkEntry = "${LocalTvHostedRailController.current.contentRegistrationKey}|$entry"
    val preferences = koinInject<TvUiPreferencesStore>()
    val enabled by preferences.showLibraryGridHero.collectAsStateWithLifecycle()
    var settledItem by remember(session.serverId, session.userId, artworkEntry) { mutableStateOf<MediaCardUi?>(null) }
    LaunchedEffect(focusedItem, enabled, artworkEntry) {
        if (focusedItem == null) {
            settledItem = null
        } else if (enabled) {
            if (settledItem != null && settledItem?.id != focusedItem.id) delay(HERO_SETTLE_MS)
            settledItem = focusedItem
        }
    }
    val item = settledItem.takeIf { enabled && focusedItem != null }
    val account = AccountIdentity(session.serverId, session.userId)
    val url = item?.backdropUrl ?: item?.imageUrl
    val publicOwner = if (publicArtwork && url != null) PublicArtworkOwner(account, artworkEntry, url) else null
    val ambient =
        rememberTvHeroAmbientState(
            item = item,
            surface = surface,
            accountKey = "${session.serverId}|${session.userId}",
            artworkAccount = account,
            entry = artworkEntry,
            publicOwner = publicOwner,
        )
    return TvBrowseHeroState(enabled, item, ambient, artworkEntry)
}
