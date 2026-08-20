// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.data.repository.MediaRepository
import com.jellyscope.core.domain.model.SendClientLogsResult
import com.jellyscope.core.domain.platform.DiagnosticsEnvironment
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.playback.PlaybackDiagnosticsContext
import com.jellyscope.core.util.DiagnosticsReport
import com.jellyscope.core.util.LogBufferStore
import kotlinx.coroutines.CancellationException

class SendClientLogsAction(
    private val logBufferStore: LogBufferStore,
    private val mediaRepository: MediaRepository,
    private val diagnosticsEnvironment: DiagnosticsEnvironment,
    private val deviceProfileProvider: DeviceProfileProvider,
    private val playbackDiagnosticsContext: PlaybackDiagnosticsContext,
) {
    /** Uploads a capability snapshot even when log collection is disabled or empty. */
    suspend operator fun invoke(preferredBackend: PlayerBackend? = null): SendClientLogsResult? {
        val snapshot = logBufferStore.snapshot()
        val playbackSnapshot = playbackDiagnosticsContext.snapshot()
        val backend =
            selectDiagnosticsBackend(
                preferredBackend = preferredBackend,
                recentFailureBackend = playbackSnapshot?.backend,
                deviceProfileProvider = deviceProfileProvider,
            )
        val compatiblePlaybackSnapshot = playbackSnapshot?.takeIf { failure -> failure.backend == backend }
        val capabilities =
            if (compatiblePlaybackSnapshot != null) {
                compatiblePlaybackSnapshot.capabilities
            } else {
                try {
                    deviceProfileProvider.capabilities(backend)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Throwable) {
                    null
                }
            }
        val report =
            DiagnosticsReport.build(
                environment = diagnosticsEnvironment,
                backend = backend,
                capabilities = capabilities,
                source = compatiblePlaybackSnapshot?.source,
            )
        val bufferContributed = snapshot.text.isNotBlank()
        val payload =
            buildString {
                append(report)
                if (bufferContributed) {
                    appendLine()
                    append(snapshot.text)
                }
            }
        if (payload.isBlank()) return null
        return mediaRepository.uploadClientLogs(payload).also { result ->
            if (result is SendClientLogsResult.Success && bufferContributed) {
                logBufferStore.clearThrough(snapshot.acknowledgement)
            }
        }
    }
}

private fun selectDiagnosticsBackend(
    preferredBackend: PlayerBackend?,
    recentFailureBackend: PlayerBackend?,
    deviceProfileProvider: DeviceProfileProvider,
): PlayerBackend {
    val policy = deviceProfileProvider.backendPolicy
    val availableBackends = deviceProfileProvider.availableBackends
    val normalizedPreference = policy.normalizePersisted(preferredBackend)
    val supportedPreference =
        normalizedPreference.takeIf { backend ->
            backend == PlayerBackend.Auto || backend in availableBackends
        }

    if (supportedPreference != null && supportedPreference != PlayerBackend.Auto) {
        return supportedPreference
    }

    if (supportedPreference == PlayerBackend.Auto) {
        recentFailureBackend
            ?.takeIf { backend -> backend != PlayerBackend.Auto && backend in availableBackends }
            ?.let { backend -> return backend }
    }

    val normalizedDefault = policy.normalizePersisted(policy.defaultBackend)
    return normalizedDefault.takeIf { backend ->
        backend == PlayerBackend.Auto || backend in availableBackends
    } ?: policy.defaultBackend
}
