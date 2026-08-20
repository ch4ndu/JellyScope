// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import com.jellyscope.core.data.remote.ImageAuthHeaderProvider
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.component.ImageDecode
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.buildAuthenticatedImageRequest
import com.jellyscope.ui.screen.home.HomeRow
import com.jellyscope.ui.screen.home.HomeViewModel
import com.jellyscope.ui.screen.home.RowState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun TvHomeScreen(
    session: Session,
    restoredFocusItemId: String?,
    restoredFocusRow: String?,
    restoredFocusIndex: Int,
    restoredFocusViewAll: Boolean,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    onViewAllSelected: (HomeRow) -> Unit,
    modifier: Modifier = Modifier,
    onAmbientPresentationChanged: (TvHeroAmbientPresentation) -> Unit = {},
    viewModel: HomeViewModel =
        koinViewModel(
            parameters = { parametersOf(session) },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var focusedItem by remember { mutableStateOf<MediaCardUi?>(null) }
    var focusedRow by remember { mutableStateOf<HomeRow?>(null) }
    var heroItem by remember { mutableStateOf<MediaCardUi?>(null) }
    val ambientState =
        rememberTvHeroAmbientState(
            item = heroItem,
            surface = TvHeroAmbientSurface.Home,
            accountKey = "${session.serverId}|${session.userId}",
        )
    SideEffect {
        onAmbientPresentationChanged(ambientState.presentation)
    }
    DisposableEffect(Unit) {
        onDispose { onAmbientPresentationChanged(TvHeroAmbientPresentation.Empty) }
    }

    // Debounce backdrop prefetch so fast focus movement does no decode work.
    val platformContext = LocalPlatformContext.current
    val imageAuthHeaderProvider = koinInject<ImageAuthHeaderProvider>()
    LaunchedEffect(focusedItem) {
        val backdropUrl = focusedItem?.backdropUrl ?: return@LaunchedEffect
        delay(BACKDROP_PREFETCH_DEBOUNCE_MS)
        val request =
            buildAuthenticatedImageRequest(
                context = platformContext,
                imageUrl = backdropUrl,
                session = session,
                imageAuthHeaderProvider = imageAuthHeaderProvider,
                decode = ImageDecode.Backdrop,
            )
        SingletonImageLoader.get(platformContext).enqueue(request)
    }

    // Settle hero artwork after focus stops to avoid per-keypress decode work.
    LaunchedEffect(focusedItem) {
        val target = focusedItem ?: return@LaunchedEffect
        if (heroItem != null) {
            delay(HERO_SETTLE_MS)
        }
        heroItem = target
    }

    fun updateFocusedItem(
        item: MediaCardUi,
        row: HomeRow,
    ) {
        focusedItem = item
        focusedRow = row
    }

    // Snapshot entry restore targets so browsing does not recompose the tree.
    val initialFocusItemId = remember { restoredFocusItemId }
    val initialFocusRow = remember { restoredFocusRow }
    val initialFocusIndex = remember { restoredFocusIndex }
    val initialFocusViewAll = remember { restoredFocusViewAll }

    // Show cached rows immediately, then refresh in the background.
    LaunchedEffect(Unit) {
        viewModel.refreshSilently()
    }

    // If refresh removes the focused card from its row, rescue within that row.
    var focusRescueTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        snapshotFlow {
            val currentId = focusedItem?.id ?: return@snapshotFlow null to null
            val row = focusedRow ?: return@snapshotFlow currentId to null
            when (val rowState = state.rowState(row)) {
                // Loading may repopulate the row.
                is RowState.Loading -> currentId to null
                is RowState.Content -> currentId to rowState.items.any { item -> item.id == currentId }
                // Empty and error states remove the focused card.
                else -> currentId to false
            }
        }.distinctUntilChanged()
            .collect { (_, present) ->
                if (present == false) {
                    focusRescueTick++
                }
            }
    }

    TvHomeContent(
        session = session,
        state = state,
        focusedItem = heroItem,
        restoredFocusItemId = initialFocusItemId,
        restoredFocusRow = initialFocusRow,
        restoredFocusIndex = initialFocusIndex,
        restoredFocusViewAll = initialFocusViewAll,
        ambientPresentation = ambientState.presentation,
        onRetry = viewModel::retry,
        onFocusedItem = ::updateFocusedItem,
        onPosterLoaded = ambientState.onPosterLoaded,
        onItemSelected = onItemSelected,
        onItemPlayDirect = onItemPlayDirect,
        onViewAllSelected = onViewAllSelected,
        focusRescueTick = focusRescueTick,
        modifier = modifier,
    )
}
