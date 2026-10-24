// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import com.jellyscope.core.data.remote.AuthHeaderProvider
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.security.CredentialOriginGuard
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

internal data class LauncherConfigurationResult(
    val status: LauncherConfigurationStatus,
    val layout: LauncherLayout? = null,
)

internal class LauncherConfigurationRepository(
    private val client: HttpClient,
    private val authorization: AuthHeaderProvider,
    private val json: Json,
) {
    suspend fun fetch(session: Session): LauncherConfigurationResult {
        val url = "${session.serverUrl.trimEnd('/')}/Launcher/Configuration"
        if (session.accessToken.isBlank() || !CredentialOriginGuard(session.serverUrl).mayAttachCredentials(url)) {
            return LauncherConfigurationResult(LauncherConfigurationStatus.Unavailable)
        }
        return try {
            client
                .prepareGet(url) {
                    expectSuccess = false
                    header(HttpHeaders.Authorization, authorization.authHeader(session.accessToken))
                    header(HttpHeaders.Accept, "application/json")
                }.execute { response ->
                    if (response.status.value !in 200..299) {
                        return@execute LauncherConfigurationResult(LauncherConfigurationStatus.Unavailable)
                    }
                    val bounded = ByteArray(MAX_CONFIGURATION_BYTES + 1)
                    val channel = response.bodyAsChannel()
                    var count = 0
                    while (count < bounded.size) {
                        val read = channel.readAvailable(bounded, count, bounded.size - count)
                        if (read < 0) break
                        count += read
                    }
                    if (count > MAX_CONFIGURATION_BYTES) {
                        return@execute LauncherConfigurationResult(LauncherConfigurationStatus.Invalid)
                    }
                    decode(bounded, count)
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            launcherFailure("configuration-fetch", failure)
            LauncherConfigurationResult(LauncherConfigurationStatus.Unavailable)
        }
    }

    private fun decode(
        bytes: ByteArray,
        count: Int,
    ): LauncherConfigurationResult =
        try {
            val body = bytes.decodeToString(0, count, throwOnInvalidSequence = true)
            val root = json.parseToJsonElement(body) as? JsonObject ?: error("Invalid layout object")
            val schema = (root["schemaVersion"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            if (schema != null && schema != 1) {
                LauncherConfigurationResult(LauncherConfigurationStatus.Unsupported)
            } else {
                validateWireTypes(root)
                LauncherConfigurationResult(
                    LauncherConfigurationStatus.Current,
                    json.decodeFromJsonElement<LauncherLayout>(root).validated(),
                )
            }
        } catch (failure: Exception) {
            launcherFailure("configuration-decode", failure)
            LauncherConfigurationResult(LauncherConfigurationStatus.Invalid)
        }

    private fun validateWireTypes(root: JsonObject) {
        require(root.string("revision") != null && root.string("playbackPreference") != null)
        val schema = root["schemaVersion"] as? JsonPrimitive
        require(schema != null && !schema.isString && schema.intOrNull == 1)
        val rows = root["rows"] as? JsonArray ?: error("Missing layout rows")
        rows.forEach { value ->
            val row = value as? JsonObject ?: error("Invalid row object")
            listOf("id", "title", "aspect", "query").forEach { require(row.string(it) != null) }
            val visible = row["visible"] as? JsonPrimitive
            val limit = row["limit"] as? JsonPrimitive
            require(visible != null && !visible.isString && visible.booleanOrNull != null)
            require(limit != null && !limit.isString && limit.intOrNull != null)
            require(row["libraryId"] == JsonNull || row.string("libraryId") != null)
            require("tab" !in row || row["tab"] == JsonNull || row.string("tab") != null)
        }
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val MAX_CONFIGURATION_BYTES = 64 * 1024
    }
}
