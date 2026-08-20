// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.jellyscope.core.domain.playback.PlayerController
import com.sun.jna.Platform

@Composable
actual fun PlayerNowPlayingEffects(
    controller: PlayerController,
    content: PlayerUiState.Content?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
) {
    val currentOnPlay = rememberUpdatedState(onPlay)
    val currentOnPause = rememberUpdatedState(onPause)
    val currentOnSeekTo = rememberUpdatedState(onSeekTo)
    val currentMetadata = rememberUpdatedState(content?.metadata)
    val bridge =
        remember(controller) {
            if (!Platform.isMac()) {
                null
            } else {
                loadMacosNowPlayingBridge {
                    MacosNowPlayingBridge(
                        currentPositionMs = { controller.playbackState.value.positionMs },
                        onPlay = { currentOnPlay.value() },
                        onPause = { currentOnPause.value() },
                        onSeekTo = { positionMs -> currentOnSeekTo.value(positionMs) },
                    )
                }
            }
        }

    DisposableEffect(bridge) {
        bridge?.install()
        onDispose { bridge?.teardown() }
    }

    val metadata = content?.metadata
    LaunchedEffect(bridge, metadata) {
        bridge?.update(metadata = metadata, state = controller.playbackState.value)
    }
    LaunchedEffect(bridge) {
        if (bridge != null) {
            controller.playbackState.collect { state ->
                bridge.update(metadata = currentMetadata.value, state = state)
            }
        }
    }
}

internal fun loadMacosNowPlayingBridge(
    failureReporter: MacosNowPlayingFailureReporter = DefaultMacosNowPlayingFailureReporter,
    factory: () -> MacosNowPlayingBridge,
): MacosNowPlayingBridge? =
    runCatching(factory)
        .onFailure { failure -> failureReporter.report(MacosNowPlayingFailureEvent.Load, failure) }
        .getOrNull()
