// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.domain.discovery.ServerDiscovery

class DiscoverServersUseCase(
    private val serverDiscovery: ServerDiscovery,
) {
    val isAvailable: Boolean
        get() = serverDiscovery.isAvailable

    operator fun invoke(timeoutMs: Long = 3_000) = serverDiscovery.discover(timeoutMs)
}
