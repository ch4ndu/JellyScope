// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.component.AppTopBar
import com.jellyscope.ui.component.LoadingIndicator
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.OnResumeEffect
import com.jellyscope.ui.component.RetryableError
import com.jellyscope.ui.component.appTopBarContentPadding
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.libraries_empty
import com.jellyscope.ui.generated.resources.libraries_error
import com.jellyscope.ui.generated.resources.library_picker_cd
import com.jellyscope.ui.generated.resources.library_shuffle_empty
import com.jellyscope.ui.generated.resources.library_shuffle_unavailable
import com.jellyscope.ui.generated.resources.library_tab_label
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun LibraryTabScreen(
    session: Session,
    onItemSelected: (MediaCardUi) -> Unit,
    onPlayItem: (String, Long) -> Unit,
    onShuffleQueue: (List<String>) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = Dimensions.zero,
    viewModel: LibraryTabViewModel = koinViewModel(parameters = { parametersOf(session) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LibraryTabContent(
        session = session,
        state = state,
        onRetry = viewModel::retry,
        onLibrarySelected = viewModel::selectLibrary,
        onItemSelected = onItemSelected,
        onPlayItem = onPlayItem,
        onShuffleQueue = onShuffleQueue,
        onSettingsClick = onSettingsClick,
        modifier = modifier,
        bottomContentPadding = bottomContentPadding,
    )
}

@Composable
private fun LibraryTabContent(
    session: Session,
    state: LibraryTabUiState,
    onRetry: () -> Unit,
    onLibrarySelected: (String) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onPlayItem: (String, Long) -> Unit,
    onShuffleQueue: (List<String>) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = Dimensions.zero,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val libraries =
        when (state) {
            is LibraryTabUiState.Loaded -> state.libraries
            LibraryTabUiState.Error,
            LibraryTabUiState.Loading,
            -> emptyList()
        }
    val selectedLibraryId = (state as? LibraryTabUiState.Loaded)?.selectedLibraryId
    val selectedLibrary = libraries.firstOrNull { library -> library.id == selectedLibraryId } ?: libraries.firstOrNull()
    val libraryStateHolder = rememberSaveableStateHolder()
    val title = stringResource(Res.string.library_tab_label)
    val showStandaloneTopBar =
        when (state) {
            is LibraryTabUiState.Loaded -> state.libraries.isEmpty() || selectedLibrary == null
            LibraryTabUiState.Error,
            LibraryTabUiState.Loading,
            -> true
        }

    // Reset scroll to the top only when the user switches to a DIFFERENT library
    // tab — NOT on the recomposition when returning from a detail/player screen,
    // where the grid's saved scroll position must be preserved. ScrollToTopOnChange
    // skips the initial (and post-nav-return) observation. (Matches LibraryScreen.)
    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            LibraryTabUiState.Loading ->
                LibraryTabCenteredMessage(
                    topContentPadding = appTopBarContentPadding(),
                    bottomContentPadding = bottomContentPadding,
                ) {
                    LibraryTabLoading()
                }
            LibraryTabUiState.Error ->
                LibraryTabCenteredMessage(
                    topContentPadding = appTopBarContentPadding(),
                    bottomContentPadding = bottomContentPadding,
                ) {
                    LibraryTabError(onRetry = onRetry)
                }
            is LibraryTabUiState.Loaded ->
                if (state.libraries.isEmpty() || selectedLibrary == null) {
                    LibraryTabCenteredMessage(
                        topContentPadding = appTopBarContentPadding(),
                        bottomContentPadding = bottomContentPadding,
                    ) {
                        Text(
                            text = stringResource(Res.string.libraries_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    libraryStateHolder.SaveableStateProvider(selectedLibrary.id) {
                        LibraryTabHub(
                            session = session,
                            library = selectedLibrary,
                            onItemSelected = onItemSelected,
                            onPlayItem = onPlayItem,
                            onShuffleQueue = onShuffleQueue,
                            onShuffleMessage = { message -> snackbarHostState.showSnackbar(message) },
                            bottomContentPadding = bottomContentPadding,
                            modifier = Modifier.fillMaxSize(),
                            outerTopBar = { actions ->
                                AppTopBar(
                                    title = title,
                                    titleContent = {
                                        LibraryDropdown(
                                            libraries = state.libraries,
                                            selectedLibrary = selectedLibrary,
                                            onLibrarySelected = onLibrarySelected,
                                        )
                                    },
                                    visible = true,
                                    onSettingsClick = onSettingsClick,
                                    actions = actions,
                                )
                            },
                        )
                    }
                }
        }
        if (showStandaloneTopBar) {
            AppTopBar(
                title = title,
                visible = true,
                onSettingsClick = onSettingsClick,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
internal fun LibraryDropdown(
    libraries: List<Library>,
    selectedLibrary: Library,
    onLibrarySelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val pickerDescription = stringResource(Res.string.library_picker_cd)

    Box(
        modifier =
            Modifier
                .wrapContentWidth()
                .semantics { contentDescription = pickerDescription },
    ) {
        Row(
            modifier =
                Modifier
                    // Wrap the label and its chevron so the chevron sits beside the
                    // title instead of at the far edge of the bar, and keep the whole
                    // pair a full-height touch target centred in the top bar.
                    .wrapContentWidth()
                    .heightIn(min = Dimensions.minTouchTarget)
                    .clickable { expanded = true },
            horizontalArrangement = Arrangement.spacedBy(Dimensions.labelGlyphSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedLibrary.name,
                // fill = false keeps the row content-sized while still letting a long
                // library name ellipsize instead of pushing the chevron off-screen.
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            libraries.forEach { library ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = library.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        expanded = false
                        onLibrarySelected(library.id)
                    },
                    trailingIcon = {
                        if (library.id == selectedLibrary.id) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun LibraryTabHub(
    session: Session,
    library: Library,
    onItemSelected: (MediaCardUi) -> Unit,
    onPlayItem: (String, Long) -> Unit,
    onShuffleQueue: (List<String>) -> Unit,
    onShuffleMessage: suspend (String) -> Unit,
    outerTopBar: @Composable (@Composable RowScope.() -> Unit) -> Unit,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
    hubViewModel: LibraryHubViewModel =
        koinViewModel(
            key = "library-hub-${library.id}-${session.serverId}-${session.userId}",
            parameters = { parametersOf(session, library.id, library.collectionType) },
        ),
) {
    val hubState by hubViewModel.state.collectAsStateWithLifecycle()
    val recommendedListState = rememberLazyListState()

    OnResumeEffect(hubViewModel::refreshRecommendedSilently)

    val innerTabs: @Composable () -> Unit = {
        LibraryInnerViewTabs(
            views = hubState.availableViews,
            selectedView = hubState.selectedView,
            onViewSelected = hubViewModel::selectView,
        )
    }

    when (hubState.selectedView) {
        com.jellyscope.core.domain.model.LibraryInnerView.Library ->
            LibraryGridInnerView(
                session = session,
                library = library,
                onItemSelected = onItemSelected,
                onPlayItem = onPlayItem,
                onShuffleQueue = onShuffleQueue,
                onShuffleMessage = onShuffleMessage,
                controlsResetKey = library.id,
                bottomContentPadding = bottomContentPadding,
                topBarContent = { actions ->
                    Column {
                        outerTopBar(actions)
                        innerTabs()
                        Spacer(Modifier.height(Dimensions.libraryInnerViewContentSpacing))
                    }
                },
                modifier = modifier,
            )
        else ->
            Column(modifier = modifier.fillMaxSize()) {
                outerTopBar {}
                innerTabs()
                when (hubState.selectedView) {
                    com.jellyscope.core.domain.model.LibraryInnerView.Recommended ->
                        LibraryRecommendedContent(
                            session = session,
                            states = hubState.recommendationSections,
                            listState = recommendedListState,
                            onRetry = hubViewModel::retryRecommendation,
                            onItemSelected = onItemSelected,
                            topPadding = Dimensions.libraryInnerViewContentSpacing,
                            bottomPadding = Dimensions.screenPadding + bottomContentPadding,
                            modifier = Modifier.weight(1f),
                        )
                    // Library is handled by the branch above; Genres/Collections are
                    // retained enum entries that are no longer selectable views.
                    com.jellyscope.core.domain.model.LibraryInnerView.Library,
                    com.jellyscope.core.domain.model.LibraryInnerView.Genres,
                    com.jellyscope.core.domain.model.LibraryInnerView.Collections,
                    -> Unit
                }
            }
    }
}

@Composable
private fun LibraryGridInnerView(
    session: Session,
    library: Library,
    onItemSelected: (MediaCardUi) -> Unit,
    onPlayItem: (String, Long) -> Unit,
    onShuffleQueue: (List<String>) -> Unit,
    onShuffleMessage: suspend (String) -> Unit,
    controlsResetKey: Any,
    bottomContentPadding: Dp,
    topBarContent: @Composable (@Composable RowScope.() -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryBrowseViewModel =
        koinViewModel(
            key = "library-tab-${library.id}-${session.serverId}-${session.userId}",
            parameters = { parametersOf(session, library.id, library.collectionType) },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val emptyMessage = stringResource(Res.string.library_shuffle_empty)
    val unavailableMessage = stringResource(Res.string.library_shuffle_unavailable)
    val currentOnShuffleQueue by rememberUpdatedState(onShuffleQueue)
    val currentOnShuffleMessage by rememberUpdatedState(onShuffleMessage)

    OnResumeEffect(viewModel::refreshSilently)

    LaunchedEffect(viewModel, emptyMessage, unavailableMessage) {
        viewModel.events.collect { event ->
            when (event) {
                is LibraryBrowseEvent.LaunchShuffle -> currentOnShuffleQueue(event.itemIds)
                LibraryBrowseEvent.ShuffleEmpty -> currentOnShuffleMessage(emptyMessage)
                LibraryBrowseEvent.ShuffleUnavailable -> currentOnShuffleMessage(unavailableMessage)
            }
        }
    }

    LibraryBrowseGridContent(
        session = session,
        state = state,
        gridState = gridState,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        onSetSort = viewModel::setSort,
        onSetFilters = viewModel::setFilters,
        onShuffleAll = viewModel::shuffleAll,
        onItemSelected = onItemSelected,
        onPlayItem = onPlayItem,
        controlsResetKey = controlsResetKey,
        bottomContentPadding = Dimensions.screenPadding + bottomContentPadding,
        topBarContent = topBarContent,
        modifier = modifier,
    )
}

@Composable
private fun LibraryTabCenteredMessage(
    topContentPadding: Dp,
    bottomContentPadding: Dp,
    content: @Composable () -> Unit,
) {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(
                    start = horizontalContentPadding.start,
                    top = topContentPadding,
                    end = horizontalContentPadding.end,
                    bottom = Dimensions.screenPadding + bottomContentPadding,
                ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun LibraryTabLoading() {
    LoadingIndicator()
}

@Composable
private fun LibraryTabError(onRetry: () -> Unit) {
    RetryableError(
        message = stringResource(Res.string.libraries_error),
        retryable = true,
        onRetry = onRetry,
    )
}
