// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.discovery

import com.jellyscope.core.domain.discovery.DiscoveredServer
import com.jellyscope.core.domain.discovery.ServerDiscovery
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KtorUdpServerDiscoveryTest {
    @Test
    fun parsesValidReply() {
        val server =
            parseDiscoveryReply(
                """{"Address":"http://192.168.1.10:8096","Id":"server-1","Name":"Living Room","EndpointAddress":null}""",
            )

        assertEquals(
            DiscoveredServer(
                id = "server-1",
                name = "Living Room",
                address = "http://192.168.1.10:8096",
            ),
            server,
        )
    }

    @Test
    fun skipsMalformedReply() {
        assertEquals(null, parseDiscoveryReply("""{"Address":42"""))
        assertEquals(null, parseDiscoveryReply("""{"Address":"http://192.168.1.10:8096","Id":"","Name":"Living Room"}"""))
    }

    @Test
    fun discoveryDeDupsByIdAcrossReplies() =
        runTest {
            val discovery =
                object : ServerDiscovery {
                    override fun discover(timeoutMs: Long) =
                        flow {
                            emit(DiscoveredServer("server-1", "First", "http://192.168.1.10:8096"))
                            emit(DiscoveredServer("server-1", "Duplicate", "http://192.168.1.11:8096"))
                            emit(DiscoveredServer("server-2", "Second", "http://192.168.1.12:8096"))
                        }
                }

            val servers = discovery.discover().dedupeDiscoveredServers().toList()

            assertEquals(
                listOf(
                    DiscoveredServer("server-1", "First", "http://192.168.1.10:8096"),
                    DiscoveredServer("server-2", "Second", "http://192.168.1.12:8096"),
                ),
                servers,
            )
        }
}
