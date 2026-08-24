// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.PlaybackSelectionKey
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackLaunchContext
import com.jellyscope.core.domain.playback.PlaybackLaunchReadOutcome
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Reads the independent durable inputs for one exact playback launch. */
class GetPlaybackLaunchContextUseCase(
    private val getPlaybackPreferences: GetPlaybackPreferencesUseCase? = null,
    private val getPlaybackSelection: GetPlaybackSelectionUseCase? = null,
    private val getSubtitleSelection: GetSubtitleSelectionUseCase? = null,
) {
    suspend operator fun invoke(
        session: Session,
        itemId: String,
        mediaSourceId: String,
    ): PlaybackLaunchContext {
        val playbackSelectionKey =
            PlaybackSelectionKey(
                serverId = session.serverId,
                userId = session.userId,
                itemId = itemId,
                mediaSourceId = mediaSourceId,
            )
        val subtitleSelectionKey =
            SubtitleSelectionKey(
                serverId = session.serverId,
                userId = session.userId,
                itemId = itemId,
                mediaSourceId = mediaSourceId,
            )

        return coroutineScope {
            val preferencesRead =
                async {
                    readOptional(getPlaybackPreferences) { useCase ->
                        useCase(session.serverId).normalized()
                    }
                }
            val playbackSelectionRead =
                async {
                    readOptional(getPlaybackSelection) { useCase ->
                        useCase(playbackSelectionKey)?.normalized()
                    }
                }
            val subtitleSelectionRead =
                async {
                    readOptional(getSubtitleSelection) { useCase ->
                        useCase(subtitleSelectionKey)
                    }
                }

            val preferences = preferencesRead.await()
            val playbackSelection = playbackSelectionRead.await()
            val subtitleSelection = subtitleSelectionRead.await()
            PlaybackLaunchContext(
                playbackPreferences = preferences.value ?: PlaybackPreferences().normalized(),
                playbackSelection = playbackSelection.value,
                subtitleSelection = subtitleSelection.value,
                playbackPreferencesOutcome = preferences.outcome,
                playbackSelectionOutcome = playbackSelection.outcome,
                subtitleSelectionOutcome = subtitleSelection.outcome,
            )
        }
    }
}

private data class PlaybackLaunchRead<T>(
    val value: T?,
    val outcome: PlaybackLaunchReadOutcome,
)

private suspend fun <UseCase, Value> readOptional(
    useCase: UseCase?,
    read: suspend (UseCase) -> Value?,
): PlaybackLaunchRead<Value> {
    if (useCase == null) {
        return PlaybackLaunchRead(value = null, outcome = PlaybackLaunchReadOutcome.Unavailable)
    }
    return try {
        val value = read(useCase)
        PlaybackLaunchRead(
            value = value,
            outcome =
                if (value == null) {
                    PlaybackLaunchReadOutcome.Missing
                } else {
                    PlaybackLaunchReadOutcome.Present
                },
        )
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Throwable) {
        PlaybackLaunchRead(value = null, outcome = PlaybackLaunchReadOutcome.Failed)
    }
}
