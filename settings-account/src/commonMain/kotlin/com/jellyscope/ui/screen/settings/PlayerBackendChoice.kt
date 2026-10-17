// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerBackendPolicy

data class PlayerBackendChoice(
    val backend: PlayerBackend,
    val available: Boolean,
)

/** Uses policy order and assumes availability until the runtime probe completes. */
fun playerBackendChoices(
    policy: PlayerBackendPolicy,
    availableBackends: Set<PlayerBackend> = policy.visibleBackends.toSet(),
): List<PlayerBackendChoice> =
    policy.visibleBackends.map { backend ->
        PlayerBackendChoice(
            backend = backend,
            available = backend in availableBackends,
        )
    }
