// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.data.local.AccountScopedClearableStore
import com.jellyscope.core.data.local.RefetchableServerCache
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.playback.DeviceDecodingCapabilities
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.PlayerBackend
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock

data class DiagnosticsSourceDescriptor(
    val videoCodec: String?,
    val width: Int?,
    val height: Int?,
    val frameRate: Double?,
    val bitRate: Long?,
    val bitDepth: Int?,
    val videoRangeType: String?,
    val container: String?,
    val audioCodec: String?,
    val channelLayout: String?,
)

data class PlaybackDiagnosticsSnapshot(
    val backend: PlayerBackend,
    val capabilities: DeviceDecodingCapabilities?,
    val source: DiagnosticsSourceDescriptor?,
)

class PlaybackDiagnosticsContext :
    RefetchableServerCache,
    AccountScopedClearableStore {
    private val lock = ReentrantLock()
    private var latestFailure: PlaybackDiagnosticsSnapshot? = null

    fun recordFailure(
        backend: PlayerBackend,
        capabilities: DeviceDecodingCapabilities?,
        source: DiagnosticsSourceDescriptor?,
    ) {
        lock.withLock {
            latestFailure = PlaybackDiagnosticsSnapshot(backend, capabilities, source)
        }
    }

    fun snapshot(): PlaybackDiagnosticsSnapshot? = lock.withLock { latestFailure }

    override suspend fun clearServerScoped() {
        clear()
    }

    override suspend fun clearServerScoped(serverId: String) {
        clear()
    }

    override suspend fun clearAccount(accountIdentity: AccountIdentity) {
        clear()
    }

    private fun clear() {
        lock.withLock {
            latestFailure = null
        }
    }
}

fun List<PlaybackMediaStream>.toDiagnosticsSourceDescriptor(
    container: String?,
    selectedAudioStreamIndex: Int?,
): DiagnosticsSourceDescriptor {
    val video = firstOrNull { stream -> stream.type.equals("Video", ignoreCase = true) }
    val audio =
        firstOrNull { stream ->
            stream.type.equals("Audio", ignoreCase = true) &&
                selectedAudioStreamIndex != null &&
                stream.index == selectedAudioStreamIndex
        } ?: firstOrNull { stream -> stream.type.equals("Audio", ignoreCase = true) }
    return DiagnosticsSourceDescriptor(
        videoCodec = video?.codec,
        width = video?.width,
        height = video?.height,
        frameRate = video?.realFrameRate,
        bitRate = video?.bitRate,
        bitDepth = video?.bitDepth,
        videoRangeType = video?.videoRangeType,
        container = container,
        audioCodec = audio?.codec,
        channelLayout = audio?.channelLayout,
    )
}
