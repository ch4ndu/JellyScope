// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.discovery

import com.jellyscope.core.domain.discovery.DiscoveredServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

internal fun Flow<DiscoveredServer>.dedupeDiscoveredServers(): Flow<DiscoveredServer> =
    flow {
        val seenIds = mutableSetOf<String>()
        collect { server ->
            if (seenIds.add(server.id)) {
                emit(server)
            }
        }
    }
