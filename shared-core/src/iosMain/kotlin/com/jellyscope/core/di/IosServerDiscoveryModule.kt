// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.di

import com.jellyscope.core.domain.discovery.DiscoveredServer
import com.jellyscope.core.domain.discovery.ServerDiscovery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.module.Module
import org.koin.dsl.module

val iosServerDiscoveryModule: Module =
    module {
        single<ServerDiscovery> { IosUnavailableServerDiscovery() }
    }

private class IosUnavailableServerDiscovery : ServerDiscovery {
    override val isAvailable: Boolean = false

    override fun discover(timeoutMs: Long): Flow<DiscoveredServer> = emptyFlow()
}
