// SPDX-License-Identifier: MPL-2.0

@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package com.jellyscope.core.data.local

import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

private const val LEGACY_STORE_KEY = "com.jellyscope.secure-store"
private const val KEYCHAIN_SERVICE = "com.jellyscope.secure-store"

/**
 * Device-only Apple Keychain storage for session and other secure values.
 *
 * The legacy UserDefaults blob is imported before the first Keychain access and
 * removed only after every entry has been written successfully. The migration
 * is deliberately shared by iOS and tvOS through appleMain.
 */
class AppleKeychainSecureStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val json: Json = Json,
) : SecureStore {
    private val mutex = Mutex()
    private var migrationComplete = false

    override suspend fun read(key: String): String? =
        mutex.withLock {
            withContext(Dispatchers.Default) {
                migrateLegacyStoreIfNeeded()
                readItem(key)
            }
        }

    override suspend fun write(
        key: String,
        value: String,
    ) {
        mutex.withLock {
            withContext(Dispatchers.Default) {
                migrateLegacyStoreIfNeeded()
                writeItem(key, value)
            }
        }
    }

    override suspend fun remove(key: String) {
        mutex.withLock {
            withContext(Dispatchers.Default) {
                migrateLegacyStoreIfNeeded()
                deleteItem(account = key)
            }
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            withContext(Dispatchers.Default) {
                migrateLegacyStoreIfNeeded()
                deleteItem(account = null)
            }
        }
    }

    private fun migrateLegacyStoreIfNeeded() {
        if (migrationComplete) {
            return
        }

        val encoded = defaults.stringForKey(LEGACY_STORE_KEY)
        if (encoded == null) {
            migrationComplete = true
            return
        }

        val values =
            runCatching {
                json.decodeFromString<Map<String, String>>(encoded)
            }.getOrElse { error ->
                throw IllegalStateException("Legacy secure-store migration failed", error)
            }
        values.forEach { (key, value) -> writeItem(key, value) }
        defaults.removeObjectForKey(LEGACY_STORE_KEY)
        defaults.synchronize()
        migrationComplete = true
    }

    private fun readItem(key: String): String? =
        memScoped {
            val result = alloc<COpaquePointerVar>()
            val status =
                withKeychainQuery(account = key, returnData = true) { query ->
                    SecItemCopyMatching(query, result.ptr.reinterpret())
                }
            when {
                status == errSecItemNotFound -> null
                status != errSecSuccess ->
                    throw IllegalStateException("SecItemCopyMatching failed with status=$status")
                else -> (CFBridgingRelease(result.value) as? NSData)?.toUtf8String()
            }
        }

    private fun writeItem(
        key: String,
        value: String,
    ) {
        val data = value.toNSData()
        val addStatus =
            withKeychainQuery(account = key, data = data, accessible = true) { attributes ->
                SecItemAdd(attributes, null)
            }
        when (addStatus) {
            errSecSuccess -> Unit
            errSecDuplicateItem -> {
                val updateStatus =
                    withKeychainQuery(account = key) { query ->
                        withKeychainValueUpdate(data) { attributes ->
                            SecItemUpdate(query, attributes)
                        }
                    }
                if (updateStatus != errSecSuccess) {
                    throw IllegalStateException("SecItemUpdate failed with status=$updateStatus")
                }
            }
            else -> throw IllegalStateException("SecItemAdd failed with status=$addStatus")
        }
    }

    private fun deleteItem(account: String?) {
        val status = withKeychainQuery(account = account) { query -> SecItemDelete(query) }
        if (status != errSecSuccess && status != errSecItemNotFound) {
            throw IllegalStateException("SecItemDelete failed with status=$status")
        }
    }

    /**
     * Builds a generic-password Keychain query/attribute dictionary, invokes
     * [block], and releases every bridged value afterward. A null [account]
     * scopes the operation to the whole service (used by clear()).
     */
    private fun <T> withKeychainQuery(
        account: String?,
        data: NSData? = null,
        returnData: Boolean = false,
        accessible: Boolean = false,
        block: (CFDictionaryRef?) -> T,
    ): T {
        val retained = mutableListOf<CFTypeRef?>()

        fun bridge(value: Any?): CFTypeRef? = CFBridgingRetain(value).also { ref -> retained += ref }

        val dictionary =
            CFDictionaryCreateMutable(
                null,
                0,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            ) ?: error("Could not create Keychain query dictionary")
        try {
            CFDictionaryAddValue(dictionary, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(dictionary, kSecAttrService, bridge(KEYCHAIN_SERVICE as NSString))
            CFDictionaryAddValue(dictionary, kSecAttrSynchronizable, kCFBooleanFalse)
            if (account != null) {
                CFDictionaryAddValue(dictionary, kSecAttrAccount, bridge(account as NSString))
            }
            if (data != null) {
                CFDictionaryAddValue(dictionary, kSecValueData, bridge(data))
                if (accessible) {
                    CFDictionaryAddValue(
                        dictionary,
                        kSecAttrAccessible,
                        kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                    )
                }
            }
            if (returnData) {
                CFDictionaryAddValue(dictionary, kSecReturnData, kCFBooleanTrue)
                CFDictionaryAddValue(dictionary, kSecMatchLimit, kSecMatchLimitOne)
            }
            return block(dictionary)
        } finally {
            CFBridgingRelease(dictionary)
            retained.forEach { ref -> CFBridgingRelease(ref) }
        }
    }

    private fun <T> withKeychainValueUpdate(
        data: NSData,
        block: (CFDictionaryRef?) -> T,
    ): T {
        val dictionary =
            CFDictionaryCreateMutable(
                null,
                0,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            ) ?: error("Could not create Keychain update dictionary")
        val dataRef = CFBridgingRetain(data)
        try {
            CFDictionaryAddValue(dictionary, kSecValueData, dataRef)
            return block(dictionary)
        } finally {
            CFBridgingRelease(dictionary)
            CFBridgingRelease(dataRef)
        }
    }

    private fun String.toNSData(): NSData = (this as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: NSData()

    private fun NSData.toUtf8String(): String? = NSString.create(this, NSUTF8StringEncoding) as String?
}
