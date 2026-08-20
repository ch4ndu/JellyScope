// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.screen.detail.AdaptiveDetailScreen
import com.jellyscope.ui.screen.detail.AdaptiveSeasonScreen
import com.jellyscope.ui.screen.detail.AdaptiveSeriesScreen
import com.jellyscope.ui.screen.detail.DetailInteractionMode
import com.jellyscope.ui.screen.detail.DetailViewModel
import com.jellyscope.ui.screen.detail.LocalDetailInteractionMode
import com.jellyscope.ui.screen.detail.SeriesViewModel
import org.koin.android.ext.android.get

@Composable
internal fun TvDetailAdapter(
    session: Session,
    itemId: String,
    onBack: () -> Unit,
    onPlay: (String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit,
    onPlayWithSubtitleIntent: (String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit,
    onRelatedItemSelected: (MediaCardUi) -> Unit,
    onPlayRelatedDirect: (MediaCardUi) -> Unit,
    onPersonSelected: (String) -> Unit,
    viewModel: DetailViewModel,
) {
    CompositionLocalProvider(LocalDetailInteractionMode provides DetailInteractionMode.Dpad) {
        AdaptiveDetailScreen(
            session = session,
            itemId = itemId,
            onBack = onBack,
            onPlay = onPlay,
            onPlayWithSubtitleIntent = onPlayWithSubtitleIntent,
            onRelatedItemSelected = onRelatedItemSelected,
            onPlayRelatedDirect = onPlayRelatedDirect,
            onPersonSelected = onPersonSelected,
            viewModel = viewModel,
        )
    }
}

@Composable
internal fun TvSeriesSeasonAdapter(
    showingSeason: Boolean,
    session: Session,
    seriesId: String,
    initialSeasonId: String,
    onBackFromSeries: () -> Unit,
    onBackFromSeason: () -> Unit,
    onPlayFromSeries: (String, Long, String?, Int?, SubtitleSelectionIntent, List<String>) -> Unit,
    onPlayFromSeason: (String, Long, String?, Int?, SubtitleSelectionIntent, List<String>) -> Unit,
    onSeasonSelected: (String) -> Unit,
    onSeasonTabSelected: (String) -> Unit,
    onRelatedItemSelected: (MediaCardUi) -> Unit,
    onPlayRelatedDirect: (MediaCardUi) -> Unit,
    onPersonSelected: (String) -> Unit,
    viewModel: SeriesViewModel,
) {
    CompositionLocalProvider(LocalDetailInteractionMode provides DetailInteractionMode.Dpad) {
        if (showingSeason) {
            AdaptiveSeasonScreen(
                session = session,
                seriesId = seriesId,
                initialSeasonId = initialSeasonId,
                onBack = onBackFromSeason,
                onPlay = onPlayFromSeason,
                onSeasonSelected = onSeasonTabSelected,
                onPersonSelected = onPersonSelected,
                viewModel = viewModel,
            )
        } else {
            AdaptiveSeriesScreen(
                session = session,
                seriesId = seriesId,
                onBack = onBackFromSeries,
                onPlay = onPlayFromSeries,
                onSeasonSelected = onSeasonSelected,
                onRelatedItemSelected = onRelatedItemSelected,
                onPlayRelatedDirect = onPlayRelatedDirect,
                onPersonSelected = onPersonSelected,
                viewModel = viewModel,
            )
        }
    }
}
