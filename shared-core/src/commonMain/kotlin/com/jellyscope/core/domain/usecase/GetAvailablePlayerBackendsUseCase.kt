// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.util.runCatchingCancellable

/**
 * Backends this device can actually run, read from the same domain source the
 * player falls back through ([DeviceProfileProvider.availableBackends]).
 *
 * Suspending because the read can trigger a lazy native probe (Android
 * constructs and releases LibVLC once); callers must invoke it from a work
 * dispatcher, never from composition. A probe that fails or reports nothing
 * resolves to the platform default backend, so the result is never empty.
 */
class GetAvailablePlayerBackendsUseCase(
    private val deviceProfileProvider: DeviceProfileProvider,
) {
    suspend operator fun invoke(): Set<PlayerBackend> =
        runCatchingCancellable { deviceProfileProvider.availableBackends }
            .getOrNull()
            ?.takeIf { backends -> backends.isNotEmpty() }
            ?: setOf(deviceProfileProvider.backendPolicy.defaultBackend)
}
