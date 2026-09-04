// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.AudioTrackOption
import com.jellyscope.core.domain.playback.EmbeddedAudioSelection
import com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection
import com.jellyscope.core.domain.playback.PlaybackHealthMeasurementCapabilities
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.QualityOption
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.core.domain.playback.SubtitleTrackOption
import com.jellyscope.core.domain.playback.TrickplayInfo
import com.jellyscope.core.domain.playback.VideoOutputMeasurementCapabilities
import com.jellyscope.core.domain.playback.audioOptions
import com.jellyscope.core.domain.playback.qualityOptions
import com.jellyscope.core.domain.playback.subtitleOptions
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Inert production placeholder until the resolved native backend is installed. */
internal object PendingPlayerController : PlayerController {
    override val playbackState: StateFlow<PlaybackState> =
        MutableStateFlow(
            PlaybackState(
                status = PlaybackStatus.Loading,
                positionMs = 0L,
                durationMs = null,
                bufferedPositionMs = 0L,
                error = null,
            ),
        )
    override val runtimeDiagnostics: StateFlow<PlaybackRuntimeDiagnostics> =
        MutableStateFlow(PlaybackRuntimeDiagnostics.EMPTY)
    override val platformPlayer: Any? = null
    override val activeBackend: PlayerBackend = PlayerBackend.Auto
    override val playbackHealthMeasurementCapabilities: PlaybackHealthMeasurementCapabilities =
        PlaybackHealthMeasurementCapabilities.None
    override val videoOutputMeasurementCapabilities: VideoOutputMeasurementCapabilities =
        VideoOutputMeasurementCapabilities.Unsupported

    override fun prepare(
        plan: PlaybackPlan,
        subtitleAsset: com.jellyscope.core.domain.playback.SubtitleAsset?,
    ) = Unit

    override fun selectEmbeddedAudio(selection: EmbeddedAudioSelection) = Unit

    override fun selectEmbeddedSubtitle(selection: EmbeddedSubtitleSelection?) = Unit

    override fun setPlaybackSpeed(speed: Float) = Unit

    override fun setSubtitleStyle(style: SubtitleStyle) = Unit

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun stop() = Unit

    override fun retry() = Unit

    override fun release() = Unit
}

internal fun normalizedQueue(
    initialItemId: String,
    queue: List<String>,
): List<String> {
    val uniqueQueue = queue.filter { id -> id.isNotBlank() }.distinct()
    if (uniqueQueue.size <= 1) {
        return emptyList()
    }
    return if (uniqueQueue.firstOrNull() == initialItemId) {
        uniqueQueue
    } else {
        listOf(initialItemId) + uniqueQueue.filterNot { id -> id == initialItemId }
    }
}

const val UP_NEXT_THRESHOLD_MS = 30_000L
const val STILL_WATCHING_THRESHOLD = 3

internal const val QUEUE_METADATA_BATCH_SIZE = 100

internal const val PREVIOUS_ITEM_RESTART_THRESHOLD_MS = 5_000L

internal val playerViewModelLogger = diagnosticLogger(DiagnosticTag.PlayerViewModel)

/** Preserves list identity across unchanged publications on the ViewModel thread. */
internal class PlayerProjectionCache {
    private var trackKey: TrackKey? = null
    private var audio: List<AudioTrackOption> = emptyList()
    private var subtitles: List<SubtitleTrackOption> = emptyList()

    private var qualityKey: QualityKey? = null
    private var quality: List<QualityOption> = emptyList()

    private var trickplayKey: TrickplayKey? = null
    private var trickplayTiles: List<String> = emptyList()

    fun audioTrackOptions(
        itemId: String?,
        streams: List<PlaybackMediaStream>,
    ): List<AudioTrackOption> {
        refreshTracks(itemId, streams)
        return audio
    }

    fun subtitleTrackOptions(
        itemId: String?,
        streams: List<PlaybackMediaStream>,
    ): List<SubtitleTrackOption> {
        refreshTracks(itemId, streams)
        return subtitles
    }

    private fun refreshTracks(
        itemId: String?,
        streams: List<PlaybackMediaStream>,
    ) {
        val key = TrackKey(itemId, streams)
        if (trackKey == key) {
            return
        }
        trackKey = key
        audio = audioOptions(streams)
        subtitles = subtitleOptions(streams)
    }

    fun qualityRungs(
        itemId: String?,
        sourceBitrateBps: Long?,
    ): List<QualityOption> {
        val key = QualityKey(itemId, sourceBitrateBps)
        if (qualityKey != key) {
            qualityKey = key
            quality = qualityOptions(sourceBitrateBps)
        }
        return quality
    }

    /** Calls [buildTiles] only on a cache miss. */
    fun trickplayTileUrls(
        itemId: String?,
        serverUrl: String,
        mediaSourceId: String?,
        tileWidth: Int?,
        tileCount: Int?,
        buildTiles: () -> List<String>,
    ): List<String> {
        val key = TrickplayKey(itemId, serverUrl, mediaSourceId, tileWidth, tileCount)
        if (trickplayKey != key) {
            trickplayKey = key
            trickplayTiles = buildTiles()
        }
        return trickplayTiles
    }

    private data class TrackKey(
        val itemId: String?,
        val streams: List<PlaybackMediaStream>,
    )

    private data class QualityKey(
        val itemId: String?,
        val sourceBitrateBps: Long?,
    )

    private data class TrickplayKey(
        val itemId: String?,
        val serverUrl: String,
        val mediaSourceId: String?,
        val tileWidth: Int?,
        val tileCount: Int?,
    )
}

internal fun Map<String, TrickplayInfo?>.trickplayForMediaSource(mediaSourceId: String): TrickplayInfo? =
    this[mediaSourceId] ?: takeIf { size == 1 }?.values?.singleOrNull()

internal fun activeControllerSatisfiesBackend(
    resolvedBackend: com.jellyscope.core.domain.playback.PlayerBackend,
    trackedBackend: com.jellyscope.core.domain.playback.PlayerBackend,
    activeBackend: com.jellyscope.core.domain.playback.PlayerBackend,
): Boolean =
    resolvedBackend == trackedBackend ||
        (
            resolvedBackend == com.jellyscope.core.domain.playback.PlayerBackend.Auto &&
                trackedBackend != com.jellyscope.core.domain.playback.PlayerBackend.Auto &&
                activeBackend == trackedBackend
        )
