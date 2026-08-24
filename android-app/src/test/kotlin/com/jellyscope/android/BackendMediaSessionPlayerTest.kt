// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.android

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.jellyscope.core.domain.playback.NoopPlayerController
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.ui.screen.player.AndroidActivePlayer
import com.jellyscope.ui.screen.player.PlayerMediaMetadata
import com.jellyscope.ui.screen.player.PlayerPlatformCommandCallbacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class BackendMediaSessionPlayerTest {
    @Test
    fun boundedVodExposesAndDispatchesEveryInItemSeekCommand() {
        val recorder = CommandRecorder()
        val projection = BackendMediaSessionProjection(activePlayer(isSeekable = true, recorder = recorder))

        IN_ITEM_SEEK_COMMANDS.forEach { command ->
            assertTrue(projection.availableCommandCodes().contains(command))
            projection.handleSeek(positionMs = 1_234L, seekCommand = command)
        }

        assertEquals(List(IN_ITEM_SEEK_COMMANDS.size) { 1_234L }, recorder.seekPositions)
    }

    @Test
    fun unknownTimelineRejectsInItemSeeksButPreservesQueueCommandsAndBoundaries() {
        val recorder = CommandRecorder()
        val projection =
            BackendMediaSessionProjection(
                activePlayer(
                    isSeekable = false,
                    hasNext = true,
                    hasPrevious = false,
                    recorder = recorder,
                ),
            )

        val commands = projection.availableCommandCodes()
        IN_ITEM_SEEK_COMMANDS.forEach { command ->
            assertFalse(commands.contains(command))
            projection.handleSeek(positionMs = 1_234L, seekCommand = command)
        }
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))

        projection.handleSeek(0L, Player.COMMAND_SEEK_TO_NEXT)
        projection.handleSeek(0L, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        projection.handleSeek(0L, Player.COMMAND_SEEK_TO_PREVIOUS)
        projection.handleSeek(0L, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)

        assertEquals(2, recorder.nextCount)
        assertEquals(1, recorder.previousCount)
        assertTrue(recorder.seekPositions.isEmpty())
    }

    @Test
    fun replacementAndClearNeverRetainAStaleSeekableCommandSet() {
        val retiring = CommandRecorder()
        val current = CommandRecorder()
        val projection =
            BackendMediaSessionProjection(
                activePlayer(
                    isSeekable = true,
                    recorder = retiring,
                    hasNext = true,
                    hasPrevious = true,
                ),
            )
        assertTrue(projection.availableCommandCodes().contains(Player.COMMAND_SEEK_FORWARD))
        assertTrue(projection.availableCommandCodes().contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertTrue(projection.availableCommandCodes().contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))

        projection.update(activePlayer(isSeekable = false, recorder = current))
        val replacementCommands = projection.availableCommandCodes()
        assertFalse(replacementCommands.contains(Player.COMMAND_SEEK_FORWARD))
        assertFalse(replacementCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertFalse(replacementCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
        projection.handleSeek(2_000L, Player.COMMAND_SEEK_FORWARD)
        projection.handleSeek(0L, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        projection.handleSeek(0L, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)

        projection.update(null)
        assertFalse(projection.availableCommandCodes().contains(Player.COMMAND_SEEK_FORWARD))
        projection.handleSeek(3_000L, Player.COMMAND_SEEK_FORWARD)

        assertTrue(retiring.seekPositions.isEmpty())
        assertTrue(current.seekPositions.isEmpty())
        assertEquals(0, current.nextCount)
        assertEquals(0, current.previousCount)
    }

    private fun activePlayer(
        isSeekable: Boolean,
        recorder: CommandRecorder,
        hasNext: Boolean = false,
        hasPrevious: Boolean = false,
    ): AndroidActivePlayer =
        AndroidActivePlayer(
            controller = NoopPlayerController(),
            playbackState =
                PlaybackState(
                    status = PlaybackStatus.Paused,
                    positionMs = 0L,
                    durationMs = null,
                    bufferedPositionMs = 0L,
                ),
            metadata = PlayerMediaMetadata(),
            videoWidth = null,
            videoHeight = null,
            isSeekable = isSeekable,
            playbackItemId = "item",
            sourceRect = null,
            commandCallbacks = recorder.callbacks,
            hasNext = hasNext,
            hasPrevious = hasPrevious,
            onCloseFromPictureInPicture = {},
        )

    private class CommandRecorder {
        val seekPositions = mutableListOf<Long>()
        var nextCount = 0
        var previousCount = 0

        val callbacks =
            PlayerPlatformCommandCallbacks(
                play = {},
                pause = {},
                toggle = {},
                seekTo = { position -> seekPositions += position },
                next = { nextCount += 1 },
                previous = { previousCount += 1 },
                stop = {},
            )
    }

    private companion object {
        val IN_ITEM_SEEK_COMMANDS =
            listOf(
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
            )
    }
}
