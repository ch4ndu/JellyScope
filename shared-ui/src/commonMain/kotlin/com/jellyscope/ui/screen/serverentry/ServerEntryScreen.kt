// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.serverentry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.ServerInfo
import com.jellyscope.ui.adaptive.LocalWindowWidthTier
import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.component.launch.AmbientGhostButton
import com.jellyscope.ui.component.launch.AmbientGlassPanel
import com.jellyscope.ui.component.launch.AmbientPrimaryButton
import com.jellyscope.ui.component.launch.AmbientTextField
import com.jellyscope.ui.component.launch.DiscoveredServerCard
import com.jellyscope.ui.component.launch.JellyScopeBrandMark
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.server_entry_action_cd
import com.jellyscope.ui.generated.resources.server_entry_button
import com.jellyscope.ui.generated.resources.server_entry_discovered_connect_cd
import com.jellyscope.ui.generated.resources.server_entry_discovered_none
import com.jellyscope.ui.generated.resources.server_entry_discovered_scanning
import com.jellyscope.ui.generated.resources.server_entry_discovered_title
import com.jellyscope.ui.generated.resources.server_entry_error_invalid_url
import com.jellyscope.ui.generated.resources.server_entry_error_not_reachable
import com.jellyscope.ui.generated.resources.server_entry_error_server
import com.jellyscope.ui.generated.resources.server_entry_loading
import com.jellyscope.ui.generated.resources.server_entry_manual
import com.jellyscope.ui.generated.resources.server_entry_or_manual
import com.jellyscope.ui.generated.resources.server_entry_scan_again
import com.jellyscope.ui.generated.resources.server_entry_scan_again_cd
import com.jellyscope.ui.generated.resources.server_entry_title
import com.jellyscope.ui.generated.resources.server_entry_url_label
import com.jellyscope.ui.theme.AmbientLaunchDimens
import com.jellyscope.ui.theme.AmbientLaunchTokens
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ServerEntryScreen(
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

    ServerEntryContent(
        state = state,
        onServerUrlChange = viewModel::updateInput,
        onSubmit = viewModel::submit,
        onScanAgain = viewModel::scanAgain,
        onDiscoveredServerClick = viewModel::selectDiscoveredServer,
        modifier = modifier,
    )
}

@Composable
fun ServerEntryContent(
    state: ServerEntryUiState,
    onServerUrlChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onScanAgain: () -> Unit,
    onDiscoveredServerClick: (DiscoveredServerUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val validateContentDescription = stringResource(Res.string.server_entry_action_cd)
    val scanAgainContentDescription = stringResource(Res.string.server_entry_scan_again_cd)
    val connectTitle = stringResource(Res.string.server_entry_title)
    val widthTier = LocalWindowWidthTier.current

    if (widthTier == WindowWidthTier.Compact) {
        CompactServerEntryContent(
            state = state,
            connectTitle = connectTitle,
            validateContentDescription = validateContentDescription,
            scanAgainContentDescription = scanAgainContentDescription,
            onServerUrlChange = onServerUrlChange,
            onSubmit = onSubmit,
            onScanAgain = onScanAgain,
            onDiscoveredServerClick = onDiscoveredServerClick,
            modifier = modifier,
        )
    } else {
        ExpandedServerEntryContent(
            state = state,
            connectTitle = connectTitle,
            validateContentDescription = validateContentDescription,
            scanAgainContentDescription = scanAgainContentDescription,
            onServerUrlChange = onServerUrlChange,
            onSubmit = onSubmit,
            onScanAgain = onScanAgain,
            onDiscoveredServerClick = onDiscoveredServerClick,
            modifier = modifier,
        )
    }
}

@Composable
private fun CompactServerEntryContent(
    state: ServerEntryUiState,
    connectTitle: String,
    validateContentDescription: String,
    scanAgainContentDescription: String,
    onServerUrlChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onScanAgain: () -> Unit,
    onDiscoveredServerClick: (DiscoveredServerUi) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Dimensions.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = Dimensions.formControlMaxWidth)
                    .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.panelGap),
        ) {
            JellyScopeBrandMark(
                subtitle = connectTitle,
                landscape = false,
            )
            AmbientGlassPanel {
                if (state.isDiscoveryAvailable) {
                    DiscoveredServers(
                        servers = state.discoveredServers,
                        isScanning = state.isScanning,
                        onServerClick = onDiscoveredServerClick,
                    )
                }
                ServerUrlField(
                    state = state,
                    onValueChange = onServerUrlChange,
                )
                ServerEntryErrorText(state.error)
                ValidateServerButton(
                    isValidating = state.isValidating,
                    contentDescription = validateContentDescription,
                    onSubmit = onSubmit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.isDiscoveryAvailable) {
                AmbientGhostButton(
                    label = stringResource(Res.string.server_entry_scan_again),
                    onClick = onScanAgain,
                    enabled = !state.isScanning,
                    contentDescription = scanAgainContentDescription,
                )
            }
        }
    }
}

@Composable
private fun ExpandedServerEntryContent(
    state: ServerEntryUiState,
    connectTitle: String,
    validateContentDescription: String,
    scanAgainContentDescription: String,
    onServerUrlChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onScanAgain: () -> Unit,
    onDiscoveredServerClick: (DiscoveredServerUi) -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = Dimensions.adaptiveExpandedScreenPadding),
    ) {
        Column(
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .heightIn(min = 640.dp, max = 960.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(100.dp))
            JellyScopeBrandMark(
                subtitle = connectTitle,
                landscape = true,
            )
            Box(
                modifier =
                    Modifier
                        .width(AmbientLaunchDimens.brandMarkLandscape)
                        .height(AmbientLaunchDimens.wideBrandUnderlineHeight)
                        .background(AmbientLaunchTokens.accent)
                        .padding(bottom = Dimensions.contentSpacing),
            )
            if (state.isDiscoveryAvailable) {
                DiscoveredServers(
                    servers = state.discoveredServers,
                    isScanning = state.isScanning,
                    onServerClick = onDiscoveredServerClick,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text =
                    stringResource(
                        if (state.isDiscoveryAvailable) {
                            Res.string.server_entry_or_manual
                        } else {
                            Res.string.server_entry_manual
                        },
                    ),
                color = AmbientLaunchTokens.textPrimary,
                style =
                    TextStyle(
                        fontSize = AmbientLaunchDimens.sectionTitleSize,
                        fontWeight = FontWeight.SemiBold,
                    ),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.controlGap),
                verticalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.controlGap),
                itemVerticalAlignment = Alignment.Bottom,
            ) {
                ServerUrlField(
                    state = state,
                    onValueChange = onServerUrlChange,
                    modifier = Modifier.widthIn(max = Dimensions.formControlMaxWidth),
                )
                ValidateServerButton(
                    isValidating = state.isValidating,
                    contentDescription = validateContentDescription,
                    onSubmit = onSubmit,
                )
                if (state.isDiscoveryAvailable) {
                    AmbientGhostButton(
                        label = stringResource(Res.string.server_entry_scan_again),
                        onClick = onScanAgain,
                        enabled = !state.isScanning,
                        contentDescription = scanAgainContentDescription,
                    )
                }
            }
            ServerEntryErrorText(state.error)
            Spacer(modifier = Modifier.height(Dimensions.adaptiveExpandedScreenPadding))
        }
    }
}

@Composable
private fun ServerUrlField(
    state: ServerEntryUiState,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AmbientTextField(
        value = state.input,
        onValueChange = onValueChange,
        label = stringResource(Res.string.server_entry_url_label),
        modifier = modifier,
        leadingIcon = Icons.Outlined.Link,
        enabled = !state.isValidating,
        isError = state.error != null,
        contentDescription = stringResource(Res.string.server_entry_url_label),
    )
}

@Composable
private fun ValidateServerButton(
    isValidating: Boolean,
    contentDescription: String,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AmbientPrimaryButton(
        label =
            stringResource(
                if (isValidating) {
                    Res.string.server_entry_loading
                } else {
                    Res.string.server_entry_button
                },
            ),
        onClick = onSubmit,
        modifier = modifier,
        enabled = !isValidating,
        loading = isValidating,
        contentDescription = contentDescription,
    )
}

@Composable
private fun DiscoveredServers(
    servers: List<DiscoveredServerUi>,
    isScanning: Boolean,
    onServerClick: (DiscoveredServerUi) -> Unit,
) {
    // The region is always present (fixed-height box below) so re-scanning never
    // inserts/removes height and reflows the centered form — the content inside
    // the box swaps between the list, the scanning hint, and the empty state.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.controlGap),
    ) {
        Row(
            modifier = Modifier.padding(vertical = Dimensions.contentSpacing),
            horizontalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.controlGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.server_entry_discovered_title),
                color = AmbientLaunchTokens.textPrimary,
                style =
                    TextStyle(
                        fontSize = AmbientLaunchDimens.sectionTitleSize,
                        fontWeight = FontWeight.SemiBold,
                    ),
            )
            // Inline spinner keeps scanning state out of the layout flow so it
            // never adds/removes a row height.
            if (isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(AmbientLaunchDimens.buttonSpinnerSize),
                    color = AmbientLaunchTokens.accent,
                    strokeWidth = AmbientLaunchDimens.buttonSpinnerStroke,
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(AmbientLaunchDimens.discoveredRegionHeight),
        ) {
            if (servers.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.controlGap),
                ) {
                    items(
                        items = servers,
                        key = { it.id },
                    ) { server ->
                        DiscoveredServerCard(
                            name = server.name,
                            address = server.address,
                            onClick = { onServerClick(server) },
                            contentDescription =
                                stringResource(
                                    Res.string.server_entry_discovered_connect_cd,
                                    server.name,
                                    server.address,
                                ),
                        )
                    }
                }
            } else {
                Text(
                    text =
                        stringResource(
                            if (isScanning) {
                                Res.string.server_entry_discovered_scanning
                            } else {
                                Res.string.server_entry_discovered_none
                            },
                        ),
                    modifier = Modifier.align(Alignment.TopStart),
                    color = AmbientLaunchTokens.textSecondary,
                    style = TextStyle(fontSize = AmbientLaunchDimens.bodyTextSize),
                )
            }
        }
    }
}

@Composable
private fun ServerEntryErrorText(error: ServerEntryError?) {
    error?.let {
        Text(
            text = stringResource(error.stringResource),
            color = AmbientLaunchTokens.error,
            style = TextStyle(fontSize = AmbientLaunchDimens.bodyTextSize),
        )
    }
}

private val ServerEntryError.stringResource: StringResource
    get() =
        when (this) {
            ServerEntryError.InvalidUrl -> Res.string.server_entry_error_invalid_url
            ServerEntryError.NotReachable -> Res.string.server_entry_error_not_reachable
            ServerEntryError.ServerError -> Res.string.server_entry_error_server
        }
