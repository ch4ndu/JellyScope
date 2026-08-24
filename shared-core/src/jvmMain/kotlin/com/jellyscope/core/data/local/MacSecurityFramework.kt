// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

internal data class MacKeychainCopyResult(
    val status: Int,
    val data: ByteArray? = null,
)

internal interface MacSecurityFramework {
    fun copyGenericPassword(
        service: String,
        account: String,
    ): MacKeychainCopyResult

    fun addGenericPassword(
        service: String,
        account: String,
        data: ByteArray,
    ): Int

    fun updateGenericPassword(
        service: String,
        account: String,
        data: ByteArray,
    ): Int

    fun deleteGenericPassword(
        service: String,
        account: String?,
    ): Int
}

internal object LazyMacSecurityFramework : MacSecurityFramework {
    private val delegate: MacSecurityFramework by lazy { JnaMacSecurityFramework() }

    override fun copyGenericPassword(
        service: String,
        account: String,
    ): MacKeychainCopyResult = delegate.copyGenericPassword(service, account)

    override fun addGenericPassword(
        service: String,
        account: String,
        data: ByteArray,
    ): Int = delegate.addGenericPassword(service, account, data)

    override fun updateGenericPassword(
        service: String,
        account: String,
        data: ByteArray,
    ): Int = delegate.updateGenericPassword(service, account, data)

    override fun deleteGenericPassword(
        service: String,
        account: String?,
    ): Int = delegate.deleteGenericPassword(service, account)
}

internal class JnaMacSecurityFramework(
    private val native: MacSecurityNative = JnaMacSecurityNative,
) : MacSecurityFramework {
    override fun copyGenericPassword(
        service: String,
        account: String,
    ): MacKeychainCopyResult {
        val result = PointerByReference()
        val status =
            withQuery(
                service = service,
                account = account,
                returnData = true,
            ) { query ->
                native.secItemCopyMatching(query, result)
            }
        val dataPointer = result.value
        return try {
            MacKeychainCopyResult(
                status = status,
                data =
                    if (status == ERR_SEC_SUCCESS && dataPointer != null) {
                        native.dataBytes(dataPointer)
                    } else {
                        null
                    },
            )
        } finally {
            dataPointer?.let(native::release)
        }
    }

    override fun addGenericPassword(
        service: String,
        account: String,
        data: ByteArray,
    ): Int =
        withQuery(
            service = service,
            account = account,
            data = data,
            accessible = true,
        ) { attributes ->
            native.secItemAdd(attributes)
        }

    override fun updateGenericPassword(
        service: String,
        account: String,
        data: ByteArray,
    ): Int =
        withQuery(service = service, account = account) { query ->
            withValueUpdate(data) { attributes ->
                native.secItemUpdate(query, attributes)
            }
        }

    override fun deleteGenericPassword(
        service: String,
        account: String?,
    ): Int =
        withQuery(service = service, account = account) { query ->
            native.secItemDelete(query)
        }

    private fun <T> withQuery(
        service: String,
        account: String?,
        data: ByteArray? = null,
        returnData: Boolean = false,
        accessible: Boolean = false,
        block: (Pointer) -> T,
    ): T {
        val ownedValues = mutableListOf<Pointer>()

        fun own(value: Pointer?): Pointer =
            checkNotNull(value) { "CoreFoundation value allocation failed." }
                .also(ownedValues::add)

        val dictionary =
            checkNotNull(native.createMutableDictionary()) {
                "CoreFoundation dictionary allocation failed."
            }
        try {
            native.dictionaryAddValue(dictionary, native.secClass, native.secClassGenericPassword)
            native.dictionaryAddValue(dictionary, native.secAttrService, own(native.createString(service)))
            native.dictionaryAddValue(dictionary, native.secAttrSynchronizable, native.booleanFalse)
            if (account != null) {
                native.dictionaryAddValue(dictionary, native.secAttrAccount, own(native.createString(account)))
            }
            if (data != null) {
                native.dictionaryAddValue(dictionary, native.secValueData, own(native.createData(data)))
                if (accessible) {
                    native.dictionaryAddValue(
                        dictionary,
                        native.secAttrAccessible,
                        native.secAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                    )
                }
            }
            if (returnData) {
                native.dictionaryAddValue(dictionary, native.secReturnData, native.booleanTrue)
                native.dictionaryAddValue(dictionary, native.secMatchLimit, native.secMatchLimitOne)
            }
            return block(dictionary)
        } finally {
            native.release(dictionary)
            ownedValues.forEach(native::release)
        }
    }

    private fun <T> withValueUpdate(
        data: ByteArray,
        block: (Pointer) -> T,
    ): T {
        val dictionary =
            checkNotNull(native.createMutableDictionary()) {
                "CoreFoundation dictionary allocation failed."
            }
        var dataValue: Pointer? = null
        try {
            val createdData =
                checkNotNull(native.createData(data)) {
                    "CoreFoundation value allocation failed."
                }
            dataValue = createdData
            native.dictionaryAddValue(dictionary, native.secValueData, createdData)
            return block(dictionary)
        } finally {
            native.release(dictionary)
            dataValue?.let(native::release)
        }
    }
}

internal interface MacSecurityNative {
    val secClass: Pointer
    val secClassGenericPassword: Pointer
    val secAttrService: Pointer
    val secAttrAccount: Pointer
    val secAttrSynchronizable: Pointer
    val secAttrAccessible: Pointer
    val secAttrAccessibleAfterFirstUnlockThisDeviceOnly: Pointer
    val secValueData: Pointer
    val secReturnData: Pointer
    val secMatchLimit: Pointer
    val secMatchLimitOne: Pointer
    val booleanFalse: Pointer
    val booleanTrue: Pointer

    fun createMutableDictionary(): Pointer?

    fun dictionaryAddValue(
        dictionary: Pointer,
        key: Pointer,
        value: Pointer,
    )

    fun createString(value: String): Pointer?

    fun createData(value: ByteArray): Pointer?

    fun dataBytes(data: Pointer): ByteArray

    fun secItemCopyMatching(
        query: Pointer,
        result: PointerByReference,
    ): Int

    fun secItemAdd(attributes: Pointer): Int

    fun secItemUpdate(
        query: Pointer,
        attributes: Pointer,
    ): Int

    fun secItemDelete(query: Pointer): Int

    fun release(value: Pointer)
}

private object JnaMacSecurityNative : MacSecurityNative {
    private val security: SecurityApi by lazy {
        Native.load(SECURITY_FRAMEWORK_PATH, SecurityApi::class.java)
    }
    private val coreFoundation: CoreFoundationApi by lazy {
        Native.load(CORE_FOUNDATION_FRAMEWORK_PATH, CoreFoundationApi::class.java)
    }
    private val securityLibrary: NativeLibrary by lazy { NativeLibrary.getInstance(SECURITY_FRAMEWORK_PATH) }
    private val coreFoundationLibrary: NativeLibrary by lazy {
        NativeLibrary.getInstance(CORE_FOUNDATION_FRAMEWORK_PATH)
    }

    override val secClass: Pointer by securityConstant("kSecClass")
    override val secClassGenericPassword: Pointer by securityConstant("kSecClassGenericPassword")
    override val secAttrService: Pointer by securityConstant("kSecAttrService")
    override val secAttrAccount: Pointer by securityConstant("kSecAttrAccount")
    override val secAttrSynchronizable: Pointer by securityConstant("kSecAttrSynchronizable")
    override val secAttrAccessible: Pointer by securityConstant("kSecAttrAccessible")
    override val secAttrAccessibleAfterFirstUnlockThisDeviceOnly: Pointer by
        securityConstant("kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly")
    override val secValueData: Pointer by securityConstant("kSecValueData")
    override val secReturnData: Pointer by securityConstant("kSecReturnData")
    override val secMatchLimit: Pointer by securityConstant("kSecMatchLimit")
    override val secMatchLimitOne: Pointer by securityConstant("kSecMatchLimitOne")
    override val booleanFalse: Pointer by coreFoundationConstant("kCFBooleanFalse")
    override val booleanTrue: Pointer by coreFoundationConstant("kCFBooleanTrue")

    override fun createMutableDictionary(): Pointer? =
        coreFoundation.CFDictionaryCreateMutable(
            null,
            0,
            coreFoundationLibrary.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks"),
            coreFoundationLibrary.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks"),
        )

    override fun dictionaryAddValue(
        dictionary: Pointer,
        key: Pointer,
        value: Pointer,
    ) {
        coreFoundation.CFDictionaryAddValue(dictionary, key, value)
    }

    override fun createString(value: String): Pointer? = coreFoundation.CFStringCreateWithCString(null, value, CF_STRING_ENCODING_UTF8)

    override fun createData(value: ByteArray): Pointer? {
        val bytes =
            value.takeIf { it.isNotEmpty() }?.let { source ->
                Memory(source.size.toLong()).also { memory ->
                    memory.write(0, source, 0, source.size)
                }
            }
        return coreFoundation.CFDataCreate(null, bytes, value.size.toLong())
    }

    override fun dataBytes(data: Pointer): ByteArray {
        val size = coreFoundation.CFDataGetLength(data)
        if (size <= 0) {
            return byteArrayOf()
        }
        val bytes = checkNotNull(coreFoundation.CFDataGetBytePtr(data))
        return bytes.getByteArray(0, size.toInt())
    }

    override fun secItemCopyMatching(
        query: Pointer,
        result: PointerByReference,
    ): Int = security.SecItemCopyMatching(query, result)

    override fun secItemAdd(attributes: Pointer): Int = security.SecItemAdd(attributes, null)

    override fun secItemUpdate(
        query: Pointer,
        attributes: Pointer,
    ): Int = security.SecItemUpdate(query, attributes)

    override fun secItemDelete(query: Pointer): Int = security.SecItemDelete(query)

    override fun release(value: Pointer) {
        coreFoundation.CFRelease(value)
    }

    private fun securityConstant(name: String): Lazy<Pointer> = lazy { securityLibrary.getGlobalVariableAddress(name).getPointer(0) }

    private fun coreFoundationConstant(name: String): Lazy<Pointer> =
        lazy { coreFoundationLibrary.getGlobalVariableAddress(name).getPointer(0) }
}

@Suppress("FunctionName")
private interface SecurityApi : Library {
    fun SecItemCopyMatching(
        query: Pointer,
        result: PointerByReference,
    ): Int

    fun SecItemAdd(
        attributes: Pointer,
        result: Pointer?,
    ): Int

    fun SecItemUpdate(
        query: Pointer,
        attributesToUpdate: Pointer,
    ): Int

    fun SecItemDelete(query: Pointer): Int
}

@Suppress("FunctionName")
private interface CoreFoundationApi : Library {
    fun CFDictionaryCreateMutable(
        allocator: Pointer?,
        capacity: Long,
        keyCallbacks: Pointer,
        valueCallbacks: Pointer,
    ): Pointer?

    fun CFDictionaryAddValue(
        dictionary: Pointer,
        key: Pointer,
        value: Pointer,
    )

    fun CFStringCreateWithCString(
        allocator: Pointer?,
        value: String,
        encoding: Int,
    ): Pointer?

    fun CFDataCreate(
        allocator: Pointer?,
        bytes: Pointer?,
        length: Long,
    ): Pointer?

    fun CFDataGetLength(data: Pointer): Long

    fun CFDataGetBytePtr(data: Pointer): Pointer?

    fun CFRelease(value: Pointer)
}

internal const val ERR_SEC_SUCCESS = 0
internal const val ERR_SEC_DUPLICATE_ITEM = -25299
internal const val ERR_SEC_ITEM_NOT_FOUND = -25300

private const val SECURITY_FRAMEWORK_PATH = "/System/Library/Frameworks/Security.framework/Security"
private const val CORE_FOUNDATION_FRAMEWORK_PATH =
    "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"
private const val CF_STRING_ENCODING_UTF8 = 0x08000100
