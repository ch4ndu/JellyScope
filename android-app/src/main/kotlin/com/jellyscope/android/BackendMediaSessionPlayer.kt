// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.android

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.ui.screen.player.AndroidActivePlayer

/**
 * Media3-facing transport surface for the Android system media session.
 *
 * The application player may be Media3, mpv, or LibVLC, but MediaSession requires a
 * Media3 [Player]. This adapter contains no playback implementation: every
 * transport command is delegated to the active ViewModel callbacks and its
 * state is a projection of the active-player registry.
 */
@UnstableApi
internal class BackendMediaSessionPlayer(
    initialActivePlayer: AndroidActivePlayer,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activePlayer: AndroidActivePlayer? = initialActivePlayer
    private var pendingUpdate = false

    fun update(active: AndroidActivePlayer?) {
        activePlayer = active
        if (!pendingUpdate) {
            pendingUpdate = true
            mainHandler.post {
                pendingUpdate = false
                invalidateState()
            }
        }
    }

    override fun getState(): State = stateFor(activePlayer)

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        activePlayer?.let { active ->
            if (playWhenReady) {
                active.commandCallbacks.play()
            } else {
                active.commandCallbacks.pause()
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        activePlayer?.commandCallbacks?.setPlaybackSpeed?.invoke(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        val active = activePlayer
        if (active != null) {
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                -> active.commandCallbacks.next()

                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                -> active.commandCallbacks.previous()

                else -> active.commandCallbacks.seekTo(positionMs.coerceAtLeast(0L))
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        activePlayer?.commandCallbacks?.stop?.invoke()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> {
        activePlayer = null
        return Futures.immediateVoidFuture()
    }

    private fun stateFor(active: AndroidActivePlayer?): State {
        // Plain SEEK_TO_PREVIOUS/NEXT are always available (Previous implements the
        // restart-or-previous rule and must work on a single-item queue); only the
        // *_MEDIA_ITEM variants are gated on queue position so the notification does
        // not show non-functional skip buttons at the queue boundaries.
        val commands =
            Player.Commands
                .Builder()
                .addAll(BASE_COMMANDS)
                .apply {
                    if (active?.hasNext == true) add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    if (active?.hasPrevious == true) add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                }.build()
        val state = active?.playbackState
        if (active == null || state == null) {
            return State
                .Builder()
                .setAvailableCommands(commands)
                .setPlaybackState(Player.STATE_IDLE)
                .build()
        }

        val metadata =
            MediaMetadata
                .Builder()
                .setTitle(active.metadata.title.takeIf { it.isNotBlank() })
                .setArtist(active.metadata.seriesName)
                .setAlbumTitle(active.metadata.episodeLabel)
                .setArtworkUri(active.metadata.imageUrl?.let(android.net.Uri::parse))
                .build()
        val mediaItem =
            MediaItem
                .Builder()
                .setMediaId(active.metadata.title.ifBlank { "jellyscope-playback" })
                .setMediaMetadata(metadata)
                .build()
        val durationUs = state.durationMs?.takeIf { it >= 0L }?.let { it * 1_000L } ?: C.TIME_UNSET
        val itemData =
            MediaItemData
                .Builder(mediaItem.mediaId)
                .setMediaItem(mediaItem)
                .setMediaMetadata(metadata)
                .setIsSeekable(true)
                .setDurationUs(durationUs)
                .build()
        val playbackState =
            when (state.status) {
                PlaybackStatus.Idle,
                PlaybackStatus.Failed,
                -> Player.STATE_IDLE
                PlaybackStatus.Loading,
                PlaybackStatus.Buffering,
                -> Player.STATE_BUFFERING
                PlaybackStatus.Playing,
                PlaybackStatus.Paused,
                -> Player.STATE_READY
                PlaybackStatus.Completed -> Player.STATE_ENDED
            }
        return State
            .Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(
                state.status == PlaybackStatus.Playing || state.status == PlaybackStatus.Buffering,
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
            ).setPlaybackState(playbackState)
            .setIsLoading(state.status == PlaybackStatus.Loading || state.status == PlaybackStatus.Buffering)
            .setPlaybackParameters(PlaybackParameters(state.playbackSpeed.coerceAtLeast(0.1f)))
            .setPlaylist(listOf(itemData))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(state.positionMs.coerceAtLeast(0L))
            .build()
    }

    private companion object {
        // Commands available regardless of queue position; the *_MEDIA_ITEM
        // skip variants are added per-state based on hasNext/hasPrevious.
        private val BASE_COMMANDS: Player.Commands =
            Player.Commands
                .Builder()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_PREPARE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SET_SPEED_AND_PITCH,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_RELEASE,
                ).build()
    }
}
