// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.jellyscope.core.domain.model.ServerInfo
import com.jellyscope.tv.R
import com.jellyscope.ui.component.launch.JellyScopeBrandMark
import com.jellyscope.ui.component.launch.LaunchIcons
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.serverentry.DiscoveredServerUi
import com.jellyscope.ui.screen.serverentry.ServerEntryError
import com.jellyscope.ui.screen.serverentry.ServerEntryEvent
import com.jellyscope.ui.screen.serverentry.ServerEntryUiState
import com.jellyscope.ui.screen.serverentry.ServerEntryViewModel
import com.jellyscope.ui.theme.AmbientLaunchTokens
import org.koin.compose.viewmodel.koinViewModel
import com.jellyscope.ui.component.AdaptiveSpinner as TvSpinner

@Composable
fun TvServerEntryScreen(
    initialServerUrl: String?,
    onServerValidated: (ServerInfo) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServerEntryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(initialServerUrl) {
        if (!initialServerUrl.isNullOrBlank() && state.input.isBlank()) {
            viewModel.updateInput(initialServerUrl)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is ServerEntryEvent.Validated -> onServerValidated(event.serverInfo)
            }
        }
    }

    TvServerEntryContent(
        state = state,
        onServerUrlChange = viewModel::updateInput,
        onSubmit = viewModel::submit,
        onScanAgain = viewModel::scanAgain,
        onDiscoveredServerClick = viewModel::selectDiscoveredServer,
        modifier = modifier,
    )
}

@Composable
internal fun TvServerEntryContent(
    state: ServerEntryUiState,
    onServerUrlChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onScanAgain: () -> Unit,
    onDiscoveredServerClick: (DiscoveredServerUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstServerRequester = remember { FocusRequester() }
    val validateRequester = remember { FocusRequester() }
    val hasDiscoveryContent = state.discoveredServers.isNotEmpty() || state.isScanning

    LaunchedEffect(state.discoveredServers.firstOrNull()?.id) {
        if (state.discoveredServers.isNotEmpty()) {
            firstServerRequester.requestFocusSafely()
        } else {
            validateRequester.requestFocusSafely()
        }
    }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(TvDimens.launchScreenPadding),
    ) {
        JellyScopeBrandMark(landscape = true)
        Spacer(modifier = Modifier.height(TvDimens.launchSectionGap.times(2)))
        TvAmbientLaunchTitle(text = stringResource(R.string.tv_connect_title))
        Spacer(modifier = Modifier.height(TvDimens.launchSectionGap))

        // Discovered servers as a horizontal list (matches the landscape mockup).
        if (hasDiscoveryContent) {
            TvAmbientSectionTitle(
                text = stringResource(R.string.tv_discovered_title),
                leadingIcon = LaunchIcons.Lan,
            )
            LazyRow(
                // Preserve the alpha11 four-edge focus-scale reserve so the first and
                // last focused cards never clip at the list bounds.
                contentPadding =
                    PaddingValues(
                        horizontal = TvDimens.launchListHorizontalReserve,
                        vertical = TvDimens.gridContentTopPadding,
                    ),
                horizontalArrangement = Arrangement.spacedBy(TvDimens.formGap),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isScanning) {
                    item(key = "scanning") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(TvDimens.settingsValueGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TvSpinner(
                                size = TvDimens.launchSpinnerSize,
                                color = AmbientLaunchTokens.accent,
                            )
                            Text(
                                text = stringResource(R.string.tv_discovered_scanning),
                                color = AmbientLaunchTokens.textSecondary,
                                style = AmbientTvBodyTextStyle,
                                maxLines = 1,
                            )
                        }
                    }
                }
                itemsIndexed(
                    items = state.discoveredServers,
                    key = { _, server -> server.id },
                ) { index, server ->
                    TvAmbientDiscoveredServerCard(
                        server = server,
                        onClick = { onDiscoveredServerClick(server) },
                        modifier =
                            Modifier
                                .wrapContentWidth()
                                .then(
                                    if (index == 0) {
                                        Modifier.focusRequester(firstServerRequester)
                                    } else {
                                        Modifier
                                    },
                                ),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(TvDimens.launchSectionGap.plus(20.dp)))

        TvAmbientSectionTitle(
            text = stringResource(R.string.tv_or_manual),
            leadingIcon = LaunchIcons.Globe,
        )
        state.error?.let { error ->
            Text(
                text = stringResource(error.messageResource),
                color = AmbientLaunchTokens.error,
                style = AmbientTvBodyTextStyle,
                maxLines = 2,
            )
        }
        Spacer(modifier = Modifier.height(TvDimens.launchSectionGap))
        Row(
            modifier = Modifier.fillMaxWidth(1f),
            horizontalArrangement = Arrangement.spacedBy(TvDimens.formGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvInputField(
                value = state.input,
                onValueChange = onServerUrlChange,
                label = stringResource(R.string.tv_server_url_label),
                enabled = !state.isValidating,
                modifier =
                    Modifier
                        .requiredWidthIn(max = 320.dp)
                        .align(Alignment.CenterVertically),
                colors = AmbientTvInputFieldColors,
                textStyle = AmbientTvBodyTextStyle,
                glowColor = AmbientTvFocusGlowColor,
                glowElevation = TvDimens.launchFocusGlowElevation,
            )
            TvAmbientPrimaryButton(
                text =
                    stringResource(
                        if (state.isValidating) {
                            R.string.tv_validating_server
                        } else {
                            R.string.tv_validate_server
                        },
                    ),
                onClick = onSubmit,
                enabled = !state.isValidating,
                loading = state.isValidating,
                modifier =
                    Modifier
                        .focusRequester(validateRequester)
                        .padding(bottom = 12.dp),
            )
            TvAmbientSecondaryButton(
                text = stringResource(R.string.tv_scan_again),
                onClick = onScanAgain,
                enabled = !state.isScanning,
                modifier =
                    Modifier
                        .padding(bottom = 12.dp),
            )
        }
    }
}

private val ServerEntryError.messageResource: Int
    get() =
        when (this) {
            ServerEntryError.InvalidUrl -> R.string.tv_error_invalid_url
            ServerEntryError.NotReachable -> R.string.tv_error_not_reachable
            ServerEntryError.ServerError -> R.string.tv_error_server
        }
