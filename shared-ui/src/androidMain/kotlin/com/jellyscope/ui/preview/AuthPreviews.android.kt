// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.preview

import androidx.compose.runtime.Composable
import com.jellyscope.ui.screen.login.LoginContent
import com.jellyscope.ui.screen.login.LoginUiState
import com.jellyscope.ui.screen.login.QuickConnectUiState
import com.jellyscope.ui.screen.serverentry.DiscoveredServerUi
import com.jellyscope.ui.screen.serverentry.ServerEntryContent
import com.jellyscope.ui.screen.serverentry.ServerEntryUiState

// @JellyScopeScreenPreviews
// @Composable
// private fun FoundationScreenPreview() {
//    JellyScopePreviewSurface {
//        FoundationScreen()
//    }
// }
//
// @JellyScopeScreenPreviews
// @Composable
// private fun SessionRestoringPreview() {
//    JellyScopePreviewSurface {
//        SessionRestoringContent()
//    }
// }

@JellyScopeScreenPreviews
@Composable
private fun ServerEntryPreview() {
    JellyScopePreviewSurface {
        ServerEntryContent(
            state =
                ServerEntryUiState(
                    input = PreviewFixtures.session.serverUrl,
                    discoveredServers =
                        listOf(
                            DiscoveredServerUi(
                                "server-1",
                                "Living Room Jellyfin",
                                "http://192.168.1.15:8096",
                            ),
                            DiscoveredServerUi(
                                "server-2",
                                "Studio Jellyfin",
                                "http://192.168.1.28:8096",
                            ),
                        ),
                    isScanning = true,
                ),
            onServerUrlChange = {},
            onSubmit = {},
            onScanAgain = {},
            onDiscoveredServerClick = {},
        )
    }
}

@JellyScopeScreenPreviews
@Composable
private fun EmptyServerEntryPreview() {
    JellyScopePreviewSurface {
        ServerEntryContent(
            state =
                ServerEntryUiState(
                    input = PreviewFixtures.session.serverUrl,
                    discoveredServers = emptyList(),
                    isScanning = false,
                ),
            onServerUrlChange = {},
            onSubmit = {},
            onScanAgain = {},
            onDiscoveredServerClick = {},
        )
    }
}

@JellyScopeScreenPreviews
@Composable
private fun UnavailableServerEntryPreview() {
    JellyScopePreviewSurface {
        ServerEntryContent(
            state =
                ServerEntryUiState(
                    input = PreviewFixtures.session.serverUrl,
                    isDiscoveryAvailable = false,
                ),
            onServerUrlChange = {},
            onSubmit = {},
            onScanAgain = {},
            onDiscoveredServerClick = {},
        )
    }
}

@JellyScopeScreenPreviews
@Composable
private fun LoginPreview() {
    JellyScopePreviewSurface {
        LoginContent(
            state =
                LoginUiState(
                    serverInfo = PreviewFixtures.serverInfo,
                    username = PreviewFixtures.session.userName,
                    password = "preview-password",
                ),
            quickConnectState =
                QuickConnectUiState.CodeShown(
                    secret = "preview-secret",
                    code = "123456",
                    displayCode = "123 456",
                ),
            onUsernameChange = {},
            onPasswordChange = {},
            onSubmit = {},
            onQuickConnectStart = {},
            onBackToServer = {},
        )
    }
}
