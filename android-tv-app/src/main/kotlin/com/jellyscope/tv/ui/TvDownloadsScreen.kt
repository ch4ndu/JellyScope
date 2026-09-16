// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import com.jellyscope.core.domain.model.DOWNLOAD_BYTES_PER_GB
import com.jellyscope.core.domain.model.DownloadFailure
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.wholeGbDownloadQuotaBytes
import com.jellyscope.tv.R
import com.jellyscope.tv.ui.focus.TvFocusTargetKind
import com.jellyscope.tv.ui.focus.TvFocusTrapEffect
import com.jellyscope.tv.ui.focus.rememberChildRequester
import com.jellyscope.tv.ui.focus.rememberTvFocusScopeNode
import com.jellyscope.tv.ui.focus.tvFocusScope
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.OnResumeEffect
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.downloads.DownloadsSection
import com.jellyscope.ui.screen.downloads.DownloadsSectionKind
import com.jellyscope.ui.screen.downloads.DownloadsUiError
import com.jellyscope.ui.screen.downloads.DownloadsUiState
import com.jellyscope.ui.screen.downloads.DownloadsViewModel
import com.jellyscope.ui.theme.LocalJellyfinPalette
import com.jellyscope.ui.component.AdaptiveSpinner as TvSpinner
import com.jellyscope.ui.component.DetailBodyStyle as TvBodyStyle
import com.jellyscope.ui.component.DetailSecondaryStyle as TvSecondaryStyle
import com.jellyscope.ui.component.DetailText as TvText
import com.jellyscope.ui.component.DetailTitleStyle as TvTitleStyle
import com.jellyscope.ui.component.FocusableBox as TvFocusableBox

internal const val TV_DOWNLOADS_MANAGE_FOCUS_KEY = "downloads:manage"
internal const val TV_DOWNLOADS_RESUME_ALL_FOCUS_KEY = "downloads:resume-all"

internal fun tvDownloadFocusKey(downloadId: DownloadId): String = "download:${downloadId.value}"

internal fun tvDownloadSemanticFocusKeys(
    sections: List<DownloadsSection>,
    includeResumeAll: Boolean = false,
): List<String> =
    buildList {
        add(TV_DOWNLOADS_MANAGE_FOCUS_KEY)
        if (includeResumeAll) add(TV_DOWNLOADS_RESUME_ALL_FOCUS_KEY)
        sections.forEach { section ->
            section.records.forEach { record -> add(tvDownloadFocusKey(record.downloadId)) }
        }
    }

internal fun tvDownloadLazySlotIndex(
    semanticIndex: Int,
    sectionSizes: List<Int>,
    includeResumeAll: Boolean = false,
): Int {
    if (semanticIndex <= 0) return 0
    if (includeResumeAll && semanticIndex == 1) return 1
    val resumeAllOffset = if (includeResumeAll) 1 else 0
    var remainingRecordIndex = semanticIndex - 1 - resumeAllOffset
    var slot = 1 + resumeAllOffset
    sectionSizes.forEach { sectionSize ->
        if (remainingRecordIndex < sectionSize) {
            return slot + 1 + remainingRecordIndex
        }
        remainingRecordIndex -= sectionSize
        slot += sectionSize + 1
    }
    return slot
}

private val TV_DOWNLOAD_QUOTA_PRESETS_GB = listOf(1L, 2L, 5L, 10L, 20L, 50L, 100L)

internal fun tvDownloadQuotaOptions(
    currentQuotaBytes: Long?,
    maximumQuotaBytes: Long,
): List<Long> {
    val maximumWholeGb = maximumQuotaBytes / DOWNLOAD_BYTES_PER_GB
    val currentWholeGb = currentQuotaBytes?.div(DOWNLOAD_BYTES_PER_GB)
    val wholeGbOptions =
        buildSet {
            TV_DOWNLOAD_QUOTA_PRESETS_GB
                .filterTo(this) { preset -> preset <= maximumWholeGb }
            maximumWholeGb.takeIf { maximum -> maximum >= 1L }?.let(::add)
            currentWholeGb?.takeIf { current -> current >= 1L }?.let(::add)
        }
    return wholeGbOptions
        .sorted()
        .mapNotNull(::wholeGbDownloadQuotaBytes)
}

internal fun tvDownloadCustomQuotaBytes(
    input: String,
    maximumQuotaBytes: Long,
): Long? {
    val wholeGb = input.trim().toLongOrNull() ?: return null
    return wholeGbDownloadQuotaBytes(wholeGb)
        ?.takeIf { quotaBytes -> quotaBytes <= maximumQuotaBytes }
}

@Composable
internal fun TvDownloadsScreen(
    session: Session,
    onBack: () -> Unit,
    onOpenDownloadDetail: (DownloadId) -> Unit,
    viewModel: DownloadsViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var quotaDialogVisible by rememberSaveable(session.serverId, session.userId) { mutableStateOf(false) }
    var returnFocusKey by rememberSaveable(session.serverId, session.userId) { mutableStateOf<String?>(null) }

    OnResumeEffect {
        viewModel.refresh()
        viewModel.wakeQueueOnScreenResume()
    }

    TvDownloadsContent(
        session = session,
        state = state,
        modalVisible = quotaDialogVisible,
        returnFocusKey = returnFocusKey,
        onReturnFocusConsumed = { returnFocusKey = null },
        onBack = onBack,
        onManageAllocation = { quotaDialogVisible = true },
        onResumePausedDownloads = viewModel::resumePausedDownloads,
        onOpenDownloadDetail = onOpenDownloadDetail,
        viewModel = viewModel,
    )

    if (quotaDialogVisible) {
        TvDownloadQuotaDialog(
            currentQuotaBytes = state.settings?.quotaBytes,
            maximumQuotaBytes = state.usage?.maximumConfigurableQuotaBytes,
            onDismiss = {
                quotaDialogVisible = false
                returnFocusKey = TV_DOWNLOADS_MANAGE_FOCUS_KEY
            },
            onSelected = { quotaBytes ->
                quotaDialogVisible = false
                returnFocusKey = TV_DOWNLOADS_MANAGE_FOCUS_KEY
                viewModel.configureQuota(quotaBytes)
            },
        )
    }
}

@Composable
private fun TvDownloadsContent(
    session: Session,
    state: DownloadsUiState,
    modalVisible: Boolean,
    returnFocusKey: String?,
    onReturnFocusConsumed: () -> Unit,
    onBack: () -> Unit,
    onManageAllocation: () -> Unit,
    onResumePausedDownloads: () -> Unit,
    onOpenDownloadDetail: (DownloadId) -> Unit,
    viewModel: DownloadsViewModel,
) {
    val hostedRail = LocalTvHostedRailController.current
    val listState = rememberLazyGridState()
    val loadingFocusRequester = remember { FocusRequester() }
    val focusScope = rememberTvFocusScopeNode(listOf("downloads", "list"))
    val manageFocusRequester = focusScope.rememberChildRequester(TV_DOWNLOADS_MANAGE_FOCUS_KEY)
    val sections = state.sections
    val hasPausedDownloads = state.records.any { record -> record.state == DownloadState.Paused }
    val resumeAllFocusRequester = focusScope.rememberChildRequester(TV_DOWNLOADS_RESUME_ALL_FOCUS_KEY)
    val sectionSizes = remember(sections) { sections.map { section -> section.records.size } }
    val semanticKeys = remember(sections, hasPausedDownloads) { tvDownloadSemanticFocusKeys(sections, hasPausedDownloads) }
    val recordFocusKeys =
        remember(sections) {
            sections.flatMap { section -> section.records.map { record -> tvDownloadFocusKey(record.downloadId) } }
        }
    val semanticIndexByFocusKey =
        remember(semanticKeys, recordFocusKeys) {
            recordFocusKeys.associateWith(semanticKeys::indexOf)
        }
    val loading = state.isLoading && state.records.isEmpty() && state.usage == null
    val allocationNeedsAttention = state.settings?.quotaBytes == null || state.usage?.overAllocation == true
    val defaultFocusKeys =
        remember(recordFocusKeys, allocationNeedsAttention, hasPausedDownloads) {
            when {
                hasPausedDownloads ->
                    listOf(TV_DOWNLOADS_RESUME_ALL_FOCUS_KEY) + recordFocusKeys + TV_DOWNLOADS_MANAGE_FOCUS_KEY
                allocationNeedsAttention -> listOf(TV_DOWNLOADS_MANAGE_FOCUS_KEY) + recordFocusKeys
                recordFocusKeys.isNotEmpty() -> recordFocusKeys + TV_DOWNLOADS_MANAGE_FOCUS_KEY
                else -> listOf(TV_DOWNLOADS_MANAGE_FOCUS_KEY)
            }
        }
    val restoreRequest = focusScope.restoreRequest()
    val requestInitialContentFocus = !hostedRail.contentAutofocusSuppressed
    val registrationKey = hostedRail.contentRegistrationKey
    val currentLoading by rememberUpdatedState(loading)
    val currentDefaultFocusKeys by rememberUpdatedState(defaultFocusKeys)
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var initialContentFocusCompleted by rememberSaveable { mutableStateOf(false) }
    val focusContent =
        remember(focusScope, loadingFocusRequester) {
            {
                val focused =
                    if (currentLoading) {
                        loadingFocusRequester.requestFocusSafely()
                    } else {
                        focusScope.requestEntry(currentDefaultFocusKeys)
                    }
                if (focused && !currentLoading) {
                    initialContentFocusCompleted = true
                }
                focused
            }
        }

    LaunchedEffect(hostedRail.visible, registrationKey, focusContent) {
        hostedRail.setContentRightFocusRequester(registrationKey, null)
        hostedRail.setContentRightFocusAction(registrationKey, focusContent)
    }

    LaunchedEffect(
        restoreRequest,
        loading,
        semanticKeys,
        requestInitialContentFocus,
        initialContentFocusCompleted,
    ) {
        when {
            loading && requestInitialContentFocus ->
                requestTvFocusWithRetry { loadingFocusRequester.requestFocusSafely() }
            !loading && restoreRequest != null -> {
                val restored =
                    focusScope.restoreFocus(
                        request = restoreRequest,
                        semanticKeys = semanticKeys,
                        lazySlotIndex = { semanticIndex ->
                            tvDownloadLazySlotIndex(semanticIndex, sectionSizes, hasPausedDownloads)
                        },
                        revealCentered = { slot ->
                            listState.scrollToItem(slot)
                            val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { item -> item.index == slot }
                            if (itemInfo != null) {
                                val viewportCenter =
                                    (listState.layoutInfo.viewportStartOffset + listState.layoutInfo.viewportEndOffset) / 2
                                listState.scrollToItem(slot, -(viewportCenter - itemInfo.size.height / 2))
                            }
                        },
                    )
                if (restored) {
                    initialContentFocusCompleted = true
                }
            }
            !loading && requestInitialContentFocus && !initialContentFocusCompleted -> {
                initialContentFocusCompleted =
                    requestTvFocusWithRetry { focusScope.requestEntry(defaultFocusKeys) }
            }
        }
    }

    LaunchedEffect(returnFocusKey, modalVisible, loading, semanticKeys) {
        val requestedKey = returnFocusKey ?: return@LaunchedEffect
        if (!modalVisible && !loading) {
            val fallbackKeys = listOf(requestedKey) + defaultFocusKeys.filterNot { key -> key == requestedKey }
            requestTvFocusWithRetry { focusScope.requestEntry(fallbackKeys) }
            onReturnFocusConsumed()
        }
    }

    LaunchedEffect(focusedKey, modalVisible, loading, semanticKeys, hostedRail.railHasFocus) {
        val previousKey = focusedKey ?: return@LaunchedEffect
        if (!modalVisible && !loading && !hostedRail.railHasFocus && previousKey !in semanticKeys) {
            requestTvFocusWithRetry { focusScope.requestEntry(defaultFocusKeys) }
        }
    }

    BackHandler(enabled = !hostedRail.railHasFocus && !modalVisible) {
        if (!hostedRail.requestRailFocus()) {
            onBack()
        }
    }

    if (loading) {
        TvLoadingFocusPark(
            focusRequester = loadingFocusRequester,
            onRequestRailFocus = hostedRail::requestRailFocus,
            requestFocusOnAttach = requestInitialContentFocus,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = if (hostedRail.visible) TvDimens.drawerContentStartPadding else TvDimens.overscanHorizontal,
                        top = TvDimens.overscanVertical,
                        end = TvDimens.overscanHorizontal,
                        bottom = TvDimens.overscanVertical,
                    ),
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(TvDimens.posterWidth.tileScaled()),
        state = listState,
        modifier =
            Modifier
                .fillMaxSize()
                .tvFocusScope(focusScope) { defaultFocusKeys },
        contentPadding =
            PaddingValues(
                start = if (hostedRail.visible) TvDimens.drawerContentStartPadding else TvDimens.overscanHorizontal,
                top = TvDimens.overscanVertical,
                end = TvDimens.overscanHorizontal,
                bottom = TvDimens.overscanVertical,
            ),
        verticalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
        horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
    ) {
        item(
            key = TV_DOWNLOADS_MANAGE_FOCUS_KEY,
            span = { GridItemSpan(maxLineSpan) },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(TvDimens.itemGap)) {
                TvText(
                    text = stringResource(R.string.tv_downloads_title),
                    style = TvScreenHeaderStyle,
                    maxLines = 1,
                )
                TvDownloadsUsageCard(
                    state = state,
                    focusRequester = manageFocusRequester,
                    onFocusChanged = {
                        initialContentFocusCompleted = true
                        focusedKey = TV_DOWNLOADS_MANAGE_FOCUS_KEY
                        focusScope.onChildFocused(
                            key = TV_DOWNLOADS_MANAGE_FOCUS_KEY,
                            kind = TvFocusTargetKind.Action,
                            semanticIndex = 0,
                        )
                    },
                    onRequestRailFocus = hostedRail::requestRailFocus,
                    onClick = onManageAllocation,
                )
            }
        }
        if (hasPausedDownloads) {
            item(
                key = TV_DOWNLOADS_RESUME_ALL_FOCUS_KEY,
                span = { GridItemSpan(maxLineSpan) },
            ) {
                TvDownloadsInterruptedCard(
                    busy = state.isBulkResumeInFlight,
                    focusRequester = resumeAllFocusRequester,
                    onFocusChanged = {
                        initialContentFocusCompleted = true
                        focusedKey = TV_DOWNLOADS_RESUME_ALL_FOCUS_KEY
                        focusScope.onChildFocused(
                            key = TV_DOWNLOADS_RESUME_ALL_FOCUS_KEY,
                            kind = TvFocusTargetKind.Action,
                            semanticIndex = 1,
                        )
                    },
                    onRequestRailFocus = hostedRail::requestRailFocus,
                    onClick = onResumePausedDownloads,
                )
            }
        }
        if (sections.isEmpty()) {
            item(
                key = "downloads:empty",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(TvDimens.settingsValueGap)) {
                    TvText(
                        text = stringResource(R.string.tv_downloads_empty),
                        style = TvTitleStyle,
                        maxLines = 2,
                    )
                    TvText(
                        text = stringResource(R.string.tv_downloads_empty_guidance),
                        style = TvBodyStyle,
                        color = LocalJellyfinPalette.current.textSecondary,
                        maxLines = 3,
                    )
                }
            }
        } else {
            sections.forEach { section ->
                item(
                    key = "downloads:section:${section.kind.name}",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    TvText(
                        text = stringResource(tvDownloadSectionResource(section.kind)),
                        style = TvTitleStyle,
                        maxLines = 1,
                    )
                }
                itemsIndexed(
                    items = section.records,
                    key = { _, record -> record.downloadId.value },
                ) { _, record ->
                    val focusKey = tvDownloadFocusKey(record.downloadId)
                    val focusRequester = focusScope.rememberChildRequester(focusKey)
                    TvDownloadGridCard(
                        session = session,
                        record = record,
                        card = state.detailsByDownloadId.getValue(record.downloadId).card,
                        viewModel = viewModel,
                        focusRequester = focusRequester,
                        onFocusChanged = {
                            initialContentFocusCompleted = true
                            focusedKey = focusKey
                            focusScope.onChildFocused(
                                key = focusKey,
                                kind = TvFocusTargetKind.Item,
                                semanticIndex = semanticIndexByFocusKey.getValue(focusKey),
                            )
                        },
                        onRequestRailFocus = {
                            val column =
                                listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.key == record.downloadId.value }
                                    ?.column
                            column == 0 && hostedRail.requestRailFocus()
                        },
                        onClick = { onOpenDownloadDetail(record.downloadId) },
                    )
                }
            }
        }
        state.error?.let { error ->
            item(
                key = "downloads:error",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                TvText(
                    text = stringResource(tvDownloadsErrorResource(error)),
                    color = LocalJellyfinPalette.current.error,
                    style = TvBodyStyle,
                    maxLines = 3,
                )
            }
        }
    }
}

@Composable
private fun TvDownloadsUsageCard(
    state: DownloadsUiState,
    focusRequester: FocusRequester,
    onFocusChanged: () -> Unit,
    onRequestRailFocus: () -> Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalJellyfinPalette.current
    TvFocusableBox(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { focusState -> if (focusState.isFocused) onFocusChanged() }
                .onPreviewKeyEvent { event -> event.requestsRail(onRequestRailFocus) },
        contentDescription = stringResource(R.string.tv_downloads_manage_content_description),
        focusedScale = 1.02f,
        backgroundColor = palette.surfaceNavy,
        focusedBackgroundColor = palette.surfaceRaised,
        focusedBorderColor = palette.cyan,
        focusGlowColor = palette.cyan.copy(alpha = TvDimens.SETTINGS_FOCUS_GLOW_ALPHA),
        focusGlowElevation = TvDimens.settingsPanelFocusGlow,
        contentPadding = PaddingValues(TvDimens.settingsDialogContentPadding),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(TvDimens.settingsValueGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TvDimens.settingsRowIconGap),
            ) {
                Icon(
                    imageVector = TvIcons.Download,
                    contentDescription = null,
                    tint = palette.cyan,
                    modifier = Modifier.size(TvDimens.settingsRowIconSize),
                )
                TvText(
                    text = stringResource(R.string.tv_downloads_usage),
                    modifier = Modifier.weight(1f),
                    style = TvTitleStyle,
                    maxLines = 1,
                )
                if (state.isRefreshingUsage) {
                    TvSpinner(modifier = Modifier.size(TvDimens.settingsRowIconSize))
                } else {
                    TvText(
                        text = stringResource(R.string.tv_downloads_manage),
                        style = TvBodyStyle.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.cyan,
                        maxLines = 1,
                    )
                }
            }
            val usage = state.usage
            if (usage == null) {
                TvText(
                    text = stringResource(R.string.tv_downloads_usage_loading),
                    color = palette.textSecondary,
                    style = TvBodyStyle,
                    maxLines = 2,
                )
            } else {
                TvDownloadUsageRow(
                    label = stringResource(R.string.tv_downloads_allocation),
                    value =
                        usage.quotaBytes?.let { bytes -> tvDownloadBytes(bytes) }
                            ?: stringResource(R.string.tv_downloads_not_configured),
                )
                TvDownloadUsageRow(
                    label = stringResource(R.string.tv_downloads_total_stored),
                    value = tvDownloadBytes(usage.physicalBytes),
                )
                TvDownloadUsageRow(
                    label = stringResource(R.string.tv_downloads_current_account_stored),
                    value = tvDownloadBytes(usage.currentAccountPhysicalBytes),
                )
                TvDownloadUsageRow(
                    label = stringResource(R.string.tv_downloads_other_accounts_stored),
                    value = tvDownloadBytes(usage.otherAccountsPhysicalBytes),
                )
                TvDownloadUsageRow(
                    label = stringResource(R.string.tv_downloads_reserved),
                    value = tvDownloadBytes(usage.outstandingReservationBytes),
                )
                TvDownloadUsageRow(
                    label = stringResource(R.string.tv_downloads_remaining),
                    value =
                        usage.remainingQuotaBytes?.let { bytes -> tvDownloadBytes(bytes) }
                            ?: stringResource(R.string.tv_downloads_not_configured),
                )
                TvDownloadUsageRow(
                    label = stringResource(R.string.tv_downloads_device_free),
                    value = tvDownloadBytes(usage.deviceAvailableBytes),
                )
                if (usage.overAllocation) {
                    TvText(
                        text = stringResource(R.string.tv_downloads_over_allocation),
                        color = palette.error,
                        style = TvBodyStyle,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvDownloadUsageRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        TvText(
            text = label,
            modifier = Modifier.weight(1f),
            style = TvSecondaryStyle,
            color = LocalJellyfinPalette.current.textSecondary,
            maxLines = 1,
        )
        TvText(text = value, style = TvBodyStyle, maxLines = 1)
    }
}

@Composable
private fun TvDownloadsInterruptedCard(
    busy: Boolean,
    focusRequester: FocusRequester,
    onFocusChanged: () -> Unit,
    onRequestRailFocus: () -> Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalJellyfinPalette.current
    TvFocusableBox(
        onClick = onClick,
        enabled = !busy,
        focusableWhenDisabled = true,
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { focusState -> if (focusState.isFocused) onFocusChanged() }
                .onPreviewKeyEvent { event -> event.requestsRail(onRequestRailFocus) },
        contentDescription = stringResource(R.string.tv_downloads_resume_all_content_description),
        focusedScale = 1.02f,
        backgroundColor = palette.surfaceNavy,
        focusedBackgroundColor = palette.surfaceRaised,
        focusedBorderColor = palette.cyan,
        focusGlowColor = palette.cyan.copy(alpha = TvDimens.SETTINGS_FOCUS_GLOW_ALPHA),
        focusGlowElevation = TvDimens.settingsPanelFocusGlow,
        contentPadding = PaddingValues(TvDimens.settingsDialogContentPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TvDimens.settingsRowIconGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvText(
                text = stringResource(R.string.tv_downloads_interrupted),
                modifier = Modifier.weight(1f),
                style = TvTitleStyle,
                maxLines = 2,
            )
            if (busy) {
                TvSpinner(modifier = Modifier.size(TvDimens.settingsRowIconSize))
            } else {
                TvText(
                    text = stringResource(R.string.tv_downloads_resume_all),
                    style = TvBodyStyle.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.cyan,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TvDownloadGridCard(
    session: Session,
    record: DownloadRecord,
    card: com.jellyscope.ui.component.MediaCardUi,
    viewModel: DownloadsViewModel,
    focusRequester: FocusRequester,
    onFocusChanged: () -> Unit,
    onRequestRailFocus: () -> Boolean,
    onClick: () -> Unit,
) {
    TvMediaCard(
        session = session,
        item = card,
        wide = false,
        onFocused = onFocusChanged,
        onClick = onClick,
        focusRequester = focusRequester,
        focusChildModifier = Modifier.onPreviewKeyEvent { event -> event.requestsRail(onRequestRailFocus) },
        artwork = {
            TvDownloadArtworkImage(
                session = session,
                record = record,
                role = com.jellyscope.core.domain.model.OfflineArtworkRole.Poster,
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}

@Composable
private fun TvDownloadQuotaDialog(
    currentQuotaBytes: Long?,
    maximumQuotaBytes: Long?,
    onDismiss: () -> Unit,
    onSelected: (Long) -> Unit,
) {
    TvFocusTrapEffect()
    val safeMaximum = maximumQuotaBytes
    if (safeMaximum == null) {
        TvSettingsDetailDialog(
            title = stringResource(R.string.tv_downloads_quota_dialog_title),
            detail = stringResource(R.string.tv_downloads_usage_loading),
            onDismiss = onDismiss,
        )
        return
    }

    val presetBytes =
        remember(currentQuotaBytes, safeMaximum) {
            tvDownloadQuotaOptions(currentQuotaBytes, safeMaximum)
        }
    var customVisible by remember(currentQuotaBytes, safeMaximum) { mutableStateOf(false) }
    var returnFocusChoice by
        remember(currentQuotaBytes, safeMaximum) {
            mutableStateOf<TvDownloadQuotaChoice?>(null)
        }
    if (presetBytes.isEmpty()) {
        TvSettingsDetailDialog(
            title = stringResource(R.string.tv_downloads_quota_dialog_title),
            detail = stringResource(R.string.tv_downloads_quota_unavailable),
            onDismiss = onDismiss,
        )
    } else if (customVisible) {
        TvDownloadCustomQuotaDialog(
            maximumQuotaBytes = safeMaximum,
            onDismiss = { customVisible = false },
            onSelected = onSelected,
        )
    } else {
        val selectedChoice =
            currentQuotaBytes
                ?.takeIf { quotaBytes -> quotaBytes in presetBytes }
                ?.let(TvDownloadQuotaChoice::Preset)
                ?: TvDownloadQuotaChoice.Unconfigured
        val choices: List<TvDownloadQuotaChoice> =
            buildList {
                presetBytes.forEach { quotaBytes -> add(TvDownloadQuotaChoice.Preset(quotaBytes)) }
                add(TvDownloadQuotaChoice.Custom)
            }
        val customEnabled = safeMaximum >= DOWNLOAD_BYTES_PER_GB
        TvSettingsChoiceDialog(
            title = stringResource(R.string.tv_downloads_quota_dialog_title),
            selected = selectedChoice,
            options =
                choices.map { choice ->
                    when (choice) {
                        is TvDownloadQuotaChoice.Preset ->
                            TvSettingsDialogOption(
                                value = choice,
                                label =
                                    stringResource(
                                        R.string.tv_downloads_quota_option,
                                        choice.quotaBytes / DOWNLOAD_BYTES_PER_GB,
                                    ),
                            )
                        TvDownloadQuotaChoice.Custom ->
                            TvSettingsDialogOption(
                                value = choice,
                                label = stringResource(R.string.tv_downloads_quota_custom),
                                enabled = customEnabled,
                                unavailableReason = stringResource(R.string.tv_downloads_quota_unavailable),
                            )
                        TvDownloadQuotaChoice.Unconfigured ->
                            error("Unconfigured is not a selectable quota choice.")
                    }
                },
            onSelected = { choice ->
                returnFocusChoice = choice
                when (choice) {
                    is TvDownloadQuotaChoice.Preset -> onSelected(choice.quotaBytes)
                    TvDownloadQuotaChoice.Custom -> customVisible = true
                    TvDownloadQuotaChoice.Unconfigured -> Unit
                }
            },
            onDismiss = onDismiss,
            description = stringResource(R.string.tv_downloads_quota_dialog_description),
            initialFocusValue = returnFocusChoice ?: selectedChoice,
        )
    }
}

@Composable
private fun TvDownloadCustomQuotaDialog(
    maximumQuotaBytes: Long,
    onDismiss: () -> Unit,
    onSelected: (Long) -> Unit,
) {
    val maximumWholeGb = maximumQuotaBytes / DOWNLOAD_BYTES_PER_GB
    var wholeGbInput by remember(maximumQuotaBytes) { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var exitEditingRequests by remember { mutableStateOf(0) }
    val inputRequester = remember { FocusRequester() }
    val applyRequester = remember { FocusRequester() }
    val quotaBytes = tvDownloadCustomQuotaBytes(wholeGbInput, maximumQuotaBytes)

    LaunchedEffect(inputRequester) {
        requestTvFocusWithRetry { inputRequester.requestFocusSafely() }
    }
    TvSettingsDialogFrame(
        title = stringResource(R.string.tv_downloads_quota_custom_title),
        onDismiss = {
            if (editing) {
                exitEditingRequests += 1
            } else {
                onDismiss()
            }
        },
    ) {
        TvText(
            text = stringResource(R.string.tv_downloads_quota_custom_range, maximumWholeGb),
            color = LocalJellyfinPalette.current.textSecondary,
            style = TvBodyStyle,
            maxLines = 2,
        )
        TvInputField(
            value = wholeGbInput,
            onValueChange = { value -> wholeGbInput = value.filter(Char::isDigit) },
            label = stringResource(R.string.tv_downloads_quota_custom_label),
            modifier =
                Modifier
                    .focusRequester(inputRequester)
                    .onPreviewKeyEvent { event ->
                        event.type == KeyEventType.KeyDown &&
                            !editing &&
                            event.key in
                            setOf(
                                Key.DirectionLeft,
                                Key.DirectionRight,
                                Key.DirectionUp,
                            )
                    },
            keyboardType = KeyboardType.Number,
            onImeAction = { quotaBytes?.let(onSelected) },
            onEditingChange = { isEditing -> editing = isEditing },
            exitEditingRequests = exitEditingRequests,
        )
        if (wholeGbInput.isNotBlank() && quotaBytes == null) {
            TvText(
                text = stringResource(R.string.tv_downloads_quota_custom_invalid, maximumWholeGb),
                color = LocalJellyfinPalette.current.error,
                style = TvBodyStyle,
                maxLines = 2,
            )
        }
        TvSettingsDialogAction(
            text = stringResource(R.string.tv_downloads_quota_custom_apply),
            focusRequester = applyRequester,
            onClick = { quotaBytes?.let(onSelected) },
            enabled = quotaBytes != null,
            modifier =
                Modifier.onPreviewKeyEvent { event ->
                    event.type == KeyEventType.KeyDown &&
                        event.key in
                        setOf(
                            Key.DirectionLeft,
                            Key.DirectionRight,
                            Key.DirectionDown,
                        )
                },
        )
    }
}

private sealed interface TvDownloadQuotaChoice {
    data class Preset(
        val quotaBytes: Long,
    ) : TvDownloadQuotaChoice

    data object Custom : TvDownloadQuotaChoice

    data object Unconfigured : TvDownloadQuotaChoice
}

private fun String.toDownloadFocusKeyOrNull(): String? = runCatching { tvDownloadFocusKey(DownloadId(this)) }.getOrNull()

private fun KeyEvent.requestsRail(onRequestRailFocus: () -> Boolean): Boolean =
    type == KeyEventType.KeyDown && key == Key.DirectionLeft && onRequestRailFocus()

private fun KeyEvent.isPlayKeyDown(): Boolean = type == KeyEventType.KeyDown && (key == Key.MediaPlay || key == Key.MediaPlayPause)

private fun tvDownloadExpectedBytes(record: DownloadRecord): Long =
    maxOf(
        record.request.expectedSourceBytes ?: 0L,
        record.reservationBytes,
        record.request.admissionEstimateBytes,
        record.physicalBytes,
    )

@Composable
internal fun tvDownloadBytes(bytes: Long): String =
    when {
        bytes >= DOWNLOAD_BYTES_PER_GB ->
            stringResource(R.string.tv_downloads_bytes_gb, bytes.toDouble() / DOWNLOAD_BYTES_PER_GB)
        bytes >= DOWNLOAD_BYTES_PER_MB ->
            stringResource(R.string.tv_downloads_bytes_mb, bytes.toDouble() / DOWNLOAD_BYTES_PER_MB)
        bytes >= DOWNLOAD_BYTES_PER_KB ->
            stringResource(R.string.tv_downloads_bytes_kb, bytes.toDouble() / DOWNLOAD_BYTES_PER_KB)
        else -> stringResource(R.string.tv_downloads_bytes_b, bytes)
    }

internal fun tvDownloadStateResource(state: DownloadState): Int =
    when (state) {
        DownloadState.NotDownloaded -> R.string.tv_downloads_state_not_downloaded
        DownloadState.Queued -> R.string.tv_downloads_state_queued
        DownloadState.Downloading -> R.string.tv_downloads_state_downloading
        DownloadState.Paused -> R.string.tv_downloads_state_paused
        DownloadState.BlockedByQuota -> R.string.tv_downloads_state_blocked
        DownloadState.Finalizing -> R.string.tv_downloads_state_finalizing
        DownloadState.Completed -> R.string.tv_downloads_state_completed
        DownloadState.Failed -> R.string.tv_downloads_state_failed
    }

internal fun tvDownloadFailureResource(failure: DownloadFailure): Int =
    when (failure) {
        DownloadFailure.PermissionDenied -> R.string.tv_downloads_failure_permission
        DownloadFailure.SizeUnavailable -> R.string.tv_downloads_failure_size
        DownloadFailure.Network,
        DownloadFailure.ServerUnavailable,
        -> R.string.tv_downloads_failure_network
        DownloadFailure.SourceChanged -> R.string.tv_downloads_failure_source
        DownloadFailure.UnsupportedArtifact -> R.string.tv_downloads_failure_unsupported
        DownloadFailure.QuotaExceeded -> R.string.tv_downloads_failure_quota
        DownloadFailure.DeviceStorageLow -> R.string.tv_downloads_failure_storage
        DownloadFailure.MissingArtifact -> R.string.tv_downloads_failure_missing
        DownloadFailure.ArtifactInUse -> R.string.tv_downloads_artifact_in_use
    }

internal fun tvDownloadsErrorResource(error: DownloadsUiError): Int =
    when (error) {
        DownloadsUiError.LoadFailed -> R.string.tv_downloads_error
        DownloadsUiError.CommandRejected -> R.string.tv_downloads_command_error
        DownloadsUiError.QuotaRejected -> R.string.tv_downloads_quota_error
        DownloadsUiError.ArtifactInUse -> R.string.tv_downloads_artifact_in_use
    }

private fun tvDownloadSectionResource(kind: DownloadsSectionKind): Int =
    when (kind) {
        DownloadsSectionKind.Completed -> R.string.tv_downloads_section_completed
        DownloadsSectionKind.Active -> R.string.tv_downloads_section_active
        DownloadsSectionKind.Queued -> R.string.tv_downloads_section_queued
        DownloadsSectionKind.Paused -> R.string.tv_downloads_section_paused
        DownloadsSectionKind.Failed -> R.string.tv_downloads_section_failed
    }

private const val DOWNLOAD_BYTES_PER_KB = 1_000L
private const val DOWNLOAD_BYTES_PER_MB = DOWNLOAD_BYTES_PER_KB * DOWNLOAD_BYTES_PER_KB
