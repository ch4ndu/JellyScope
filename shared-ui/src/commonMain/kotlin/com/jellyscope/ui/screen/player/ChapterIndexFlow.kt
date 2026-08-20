// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.Chapter
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.currentChapterIndex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

fun Flow<PlaybackState>.chapterIndexFlow(chapters: List<Chapter>): Flow<Int> =
    map { state -> chapters.currentChapterIndex(state.positionMs) }
        .distinctUntilChanged()
