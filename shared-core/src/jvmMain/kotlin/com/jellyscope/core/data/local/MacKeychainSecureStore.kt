// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

internal class MacKeychainSecureStore(
    private val legacyFile: File,
    private val securityFramework: MacSecurityFramework = LazyMacSecurityFramework,
    private val json: Json = Json,
) : SecureStore {
    private val mutex = Mutex()
    private var migrationAttempted = false

    override suspend fun read(key: String): String? =
        mutex.withLock {
            ensureLegacyMigration()
            withContext(Dispatchers.IO) {
                val result = securityFramework.copyGenericPassword(KEYCHAIN_SERVICE, key)
                when (result.status) {
                    ERR_SEC_SUCCESS -> result.data?.toString(StandardCharsets.UTF_8)
                    ERR_SEC_ITEM_NOT_FOUND -> null
                    else -> throw securityFailure("SecItemCopyMatching", result.status)
                }
            }
        }

    override suspend fun write(
        key: String,
        value: String,
    ) {
        mutex.withLock {
            ensureLegacyMigration()
            withContext(Dispatchers.IO) { writeItem(key, value) }
        }
    }

    override suspend fun remove(key: String) {
        mutex.withLock {
            ensureLegacyMigration()
            withContext(Dispatchers.IO) {
                deleteItem(account = key)
            }
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            ensureLegacyMigration()
            withContext(Dispatchers.IO) {
                deleteItem(account = null)
            }
        }
    }

    private suspend fun ensureLegacyMigration() {
        if (migrationAttempted) {
            return
        }

        withContext(Dispatchers.IO) {
            if (legacyFile.isFile) {
                val values =
                    runCatching {
                        json.decodeFromString<Map<String, String>>(
                            Files.readString(legacyFile.toPath(), StandardCharsets.UTF_8),
                        )
                    }.getOrDefault(emptyMap())

                values.forEach { (key, value) ->
                    writeItem(key, value)
                }
                Files.deleteIfExists(legacyFile.toPath())
            }
            migrationAttempted = true
        }
    }

    private fun writeItem(
        key: String,
        value: String,
    ) {
        val data = value.toByteArray(StandardCharsets.UTF_8)
        when (val addStatus = securityFramework.addGenericPassword(KEYCHAIN_SERVICE, key, data)) {
            ERR_SEC_SUCCESS -> Unit
            ERR_SEC_DUPLICATE_ITEM -> {
                val updateStatus = securityFramework.updateGenericPassword(KEYCHAIN_SERVICE, key, data)
                if (updateStatus != ERR_SEC_SUCCESS) {
                    throw securityFailure("SecItemUpdate", updateStatus)
                }
            }
            else -> throw securityFailure("SecItemAdd", addStatus)
        }
    }

    private fun deleteItem(account: String?) {
        val status = securityFramework.deleteGenericPassword(KEYCHAIN_SERVICE, account)
        if (status != ERR_SEC_SUCCESS && status != ERR_SEC_ITEM_NOT_FOUND) {
            throw securityFailure("SecItemDelete", status)
        }
    }

    private fun securityFailure(
        operation: String,
        status: Int,
    ): IllegalStateException = IllegalStateException("$operation failed with status=$status")

    private companion object {
        const val KEYCHAIN_SERVICE = "com.jellyscope.secure-store"
    }
}
