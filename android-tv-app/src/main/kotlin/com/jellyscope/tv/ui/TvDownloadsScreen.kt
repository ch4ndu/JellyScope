// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.jellyscope.core.domain.model.DOWNLOAD_BYTES_PER_GIB
import com.jellyscope.core.domain.model.DownloadFailure
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.wholeGibDownloadQuotaBytes
import com.jellyscope.tv.R
import com.jellyscope.tv.ui.focus.TvFocusTargetKind
import com.jellyscope.tv.ui.focus.TvFocusTrapEffect
import com.jellyscope.tv.ui.focus.rememberChildRequester
import com.jellyscope.tv.ui.focus.rememberTvFocusScopeNode
import com.jellyscope.tv.ui.focus.tvFocusScope
import com.jellyscope.ui.component.OnResumeEffect
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.downloads.DownloadsSection
import com.jellyscope.ui.screen.downloads.DownloadsSectionKind
import com.jellyscope.ui.screen.downloads.DownloadsUiError
import com.jellyscope.ui.screen.downloads.DownloadsUiState
import com.jellyscope.ui.screen.downloads.DownloadsViewModel
import com.jellyscope.ui.theme.LocalJellyfinPalette
import com.jellyscope.ui.component.AdaptiveProgressBar as TvProgressBar
import com.jellyscope.ui.component.AdaptiveSpinner as TvSpinner
import com.jellyscope.ui.component.DetailBodyStyle as TvBodyStyle
import com.jellyscope.ui.component.DetailSecondaryStyle as TvSecondaryStyle
import com.jellyscope.ui.component.DetailText as TvText
import com.jellyscope.ui.component.DetailTitleStyle as TvTitleStyle
import com.jellyscope.ui.component.FocusableBox as TvFocusableBox

internal const val TV_DOWNLOADS_MANAGE_FOCUS_KEY = "downloads:manage"

internal fun tvDownloadFocusKey(downloadId: DownloadId): String = "download:${downloadId.value}"

internal fun tvDownloadSemanticFocusKeys(sections: List<DownloadsSection>): List<String> =
    buildList {
        add(TV_DOWNLOADS_MANAGE_FOCUS_KEY)
        sections.forEach { section ->
            section.records.forEach { record -> add(tvDownloadFocusKey(record.downloadId)) }
        }
    }

internal fun tvDownloadLazySlotIndex(
    semanticIndex: Int,
    sectionSizes: List<Int>,
): Int {
    if (semanticIndex <= 0) return 0
    var remainingRecordIndex = semanticIndex - 1
    var slot = 1
    sectionSizes.forEach { sectionSize ->
        if (remainingRecordIndex < sectionSize) {
            return slot + 1 + remainingRecordIndex
        }
        remainingRecordIndex -= sectionSize
        slot += sectionSize + 1
    }
    return slot
}

private val TV_DOWNLOAD_QUOTA_PRESETS_GIB = listOf(1L, 2L, 5L, 10L, 20L, 50L, 100L)

internal fun tvDownloadQuotaOptions(
    currentQuotaBytes: Long?,
    maximumQuotaBytes: Long,
): List<Long> {
    val maximumWholeGib = maximumQuotaBytes / DOWNLOAD_BYTES_PER_GIB
    val currentWholeGib = currentQuotaBytes?.div(DOWNLOAD_BYTES_PER_GIB)
    val wholeGibOptions =
        buildSet {
            TV_DOWNLOAD_QUOTA_PRESETS_GIB
                .filterTo(this) { preset -> preset <= maximumWholeGib }
            maximumWholeGib.takeIf { maximum -> maximum >= 1L }?.let(::add)
            currentWholeGib?.takeIf { current -> current >= 1L }?.let(::add)
        }
    return wholeGibOptions
        .sorted()
        .mapNotNull(::wholeGibDownloadQuotaBytes)
}

internal fun tvDownloadCustomQuotaBytes(
    input: String,
    maximumQuotaBytes: Long,
): Long? {
    val wholeGib = input.trim().toLongOrNull() ?: return null
    return wholeGibDownloadQuotaBytes(wholeGib)
        ?.takeIf { quotaBytes -> quotaBytes <= maximumQuotaBytes }
}

@Composable
internal fun TvDownloadsScreen(
    session: Session,
    onBack: () -> Unit,
    onPlayOffline: (DownloadRecord) -> Unit,
    viewModel: DownloadsViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var actionDownloadId by rememberSaveable(session.serverId, session.userId) { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<TvDownloadConfirmation?>(null) }
    var quotaDialogVisible by rememberSaveable(session.serverId, session.userId) { mutableStateOf(false) }
    var returnFocusKey by rememberSaveable(session.serverId, session.userId) { mutableStateOf<String?>(null) }

    OnResumeEffect(viewModel::refresh)

    val actionRecord = state.records.firstOrNull { record -> record.downloadId.value == actionDownloadId }
    LaunchedEffect(actionDownloadId, actionRecord) {
        if (actionDownloadId != null && actionRecord == null) {
            actionDownloadId = null
        }
    }
    val modalVisible = actionRecord != null || confirmation != null || quotaDialogVisible

    TvDownloadsContent(
        state = state,
        modalVisible = modalVisible,
        returnFocusKey = returnFocusKey,
        onReturnFocusConsumed = { returnFocusKey = null },
        onBack = onBack,
        onManageAllocation = { quotaDialogVisible = true },
        onOpenActions = { record -> actionDownloadId = record.downloadId.value },
        onPlayOffline = onPlayOffline,
    )

    actionRecord?.let { record ->
        TvDownloadActionsDialog(
            record = record,
            busy = state.inFlightDownloadId != null,
            artifactLeased = record.downloadId.value in state.leasedDownloadIds,
            onDismiss = {
                actionDownloadId = null
                returnFocusKey = tvDownloadFocusKey(record.downloadId)
            },
            onAction = { action ->
                actionDownloadId = null
                when (action) {
                    TvDownloadAction.Play -> onPlayOffline(record)
                    TvDownloadAction.Start -> {
                        returnFocusKey = tvDownloadFocusKey(record.downloadId)
                        viewModel.retryScheduling(record.downloadId.value)
                    }
                    TvDownloadAction.Pause -> {
                        returnFocusKey = tvDownloadFocusKey(record.downloadId)
                        viewModel.pause(record.downloadId.value)
                    }
                    TvDownloadAction.Resume -> {
                        returnFocusKey = tvDownloadFocusKey(record.downloadId)
                        viewModel.resume(record.downloadId.value)
                    }
                    TvDownloadAction.Retry -> {
                        returnFocusKey = tvDownloadFocusKey(record.downloadId)
                        viewModel.retry(record.downloadId.value)
                    }
                    TvDownloadAction.Cancel ->
                        confirmation =
                            TvDownloadConfirmation(
                                downloadId = record.downloadId.value,
                                title = record.request.snapshot.title,
                                kind = TvDownloadConfirmationKind.Cancel,
                            )
                    TvDownloadAction.Delete ->
                        confirmation =
                            TvDownloadConfirmation(
                                downloadId = record.downloadId.value,
                                title = record.request.snapshot.title,
                                kind = TvDownloadConfirmationKind.Delete,
                            )
                }
            },
        )
    }

    confirmation?.let { pending ->
        TvDownloadConfirmationDialog(
            confirmation = pending,
            onDismiss = {
                confirmation = null
                pending.downloadId.toDownloadFocusKeyOrNull()?.let { key -> returnFocusKey = key }
            },
            onConfirm = {
                confirmation = null
                pending.downloadId.toDownloadFocusKeyOrNull()?.let { key -> returnFocusKey = key }
                when (pending.kind) {
                    TvDownloadConfirmationKind.Cancel -> viewModel.cancel(pending.downloadId)
                    TvDownloadConfirmationKind.Delete -> viewModel.delete(pending.downloadId)
                }
            },
        )
    }

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
    state: DownloadsUiState,
    modalVisible: Boolean,
    returnFocusKey: String?,
    onReturnFocusConsumed: () -> Unit,
    onBack: () -> Unit,
    onManageAllocation: () -> Unit,
    onOpenActions: (DownloadRecord) -> Unit,
    onPlayOffline: (DownloadRecord) -> Unit,
) {
    val hostedRail = LocalTvHostedRailController.current
    val listState = rememberLazyListState()
    val loadingFocusRequester = remember { FocusRequester() }
    val focusScope = rememberTvFocusScopeNode(listOf("downloads", "list"))
    val manageFocusRequester = focusScope.rememberChildRequester(TV_DOWNLOADS_MANAGE_FOCUS_KEY)
    val sections = state.sections
    val sectionSizes = remember(sections) { sections.map { section -> section.records.size } }
    val semanticKeys = remember(sections) { tvDownloadSemanticFocusKeys(sections) }
    val recordFocusKeys = remember(semanticKeys) { semanticKeys.drop(1) }
    val semanticIndexByFocusKey =
        remember(recordFocusKeys) {
            recordFocusKeys
                .mapIndexed { index, key -> key to index + 1 }
                .toMap()
        }
    val loading = state.isLoading && state.records.isEmpty() && state.usage == null
    val allocationNeedsAttention = state.settings?.quotaBytes == null || state.usage?.overAllocation == true
    val defaultFocusKeys =
        remember(recordFocusKeys, allocationNeedsAttention) {
            when {
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
                        lazySlotIndex = { semanticIndex -> tvDownloadLazySlotIndex(semanticIndex, sectionSizes) },
                        revealCentered = { slot ->
                            listState.scrollToItem(slot)
                            val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { item -> item.index == slot }
                            if (itemInfo != null) {
                                val viewportCenter =
                                    (listState.layoutInfo.viewportStartOffset + listState.layoutInfo.viewportEndOffset) / 2
                                listState.scrollToItem(slot, -(viewportCenter - itemInfo.size / 2))
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

    LazyColumn(
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
    ) {
        item(key = TV_DOWNLOADS_MANAGE_FOCUS_KEY) {
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
        if (sections.isEmpty()) {
            item(key = "downloads:empty") {
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
                item(key = "downloads:section:${section.kind.name}") {
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
                    TvDownloadRow(
                        record = record,
                        busy = state.inFlightDownloadId == record.downloadId.value,
                        artifactLeased = record.downloadId.value in state.leasedDownloadIds,
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
                        onRequestRailFocus = hostedRail::requestRailFocus,
                        onClick = { onOpenActions(record) },
                        onPlayOffline = { onPlayOffline(record) },
                    )
                }
            }
        }
        state.error?.let { error ->
            item(key = "downloads:error") {
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
private fun TvDownloadRow(
    record: DownloadRecord,
    busy: Boolean,
    artifactLeased: Boolean,
    focusRequester: FocusRequester,
    onFocusChanged: () -> Unit,
    onRequestRailFocus: () -> Boolean,
    onClick: () -> Unit,
    onPlayOffline: () -> Unit,
) {
    val palette = LocalJellyfinPalette.current
    val expectedBytes = tvDownloadExpectedBytes(record)
    val progress =
        if (expectedBytes > 0L) {
            (record.physicalBytes.toFloat() / expectedBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    TvFocusableBox(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { focusState -> if (focusState.isFocused) onFocusChanged() }
                .onPreviewKeyEvent { event ->
                    when {
                        event.requestsRail(onRequestRailFocus) -> true
                        record.state == DownloadState.Completed && event.isPlayKeyDown() -> {
                            onPlayOffline()
                            true
                        }
                        else -> false
                    }
                },
        contentDescription = record.request.snapshot.title,
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
                Column(modifier = Modifier.weight(1f)) {
                    TvText(text = record.request.snapshot.title, style = TvTitleStyle, maxLines = 1)
                    record.request.snapshot.seriesName?.takeIf(String::isNotBlank)?.let { seriesName ->
                        TvText(
                            text = seriesName,
                            style = TvSecondaryStyle,
                            color = palette.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
                if (busy) {
                    TvSpinner(modifier = Modifier.size(TvDimens.settingsRowIconSize))
                }
                TvText(
                    text = stringResource(tvDownloadStateResource(record.state)),
                    style = TvBodyStyle.copy(fontWeight = FontWeight.SemiBold),
                    color =
                        when (record.state) {
                            DownloadState.Failed -> palette.error
                            DownloadState.BlockedByQuota -> palette.accentAmber
                            else -> palette.cyan
                        },
                    maxLines = 1,
                )
            }
            TvDownloadSnapshotDetails(record)
            record.request.snapshot.sourcePresentation?.takeIf(String::isNotBlank)?.let { presentation ->
                TvText(
                    text = stringResource(R.string.tv_downloads_version, presentation),
                    style = TvSecondaryStyle,
                    color = palette.textSecondary,
                    maxLines = 1,
                )
            }
            if (record.state == DownloadState.Downloading || record.state == DownloadState.Finalizing) {
                TvProgressBar(progress = progress)
                TvText(
                    text =
                        stringResource(
                            R.string.tv_downloads_progress,
                            tvDownloadBytes(record.physicalBytes),
                            tvDownloadBytes(expectedBytes),
                        ),
                    style = TvSecondaryStyle,
                    color = palette.textSecondary,
                    maxLines = 1,
                )
            } else if (record.state == DownloadState.Completed) {
                TvText(
                    text = tvDownloadBytes(record.physicalBytes),
                    style = TvSecondaryStyle,
                    color = palette.textSecondary,
                    maxLines = 1,
                )
                record.localResumePositionMs.takeIf { position -> position > 0L }?.let { position ->
                    TvText(
                        text = stringResource(R.string.tv_downloads_resume_at, formatDuration(position)),
                        style = TvSecondaryStyle,
                        color = palette.textSecondary,
                        maxLines = 1,
                    )
                }
            }
            record.failure?.let { failure ->
                TvText(
                    text = stringResource(tvDownloadFailureResource(failure)),
                    style = TvBodyStyle,
                    color = palette.error,
                    maxLines = 2,
                )
            }
            if (artifactLeased) {
                TvText(
                    text = stringResource(R.string.tv_downloads_artifact_in_use),
                    style = TvBodyStyle,
                    color = palette.error,
                    maxLines = 2,
                )
            }
            TvText(
                text = stringResource(R.string.tv_downloads_open_actions_hint),
                style = TvSecondaryStyle,
                color = palette.textSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TvDownloadSnapshotDetails(record: DownloadRecord) {
    val snapshot = record.request.snapshot
    val kind =
        stringResource(
            when (snapshot.itemKind) {
                MediaKind.Movie -> R.string.tv_downloads_kind_movie
                MediaKind.Episode -> R.string.tv_downloads_kind_episode
                MediaKind.Series,
                MediaKind.Other,
                -> R.string.tv_downloads_kind_video
            },
        )
    val duration =
        snapshot.durationMs
            ?.let { durationMs -> stringResource(R.string.tv_downloads_duration_minutes, durationMs / 60_000L) }
            ?: stringResource(R.string.tv_downloads_duration_unknown)
    val quality =
        when (val downloadQuality = record.request.quality) {
            DownloadQuality.Original -> stringResource(R.string.tv_downloads_quality_original)
            is DownloadQuality.Fixed ->
                stringResource(
                    R.string.tv_downloads_quality_fixed,
                    downloadQuality.rung.height,
                    tvDownloadBitrateMbps(downloadQuality.maxBitrateBps),
                )
        }
    TvText(
        text = stringResource(R.string.tv_downloads_item_details, kind, duration, quality),
        style = TvSecondaryStyle,
        color = LocalJellyfinPalette.current.textSecondary,
        maxLines = 2,
    )
    snapshot.seasonLabel?.takeIf(String::isNotBlank)?.let { season ->
        TvText(text = season, style = TvSecondaryStyle, color = LocalJellyfinPalette.current.textSecondary, maxLines = 1)
    }
    snapshot.episodeLabel?.takeIf(String::isNotBlank)?.let { episode ->
        TvText(text = episode, style = TvSecondaryStyle, color = LocalJellyfinPalette.current.textSecondary, maxLines = 1)
    }
}

@Composable
private fun TvDownloadActionsDialog(
    record: DownloadRecord,
    busy: Boolean,
    artifactLeased: Boolean,
    onDismiss: () -> Unit,
    onAction: (TvDownloadAction) -> Unit,
) {
    val actions =
        remember(record.state, record.localResumePositionMs, busy, artifactLeased) {
            tvDownloadActions(record, busy, artifactLeased)
        }
    val requesters = remember(actions) { List(actions.size) { FocusRequester() } }
    TvFocusTrapEffect()
    LaunchedEffect(actions) {
        requesters.firstOrNull()?.let { requester -> requestTvFocusWithRetry { requester.requestFocusSafely() } }
    }
    TvSettingsDialogFrame(
        title = record.request.snapshot.title,
        onDismiss = onDismiss,
    ) {
        actions.forEachIndexed { index, option ->
            TvSettingsDialogAction(
                text = stringResource(tvDownloadActionResource(option.action, record.localResumePositionMs)),
                focusRequester = requesters[index],
                onClick = { onAction(option.action) },
                enabled = option.enabled,
                destructive = option.destructive,
                modifier =
                    Modifier.onPreviewKeyEvent { event ->
                        event.type == KeyEventType.KeyDown &&
                            (
                                event.key == Key.DirectionLeft ||
                                    event.key == Key.DirectionRight ||
                                    (index == 0 && event.key == Key.DirectionUp) ||
                                    (index == actions.lastIndex && event.key == Key.DirectionDown)
                            )
                    },
            )
        }
        if (artifactLeased && record.state == DownloadState.Completed) {
            TvText(
                text = stringResource(R.string.tv_downloads_artifact_in_use),
                style = TvBodyStyle,
                color = LocalJellyfinPalette.current.error,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun TvDownloadConfirmationDialog(
    confirmation: TvDownloadConfirmation,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val confirmRequester = remember(confirmation) { FocusRequester() }
    TvFocusTrapEffect()
    LaunchedEffect(confirmRequester) {
        requestTvFocusWithRetry { confirmRequester.requestFocusSafely() }
    }
    val deleting = confirmation.kind == TvDownloadConfirmationKind.Delete
    TvSettingsDialogFrame(
        title =
            stringResource(
                if (deleting) R.string.tv_downloads_delete_title else R.string.tv_downloads_cancel_title,
            ),
        onDismiss = onDismiss,
    ) {
        TvText(
            text =
                stringResource(
                    if (deleting) R.string.tv_downloads_delete_message else R.string.tv_downloads_cancel_message,
                    confirmation.title,
                ),
            style = TvBodyStyle,
            color = LocalJellyfinPalette.current.textSecondary,
            maxLines = 3,
        )
        TvSettingsDialogAction(
            text =
                stringResource(
                    if (deleting) R.string.tv_downloads_delete_confirm else R.string.tv_downloads_cancel_confirm,
                ),
            focusRequester = confirmRequester,
            onClick = onConfirm,
            destructive = true,
            modifier =
                Modifier.onPreviewKeyEvent { event ->
                    event.type == KeyEventType.KeyDown &&
                        event.key in
                        setOf(
                            Key.DirectionLeft,
                            Key.DirectionRight,
                            Key.DirectionUp,
                            Key.DirectionDown,
                        )
                },
        )
    }
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
        val customEnabled = safeMaximum >= DOWNLOAD_BYTES_PER_GIB
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
                                        choice.quotaBytes / DOWNLOAD_BYTES_PER_GIB,
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
    val maximumWholeGib = maximumQuotaBytes / DOWNLOAD_BYTES_PER_GIB
    var wholeGibInput by remember(maximumQuotaBytes) { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var exitEditingRequests by remember { mutableStateOf(0) }
    val inputRequester = remember { FocusRequester() }
    val applyRequester = remember { FocusRequester() }
    val quotaBytes = tvDownloadCustomQuotaBytes(wholeGibInput, maximumQuotaBytes)

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
            text = stringResource(R.string.tv_downloads_quota_custom_range, maximumWholeGib),
            color = LocalJellyfinPalette.current.textSecondary,
            style = TvBodyStyle,
            maxLines = 2,
        )
        TvInputField(
            value = wholeGibInput,
            onValueChange = { value -> wholeGibInput = value.filter(Char::isDigit) },
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
        if (wholeGibInput.isNotBlank() && quotaBytes == null) {
            TvText(
                text = stringResource(R.string.tv_downloads_quota_custom_invalid, maximumWholeGib),
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

private enum class TvDownloadAction {
    Play,
    Start,
    Pause,
    Resume,
    Retry,
    Cancel,
    Delete,
}

private data class TvDownloadActionOption(
    val action: TvDownloadAction,
    val enabled: Boolean,
    val destructive: Boolean = false,
)

private fun tvDownloadActions(
    record: DownloadRecord,
    busy: Boolean,
    artifactLeased: Boolean,
): List<TvDownloadActionOption> =
    buildList {
        when (record.state) {
            DownloadState.Queued -> add(TvDownloadActionOption(TvDownloadAction.Start, enabled = !busy))
            DownloadState.Downloading -> add(TvDownloadActionOption(TvDownloadAction.Pause, enabled = !busy))
            DownloadState.Paused,
            DownloadState.BlockedByQuota,
            -> add(TvDownloadActionOption(TvDownloadAction.Resume, enabled = !busy))
            DownloadState.Failed -> add(TvDownloadActionOption(TvDownloadAction.Retry, enabled = !busy))
            DownloadState.Completed -> add(TvDownloadActionOption(TvDownloadAction.Play, enabled = true))
            DownloadState.NotDownloaded,
            DownloadState.Finalizing,
            -> Unit
        }
        if (record.state == DownloadState.Completed) {
            add(
                TvDownloadActionOption(
                    action = TvDownloadAction.Delete,
                    enabled = !busy && !artifactLeased,
                    destructive = true,
                ),
            )
        } else {
            add(
                TvDownloadActionOption(
                    action = TvDownloadAction.Cancel,
                    enabled = !busy,
                    destructive = true,
                ),
            )
        }
    }

private data class TvDownloadConfirmation(
    val downloadId: String,
    val title: String,
    val kind: TvDownloadConfirmationKind,
)

private enum class TvDownloadConfirmationKind {
    Cancel,
    Delete,
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

private fun tvDownloadBitrateMbps(bitrateBps: Long): String {
    val whole = bitrateBps / 1_000_000L
    val remainder = bitrateBps % 1_000_000L
    return if (remainder == 0L) whole.toString() else "$whole.${remainder / 100_000L}"
}

@Composable
private fun tvDownloadBytes(bytes: Long): String =
    when {
        bytes >= DOWNLOAD_BYTES_PER_GIB ->
            stringResource(R.string.tv_downloads_bytes_gib, bytes.toDouble() / DOWNLOAD_BYTES_PER_GIB)
        bytes >= DOWNLOAD_BYTES_PER_MIB ->
            stringResource(R.string.tv_downloads_bytes_mib, bytes.toDouble() / DOWNLOAD_BYTES_PER_MIB)
        bytes >= DOWNLOAD_BYTES_PER_KIB ->
            stringResource(R.string.tv_downloads_bytes_kib, bytes.toDouble() / DOWNLOAD_BYTES_PER_KIB)
        else -> stringResource(R.string.tv_downloads_bytes_b, bytes)
    }

private fun tvDownloadStateResource(state: DownloadState): Int =
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

private fun tvDownloadFailureResource(failure: DownloadFailure): Int =
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

private fun tvDownloadsErrorResource(error: DownloadsUiError): Int =
    when (error) {
        DownloadsUiError.LoadFailed -> R.string.tv_downloads_error
        DownloadsUiError.CommandRejected -> R.string.tv_downloads_command_error
        DownloadsUiError.SchedulingRetryRejected -> R.string.tv_downloads_schedule_error
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

private fun tvDownloadActionResource(
    action: TvDownloadAction,
    localResumePositionMs: Long,
): Int =
    when (action) {
        TvDownloadAction.Play ->
            if (localResumePositionMs > 0L) {
                R.string.tv_downloads_action_resume_playback
            } else {
                R.string.tv_downloads_action_play
            }
        TvDownloadAction.Start -> R.string.tv_downloads_action_start
        TvDownloadAction.Pause -> R.string.tv_downloads_action_pause
        TvDownloadAction.Resume -> R.string.tv_downloads_action_resume
        TvDownloadAction.Retry -> R.string.tv_downloads_action_retry
        TvDownloadAction.Cancel -> R.string.tv_downloads_action_cancel
        TvDownloadAction.Delete -> R.string.tv_downloads_action_delete
    }

private const val DOWNLOAD_BYTES_PER_KIB = 1_024L
private const val DOWNLOAD_BYTES_PER_MIB = DOWNLOAD_BYTES_PER_KIB * DOWNLOAD_BYTES_PER_KIB
