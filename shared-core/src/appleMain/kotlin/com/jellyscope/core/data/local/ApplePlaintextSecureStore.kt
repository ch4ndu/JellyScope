// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

private const val APPLE_PLAINTEXT_SECURE_STORE_KEY = "com.jellyscope.secure-store"

/** App-owned plaintext persistence for iOS and tvOS session values. */
internal class ApplePlaintextSecureStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val json: Json = Json,
) : SecureStore {
    private val mutex = Mutex()

    override suspend fun read(key: String): String? =
        mutex.withLock {
            readMap()[key]
        }

    override suspend fun write(
        key: String,
        value: String,
    ) {
        mutex.withLock {
            val updated = readMap().toMutableMap()
            updated[key] = value
            writeMap(updated)
        }
    }

    override suspend fun remove(key: String) {
        mutex.withLock {
            val updated = readMap().toMutableMap()
            updated.remove(key)
            writeMap(updated)
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            writeMap(emptyMap())
        }
    }

    private fun readMap(): Map<String, String> =
        defaults.stringForKey(APPLE_PLAINTEXT_SECURE_STORE_KEY)
            ?.let { encoded ->
                runCatching { json.decodeFromString<Map<String, String>>(encoded) }.getOrNull()
            }.orEmpty()

    private fun writeMap(values: Map<String, String>) {
        val previousEncoded = defaults.stringForKey(APPLE_PLAINTEXT_SECURE_STORE_KEY)
        val updatedEncoded = if (values.isEmpty()) null else json.encodeToString(values)
        if (updatedEncoded == null) {
            defaults.removeObjectForKey(APPLE_PLAINTEXT_SECURE_STORE_KEY)
        } else {
            defaults.setObject(
                updatedEncoded,
                forKey = APPLE_PLAINTEXT_SECURE_STORE_KEY,
            )
        }
        if (!defaults.synchronize()) {
            if (previousEncoded == null) {
                defaults.removeObjectForKey(APPLE_PLAINTEXT_SECURE_STORE_KEY)
            } else {
                defaults.setObject(previousEncoded, forKey = APPLE_PLAINTEXT_SECURE_STORE_KEY)
            }
            defaults.synchronize()
            throw IllegalStateException("Unable to durably save plaintext secure store.")
        }
    }
}
