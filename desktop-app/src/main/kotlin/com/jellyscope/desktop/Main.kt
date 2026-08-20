// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import com.jellyscope.core.data.local.PreviousRunFailurePlatform
import com.jellyscope.core.data.local.PreviousRunFailureStore
import com.jellyscope.core.data.remote.ClientInfo
import com.jellyscope.core.di.coreModule
import com.jellyscope.core.di.desktopCoreModule
import com.jellyscope.core.di.downloadsModule
import com.jellyscope.core.domain.playback.PlaybackHealthGuidancePolicy
import com.jellyscope.core.download.DownloadLifecycleHost
import com.jellyscope.core.playback.DesktopPlaybackProbe
import com.jellyscope.core.util.LogBufferStore
import com.jellyscope.core.util.configureApplicationLogWriters
import com.jellyscope.core.util.consumePreviousRunFailure
import com.jellyscope.core.util.installPreviousRunFailureHandler
import com.jellyscope.ui.AppInfo
import com.jellyscope.ui.JellyScopeApp
import com.jellyscope.ui.component.LocalInputDiagnosticsSink
import com.jellyscope.ui.di.sharedUiModule
import com.jellyscope.ui.platform.FullscreenToggleState
import com.jellyscope.ui.platform.LocalFullscreenToggle
import com.jellyscope.ui.platform.LocalPlatformCapabilities
import com.jellyscope.ui.platform.LocalPlayerCursorState
import com.jellyscope.ui.platform.LocalPlayerKeyCommandBridge
import com.jellyscope.ui.platform.PlatformCapabilities
import com.jellyscope.ui.platform.PlayerCursorState
import com.jellyscope.ui.platform.PlayerKeyCommandBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import java.awt.AWTEvent
import java.awt.Color
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.net.URI

private var koinStarted = false

fun main() {
    val detailDeepLinkOwner = DesktopDetailDeepLinkOwner(desktopInitialDetailItemId())
    installDesktopDetailDeepLinkHandler { itemId ->
        detailDeepLinkOwner.publish(itemId)
    }
    application {
        ensureKoin()
        val initialDetailEvent by detailDeepLinkOwner.event.collectAsState()
        val windowState = rememberWindowState()
        val playerKeyCommandBridge = remember { PlayerKeyCommandBridge() }
        val inputDiagnostics = remember { DesktopInputDiagnostics() }
        val fullscreenToggle =
            // Keyed on placement as well as the state object: `isFullscreen` is an
            // eager value, and `windowState` keeps its identity across placement
            // changes, so keying on it alone would freeze the flag at its first value.
            remember(windowState, windowState.placement) {
                FullscreenToggleState(
                    isFullscreen = windowState.placement == WindowPlacement.Fullscreen,
                    toggleFullscreen = {
                        windowState.placement =
                            if (windowState.placement == WindowPlacement.Fullscreen) {
                                WindowPlacement.Floating
                            } else {
                                WindowPlacement.Fullscreen
                            }
                    },
                    exitFullscreen = {
                        if (windowState.placement == WindowPlacement.Fullscreen) {
                            windowState.placement = WindowPlacement.Floating
                        }
                    },
                )
            }

        Window(
            onCloseRequest = {
                shutdownDesktopDownloads()
                exitApplication()
            },
            onPreviewKeyEvent = { event -> playerKeyCommandBridge.handler?.invoke(event) ?: false },
            title = "JellyScope",
            state = windowState,
        ) {
            val defaultCursor = remember { Cursor.getDefaultCursor() }
            val hiddenCursor =
                remember {
                    Toolkit
                        .getDefaultToolkit()
                        .createCustomCursor(
                            BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
                            Point(0, 0),
                            "jellyscope-hidden-cursor",
                        )
                }
            DisposableEffect(window) {
                val previousWindowBackground = window.background
                val previousContentBackground = window.contentPane.background
                window.background = Color.BLACK
                window.contentPane.background = Color.BLACK
                onDispose {
                    window.background = previousWindowBackground
                    window.contentPane.background = previousContentBackground
                }
            }
            val playerCursorState =
                remember(window, defaultCursor, hiddenCursor) {
                    PlayerCursorState { visible ->
                        val cursor =
                            if (visible) {
                                defaultCursor
                            } else {
                                hiddenCursor
                            }
                        // Compose renders inside child AWT components. Setting only
                        // the JFrame cursor leaves a child-owned default pointer
                        // visible, so apply the effective cursor to the whole tree.
                        window.setCursorRecursively(cursor)
                    }
                }
            DisposableEffect(window, defaultCursor) {
                onDispose {
                    window.setCursorRecursively(defaultCursor)
                }
            }
            LaunchedEffect(window, windowState, inputDiagnostics) {
                snapshotFlow { windowState.placement }
                    .distinctUntilChanged()
                    .collect { placement ->
                        inputDiagnostics.recordWindowPlacement(
                            placement = placement,
                            active = window.isActive,
                            focused = window.isFocused,
                        )
                    }
            }
            DisposableEffect(window, inputDiagnostics) {
                val windowListener =
                    object : WindowAdapter() {
                        override fun windowActivated(event: WindowEvent) {
                            inputDiagnostics.recordWindowActivation(
                                active = window.isActive,
                                focused = window.isFocused,
                            )
                        }

                        override fun windowDeactivated(event: WindowEvent) {
                            inputDiagnostics.recordWindowActivation(
                                active = window.isActive,
                                focused = window.isFocused,
                            )
                        }

                        override fun windowGainedFocus(event: WindowEvent) {
                            inputDiagnostics.recordWindowFocus(
                                active = window.isActive,
                                focused = window.isFocused,
                            )
                        }

                        override fun windowLostFocus(event: WindowEvent) {
                            inputDiagnostics.recordWindowFocus(
                                active = window.isActive,
                                focused = window.isFocused,
                            )
                        }
                    }
                val awtListener = inputDiagnostics.createAwtEventListener(window)
                window.addWindowListener(windowListener)
                window.addWindowFocusListener(windowListener)
                Toolkit
                    .getDefaultToolkit()
                    .addAWTEventListener(awtListener, AWTEvent.MOUSE_EVENT_MASK)
                onDispose {
                    Toolkit
                        .getDefaultToolkit()
                        .removeAWTEventListener(awtListener)
                    window.removeWindowFocusListener(windowListener)
                    window.removeWindowListener(windowListener)
                }
            }
            CompositionLocalProvider(
                LocalPlatformCapabilities provides PlatformCapabilities.Desktop,
                LocalFullscreenToggle provides fullscreenToggle,
                LocalPlayerCursorState provides playerCursorState,
                LocalPlayerKeyCommandBridge provides playerKeyCommandBridge,
                LocalInputDiagnosticsSink provides inputDiagnostics,
            ) {
                // No server prefill: the field starts empty, matching Android/iOS
                // release behavior. The user enters their own Jellyfin server.
                JellyScopeApp(
                    initialPlaybackItemId = desktopInitialPlaybackItemId(),
                    initialDetailEvent = initialDetailEvent,
                    onInitialDetailEventConsumed = detailDeepLinkOwner::acknowledge,
                    onDetailNavigationBoundaryChanged = detailDeepLinkOwner::updateBoundary,
                )
            }
        }
    }
}

private fun ensureKoin() {
    if (!koinStarted) {
        startKoin {
            modules(
                desktopCoreModule(),
                downloadsModule,
                coreModule,
                module {
                    single { ClientInfo(versionName = desktopVersionName()) }
                    single { AppInfo(versionName = get<ClientInfo>().versionName) }
                    single { PlaybackHealthGuidancePolicy.Advisory }
                },
                sharedUiModule,
            )
        }
        val koin = GlobalContext.get()
        val preferenceStore = koin.get<LogCollectionPreferenceStore>()
        val applicationScope = koin.get<CoroutineScope>()
        configureApplicationLogWriters(
            diagnosticsWriter = koin.get<LogBufferStore>().logWriter,
            preferenceStore = preferenceStore,
            applicationScope = applicationScope,
            isDebugBuild = System.getProperty(DESKTOP_DEBUG_PROPERTY) == "true",
        )
        consumePreviousRunFailure(
            store = koin.get<PreviousRunFailureStore>(),
            preferenceStore = preferenceStore,
            logBufferStore = koin.get<LogBufferStore>(),
        )
        installPreviousRunFailureHandler(
            store = koin.get<PreviousRunFailureStore>(),
            platform = PreviousRunFailurePlatform.Desktop,
        )
        koin.get<DownloadLifecycleHost>().start()
        DesktopPlaybackProbe.setPreferenceEnabled(preferenceStore.enabled.value)
        applicationScope.launch {
            preferenceStore.enabled.collect(DesktopPlaybackProbe::setPreferenceEnabled)
        }
        koinStarted = true
    }
}

private fun shutdownDesktopDownloads() {
    if (!koinStarted) return
    runCatching { GlobalContext.get().get<DownloadLifecycleHost>().stop() }
}

private fun desktopVersionName(): String =
    System.getProperty(DESKTOP_VERSION_PROPERTY)?.takeIf { value -> value.isNotBlank() }
        ?: "dev"

private fun desktopInitialPlaybackItemId(): String? =
    System
        .getProperty(DESKTOP_INITIAL_PLAYBACK_ITEM_ID_PROPERTY)
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }

private fun desktopInitialDetailItemId(): String? =
    System
        .getProperty(DESKTOP_INITIAL_DETAIL_ITEM_ID_PROPERTY)
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: parseDesktopItemDetailDeepLink(
            System.getProperty(DESKTOP_DEEP_LINK_PROPERTY),
        )

internal fun parseDesktopItemDetailDeepLink(value: String?): String? {
    val uri =
        value
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { raw -> runCatching { URI(raw) }.getOrNull() }
            ?: return null
    if (!uri.scheme.equals(DESKTOP_DEEP_LINK_SCHEME, ignoreCase = true)) return null
    if (!uri.host.equals(DESKTOP_DETAIL_DEEP_LINK_HOST, ignoreCase = true)) return null
    return uri.path
        ?.removePrefix("/")
        ?.takeIf { itemId -> itemId.isNotBlank() && "/" !in itemId }
}

private fun installDesktopDetailDeepLinkHandler(onDetailItemId: (String) -> Unit) {
    val desktop = runCatching(Desktop::getDesktop).getOrNull() ?: return
    if (!desktop.isSupported(Desktop.Action.APP_OPEN_URI)) return
    runCatching {
        desktop.setOpenURIHandler { event ->
            parseDesktopItemDetailDeepLink(event.uri.toString())?.let(onDetailItemId)
        }
    }
}

private const val DESKTOP_VERSION_PROPERTY = "jellyscope.version"
private const val DESKTOP_DEBUG_PROPERTY = "jellyscope.debug"
private const val DESKTOP_INITIAL_PLAYBACK_ITEM_ID_PROPERTY = "jellyscope.desktop.initialPlaybackItemId"
private const val DESKTOP_INITIAL_DETAIL_ITEM_ID_PROPERTY = "jellyscope.desktop.initialDetailItemId"
private const val DESKTOP_DEEP_LINK_PROPERTY = "jellyscope.desktop.deepLink"
private const val DESKTOP_DEEP_LINK_SCHEME = "jellyscope"
private const val DESKTOP_DETAIL_DEEP_LINK_HOST = "details"
