// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.Chapter
import com.jellyscope.core.domain.playback.JELLYFIN_TICKS_PER_MILLISECOND
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterIndexFlowTest {
    @Test
    fun chapterIndexFlowEmitsOnlyAtChapterBoundaries() =
        runTest {
            val chapters =
                listOf(
                    Chapter("Opening", 0L),
                    Chapter("Middle", 3_000L * JELLYFIN_TICKS_PER_MILLISECOND),
                    Chapter("Ending", 6_000L * JELLYFIN_TICKS_PER_MILLISECOND),
                )
            val playbackState = MutableStateFlow(playbackState(positionMs = 0L))
            val indices = mutableListOf<Int>()
            val collection =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    playbackState.chapterIndexFlow(chapters).toList(indices)
                }

            playbackState.value = playbackState(positionMs = 1_000L)
            playbackState.value = playbackState(positionMs = 3_000L)
            playbackState.value = playbackState(positionMs = 4_000L)
            playbackState.value = playbackState(positionMs = 6_000L)
            collection.cancel()

            assertEquals(listOf(0, 1, 2), indices)
        }

    private fun playbackState(positionMs: Long) =
        PlaybackState(
            status = PlaybackStatus.Playing,
            positionMs = positionMs,
            durationMs = 10_000L,
            bufferedPositionMs = positionMs,
        )
}
