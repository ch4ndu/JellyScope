// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.discovery

import kotlinx.coroutines.flow.Flow

data class DiscoveredServer(
    val id: String,
    val name: String,
    val address: String,
)

interface ServerDiscovery {
    val isAvailable: Boolean
        get() = true

    fun discover(timeoutMs: Long = 3_000): Flow<DiscoveredServer>
}
