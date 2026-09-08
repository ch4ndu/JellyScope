// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlayerController
import platform.Foundation.NSNumber
import platform.Foundation.numberWithDouble
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatus
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.MediaPlayer.MPSkipIntervalCommandEvent
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private const val NOW_PLAYING_SKIP_INTERVAL_SECONDS = 15.0
private const val MS_PER_SECOND = 1_000.0

@Composable
actual fun PlayerNowPlayingEffects(
    controller: PlayerController,
    content: PlayerUiState.Content?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onToggle: () -> Unit,
    onSeekTo: (Long) -> Unit,
) {
    val currentOnPlay = rememberUpdatedState(onPlay)
    val currentOnPause = rememberUpdatedState(onPause)
    val currentOnToggle = rememberUpdatedState(onToggle)
    val currentOnSeekTo = rememberUpdatedState(onSeekTo)
    val currentMetadata = rememberUpdatedState(content?.metadata)

    val manager =
        remember(controller) {
            IosNowPlayingManager(
                currentPositionMs = { controller.playbackState.value.positionMs },
                onPlay = { currentOnPlay.value() },
                onPause = { currentOnPause.value() },
                onToggle = { currentOnToggle.value() },
                onSeekTo = { positionMs -> currentOnSeekTo.value(positionMs) },
            )
        }
    DisposableEffect(manager) {
        manager.activate()
        onDispose { manager.release() }
    }
    // Metadata-keyed push: a metadata-only transition (Content -> Error drops
    // metadata without a new controller emission) must still clear/refresh the
    // published info and the command gate immediately.
    val metadata = content?.metadata
    LaunchedEffect(manager, metadata) {
        manager.update(metadata = metadata, state = controller.playbackState.value)
    }
    LaunchedEffect(manager) {
        controller.playbackState.collect { state ->
            manager.update(metadata = currentMetadata.value, state = state)
        }
    }
}

/**
 * Publishes Now Playing metadata and registers remote transport commands
 * (lock screen / Control Center). Command handlers hop to the main queue and
 * route through the ViewModel-level callbacks so remote seeks honor the
 * transcode-window restart and reporting rules. Info pushes happen on edges
 * (title/duration/status changes, or a position jump) — iOS extrapolates
 * elapsed time from the published rate between pushes.
 */
private class IosNowPlayingManager(
    private val currentPositionMs: () -> Long,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onToggle: () -> Unit,
    private val onSeekTo: (Long) -> Unit,
) {
    private val commandCenter = MPRemoteCommandCenter.sharedCommandCenter()
    private val commandTargets = mutableListOf<Pair<platform.MediaPlayer.MPRemoteCommand, Any?>>()
    private var released = false

    // True only while content metadata is published; remote commands are inert
    // otherwise, so lock-screen transport cannot act on a failed/torn-down item.
    private var hasActiveContent = false

    private var publishedSnapshot: NowPlayingSnapshot? = null

    fun activate() {
        registerCommand(commandCenter.playCommand) {
            onPlay()
            MPRemoteCommandHandlerStatusSuccess
        }
        registerCommand(commandCenter.pauseCommand) {
            onPause()
            MPRemoteCommandHandlerStatusSuccess
        }
        registerCommand(commandCenter.togglePlayPauseCommand) {
            onToggle()
            MPRemoteCommandHandlerStatusSuccess
        }
        commandCenter.skipForwardCommand.preferredIntervals =
            listOf(NSNumber.numberWithDouble(NOW_PLAYING_SKIP_INTERVAL_SECONDS))
        registerCommandWithEvent(commandCenter.skipForwardCommand) { event ->
            val intervalMs = event.skipIntervalMs() ?: (NOW_PLAYING_SKIP_INTERVAL_SECONDS * MS_PER_SECOND).toLong()
            onSeekTo(currentPositionMs() + intervalMs)
            MPRemoteCommandHandlerStatusSuccess
        }
        commandCenter.skipBackwardCommand.preferredIntervals =
            listOf(NSNumber.numberWithDouble(NOW_PLAYING_SKIP_INTERVAL_SECONDS))
        registerCommandWithEvent(commandCenter.skipBackwardCommand) { event ->
            val intervalMs = event.skipIntervalMs() ?: (NOW_PLAYING_SKIP_INTERVAL_SECONDS * MS_PER_SECOND).toLong()
            onSeekTo((currentPositionMs() - intervalMs).coerceAtLeast(0L))
            MPRemoteCommandHandlerStatusSuccess
        }
        registerCommandWithEvent(commandCenter.changePlaybackPositionCommand) { event ->
            val positionEvent = event as? MPChangePlaybackPositionCommandEvent
            if (positionEvent != null) {
                onSeekTo((positionEvent.positionTime * MS_PER_SECOND).toLong().coerceAtLeast(0L))
            }
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    fun update(
        metadata: PlayerMediaMetadata?,
        state: PlaybackState,
    ) {
        if (released) return
        val title = metadata?.title?.takeIf(String::isNotBlank)
        hasActiveContent = title != null
        val artist = metadata?.seriesName ?: metadata?.episodeLabel
        val next =
            title?.let {
                NowPlayingSnapshot(
                    title = it,
                    artist = artist,
                    durationMs = state.durationMs,
                    status = state.status,
                    positionMs = state.positionMs,
                    playbackSpeed = state.playbackSpeed,
                )
            }
        when (nowPlayingPublicationDecision(previous = publishedSnapshot, next = next)) {
            NowPlayingPublication.Clear -> {
                val hadPublishedSnapshot = publishedSnapshot != null
                publishedSnapshot = null
                // Content dropped (fatal error state, item teardown): clear
                // stale lock-screen info instead of leaving the last item frozen
                // there.
                if (hadPublishedSnapshot) {
                    MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
                }
                return
            }
            NowPlayingPublication.NoChange -> return
            NowPlayingPublication.PublishFull -> Unit
        }

        val snapshot = next ?: return
        publishedSnapshot = snapshot
        val rate = nowPlayingPlaybackRate(status = snapshot.status, playbackSpeed = snapshot.playbackSpeed)
        val info = mutableMapOf<Any?, Any?>()
        info[MPMediaItemPropertyTitle] = snapshot.title
        snapshot.artist?.let { value -> info[MPMediaItemPropertyArtist] = value }
        snapshot.durationMs?.let { durationMs ->
            info[MPMediaItemPropertyPlaybackDuration] = durationMs.toDouble() / MS_PER_SECOND
        }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = snapshot.positionMs.toDouble() / MS_PER_SECOND
        info[MPNowPlayingInfoPropertyPlaybackRate] = rate
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
    }

    fun release() {
        if (released) return
        released = true
        commandTargets.forEach { (command, target) -> command.removeTarget(target) }
        commandTargets.clear()
        publishedSnapshot = null
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
    }

    private fun registerCommand(
        command: platform.MediaPlayer.MPRemoteCommand,
        handler: () -> MPRemoteCommandHandlerStatus,
    ) {
        registerCommandWithEvent(command) { handler() }
    }

    // MPRemoteCommandCenter does not guarantee handler thread; hop to main
    // before touching the ViewModel/controller (both assume the main thread).
    private fun registerCommandWithEvent(
        command: platform.MediaPlayer.MPRemoteCommand,
        handler: (platform.MediaPlayer.MPRemoteCommandEvent?) -> MPRemoteCommandHandlerStatus,
    ) {
        val target =
            command.addTargetWithHandler { event ->
                dispatch_async(dispatch_get_main_queue()) {
                    if (!released && hasActiveContent) handler(event)
                }
                MPRemoteCommandHandlerStatusSuccess
            }
        commandTargets.add(command to target)
    }

    private fun platform.MediaPlayer.MPRemoteCommandEvent?.skipIntervalMs(): Long? =
        (this as? MPSkipIntervalCommandEvent)?.interval?.let { seconds -> (seconds * MS_PER_SECOND).toLong() }
}
