// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.seerr

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.component.MeasuredAsyncImage
import com.jellyscope.ui.screen.person.PersonContent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

/** Adapts TMDB identities and public artwork to the existing person presentation. */
@Composable
fun SeerrPersonContent(
    state: SeerrPersonUi,
    session: Session,
    onIntent: (SeerrIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberLazyListState(state.scroll.index, state.scroll.offset)
    val movies = rememberLazyListState(state.movieScroll.index, state.movieScroll.offset)
    val series = rememberLazyListState(state.seriesScroll.index, state.seriesScroll.offset)
    val restoreId = remember(state.entryId) { state.focusedId }
    val credits = state.credits
    LaunchedEffect(scroll, movies, series) {
        snapshotFlow {
            SeerrIntent.RememberPerson(
                SeerrScrollPosition(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset),
                SeerrScrollPosition(movies.firstVisibleItemIndex, movies.firstVisibleItemScrollOffset),
                SeerrScrollPosition(series.firstVisibleItemIndex, series.firstVisibleItemScrollOffset),
            )
        }.distinctUntilChanged().collect { onIntent(it) }
    }
    PersonContent(
        session = session,
        state = state.presentation,
        onBack = { onIntent(SeerrIntent.Back) },
        onRetry = { onIntent(SeerrIntent.RetryPerson) },
        onLoadMore = {},
        onItemSelected = {
            onIntent(
                SeerrIntent.RememberPerson(
                    SeerrScrollPosition(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset),
                    SeerrScrollPosition(movies.firstVisibleItemIndex, movies.firstVisibleItemScrollOffset),
                    SeerrScrollPosition(series.firstVisibleItemIndex, series.firstVisibleItemScrollOffset),
                ),
            )
            onIntent(SeerrIntent.PersonFocused(it.id))
            onIntent(SeerrIntent.OpenCredit(it.id))
        },
        onPlayItem = { _, _ -> },
        modifier = modifier.fillMaxSize(),
        headerArtwork = { url, bounds -> SeerrPersonArtwork(url, bounds) },
        cardArtwork = { item, bounds -> credits[item.id]?.let { SeerrPoster(it, bounds) } },
        listState = scroll,
        moviesState = movies,
        seriesState = series,
        initialFocusId = restoreId,
        onFocused = { onIntent(SeerrIntent.PersonFocused(it)) },
    )
}

@Composable
fun SeerrPersonArtwork(
    url: String?,
    modifier: Modifier = Modifier,
) {
    MeasuredAsyncImage(
        model = seerrArtworkUrl(url),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}
