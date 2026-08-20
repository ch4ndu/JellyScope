// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.discovery

import com.jellyscope.core.domain.discovery.DiscoveredServer
import com.jellyscope.core.domain.discovery.ServerDiscovery
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.utils.io.core.buildPacket
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.core.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class KtorUdpServerDiscovery(
    private val json: Json,
) : ServerDiscovery {
    override val isAvailable: Boolean = true

    override fun discover(timeoutMs: Long): Flow<DiscoveredServer> =
        flow {
            if (timeoutMs <= 0) {
                return@flow
            }

            val selectorManager = SelectorManager(Dispatchers.Default)
            var socket: BoundDatagramSocket? = null
            try {
                val boundSocket =
                    aSocket(selectorManager)
                        .udp()
                        .bind(hostname = BIND_HOST, port = EPHEMERAL_PORT) {
                            broadcast = true
                            reuseAddress = true
                        }
                socket = boundSocket

                boundSocket.send(
                    Datagram(
                        packet = buildPacket { writeText(DISCOVERY_PAYLOAD) },
                        address = InetSocketAddress(BROADCAST_HOST, DISCOVERY_PORT),
                    ),
                )

                withTimeoutOrNull(timeoutMs) {
                    flow {
                        while (true) {
                            val payload = boundSocket.receive().packet.readText()
                            parseDiscoveryReply(payload, json)?.let { server ->
                                emit(server)
                            }
                        }
                    }.dedupeDiscoveredServers()
                        .collect { server -> emit(server) }
                }
            } finally {
                socket?.close()
                selectorManager.close()
            }
        }
}

internal fun parseDiscoveryReply(
    payload: String,
    json: Json = discoveryJson,
): DiscoveredServer? =
    runCatching {
        val reply = json.decodeFromString<DiscoveryReplyDto>(payload)
        val id = reply.id.trim()
        val name = reply.name.trim()
        val address = reply.address.trim()

        if (id.isBlank() || name.isBlank() || address.isBlank()) {
            null
        } else {
            DiscoveredServer(
                id = id,
                name = name,
                address = address,
            )
        }
    }.getOrNull()

private const val BIND_HOST = "0.0.0.0"
private const val BROADCAST_HOST = "255.255.255.255"
private const val DISCOVERY_PORT = 7359
private const val EPHEMERAL_PORT = 0
private const val DISCOVERY_PAYLOAD = "Who is JellyfinServer?"

private val discoveryJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

@Serializable
private data class DiscoveryReplyDto(
    @SerialName("Address")
    val address: String,
    @SerialName("Id")
    val id: String,
    @SerialName("Name")
    val name: String,
)
