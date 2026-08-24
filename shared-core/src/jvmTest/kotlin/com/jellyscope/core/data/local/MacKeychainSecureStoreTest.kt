// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacKeychainSecureStoreTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("jellyscope-mac-keychain-test")
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun migratesLegacyJsonThroughInjectedFrameworkAndDeletesIt() =
        runTest {
            val legacyFile = tempDir.resolve("secure-store.json").toFile()
            Files.writeString(
                legacyFile.toPath(),
                Json.encodeToString(mapOf("token" to "legacy-token", "server" to "https://example.test")),
                StandardCharsets.UTF_8,
            )
            val framework = FakeMacSecurityFramework()
            val store = MacKeychainSecureStore(legacyFile = legacyFile, securityFramework = framework)

            assertEquals("legacy-token", store.read("token"))
            assertEquals("https://example.test", store.read("server"))
            assertFalse(legacyFile.exists())

            store.write("token", "new-token")
            assertEquals("new-token", store.read("token"))
            assertEquals(1, framework.updateCount)

            store.remove("server")
            assertNull(store.read("server"))
            store.clear()
            assertNull(store.read("token"))
        }

    @Test
    fun duplicateAddUpdatesAndMissingDeleteSucceeds() =
        runTest {
            val framework = FakeMacSecurityFramework()
            val store =
                MacKeychainSecureStore(
                    legacyFile = tempDir.resolve("absent.json").toFile(),
                    securityFramework = framework,
                )

            store.write("token", "first")
            store.write("token", "second")
            store.remove("missing")

            assertEquals("second", store.read("token"))
            assertEquals(1, framework.updateCount)
        }

    @Test
    fun clearRemovesEveryServiceItem() =
        runTest {
            val store =
                MacKeychainSecureStore(
                    legacyFile = tempDir.resolve("absent.json").toFile(),
                    securityFramework = FakeMacSecurityFramework(),
                )
            store.write("token", "t")
            store.write("server", "s")
            store.write("device", "d")

            store.clear()

            assertNull(store.read("token"))
            assertNull(store.read("server"))
            assertNull(store.read("device"))
        }

    @Test
    fun nativeFailureContainsOnlyOperationAndNumericStatus() =
        runTest {
            val account = "private-account@example.test"
            val secret = "session-secret-value"
            val framework = FakeMacSecurityFramework(addStatus = -50)
            val store =
                MacKeychainSecureStore(
                    legacyFile = tempDir.resolve("absent.json").toFile(),
                    securityFramework = framework,
                )

            val failure =
                assertFailsWith<IllegalStateException> {
                    store.write(account, secret)
                }

            assertEquals("SecItemAdd failed with status=-50", failure.message)
            assertFalse(failure.message.orEmpty().contains(account))
            assertFalse(failure.message.orEmpty().contains(secret))
            assertTrue(framework.receivedNoCommandArguments)
        }

    @Test
    fun jnaAdapterReleasesEveryOwnedCoreFoundationValueExactlyOnce() {
        val native = RecordingMacSecurityNative()
        val framework = JnaMacSecurityFramework(native)

        assertEquals(
            "value",
            framework
                .copyGenericPassword("service", "account")
                .data
                ?.toString(StandardCharsets.UTF_8),
        )
        assertEquals(ERR_SEC_SUCCESS, framework.addGenericPassword("service", "account", byteArrayOf(1)))
        assertEquals(ERR_SEC_SUCCESS, framework.updateGenericPassword("service", "account", byteArrayOf(2)))
        assertEquals(ERR_SEC_SUCCESS, framework.deleteGenericPassword("service", null))

        assertTrue(native.createdPointers.isNotEmpty())
        assertEquals(
            native.createdPointers.toSet(),
            native.releaseCounts.filterValues { count -> count == 1 }.keys,
        )
        assertTrue(native.releaseCounts.values.all { count -> count == 1 })
    }

    @Test
    fun jnaAdapterReleasesUpdateDictionaryWhenDataAllocationFails() {
        val native = RecordingMacSecurityNative(failDataAllocation = true)
        val framework = JnaMacSecurityFramework(native)
        val account = "private-account@example.test"
        val secret = "session-secret-value"

        val failure =
            assertFailsWith<IllegalStateException> {
                framework.updateGenericPassword(
                    service = "service",
                    account = account,
                    data = secret.toByteArray(StandardCharsets.UTF_8),
                )
            }

        assertEquals("CoreFoundation value allocation failed.", failure.message)
        assertFalse(failure.message.orEmpty().contains(account))
        assertFalse(failure.message.orEmpty().contains(secret))
        assertEquals(2, native.createdDictionaries.size)
        assertEquals(1, native.releaseCounts[native.createdDictionaries.last()])
        assertEquals(
            native.createdPointers.toSet(),
            native.releaseCounts.filterValues { count -> count == 1 }.keys,
        )
        assertTrue(native.constantPointers.none { pointer -> pointer in native.releaseCounts })
    }
}

private class FakeMacSecurityFramework(
    private val addStatus: Int? = null,
) : MacSecurityFramework {
    private val values = mutableMapOf<Pair<String, String>, ByteArray>()
    var updateCount = 0
        private set
    val receivedNoCommandArguments = true

    override fun copyGenericPassword(
        service: String,
        account: String,
    ): MacKeychainCopyResult =
        values[service to account]?.let { value ->
            MacKeychainCopyResult(ERR_SEC_SUCCESS, value.copyOf())
        } ?: MacKeychainCopyResult(ERR_SEC_ITEM_NOT_FOUND)

    override fun addGenericPassword(
        service: String,
        account: String,
        data: ByteArray,
    ): Int {
        addStatus?.let { return it }
        val key = service to account
        if (key in values) {
            return ERR_SEC_DUPLICATE_ITEM
        }
        values[key] = data.copyOf()
        return ERR_SEC_SUCCESS
    }

    override fun updateGenericPassword(
        service: String,
        account: String,
        data: ByteArray,
    ): Int {
        updateCount += 1
        values[service to account] = data.copyOf()
        return ERR_SEC_SUCCESS
    }

    override fun deleteGenericPassword(
        service: String,
        account: String?,
    ): Int {
        if (account == null) {
            val matching = values.keys.filter { (storedService, _) -> storedService == service }
            if (matching.isEmpty()) {
                return ERR_SEC_ITEM_NOT_FOUND
            }
            matching.forEach(values::remove)
            return ERR_SEC_SUCCESS
        }
        return if (values.remove(service to account) == null) {
            ERR_SEC_ITEM_NOT_FOUND
        } else {
            ERR_SEC_SUCCESS
        }
    }
}

private class RecordingMacSecurityNative(
    private val failDataAllocation: Boolean = false,
) : MacSecurityNative {
    override val secClass = constant(1)
    override val secClassGenericPassword = constant(2)
    override val secAttrService = constant(3)
    override val secAttrAccount = constant(4)
    override val secAttrSynchronizable = constant(5)
    override val secAttrAccessible = constant(6)
    override val secAttrAccessibleAfterFirstUnlockThisDeviceOnly = constant(7)
    override val secValueData = constant(8)
    override val secReturnData = constant(9)
    override val secMatchLimit = constant(10)
    override val secMatchLimitOne = constant(11)
    override val booleanFalse = constant(12)
    override val booleanTrue = constant(13)

    val createdPointers = mutableListOf<Pointer>()
    val createdDictionaries = mutableListOf<Pointer>()
    val releaseCounts = mutableMapOf<Pointer, Int>()
    val constantPointers: Set<Pointer>
        get() =
            setOf(
                secClass,
                secClassGenericPassword,
                secAttrService,
                secAttrAccount,
                secAttrSynchronizable,
                secAttrAccessible,
                secAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                secValueData,
                secReturnData,
                secMatchLimit,
                secMatchLimitOne,
                booleanFalse,
                booleanTrue,
            )
    private val data = mutableMapOf<Pointer, ByteArray>()
    private var nextPointer = 100L

    override fun createMutableDictionary(): Pointer = createdPointer().also(createdDictionaries::add)

    override fun dictionaryAddValue(
        dictionary: Pointer,
        key: Pointer,
        value: Pointer,
    ) = Unit

    override fun createString(value: String): Pointer = createdPointer()

    override fun createData(value: ByteArray): Pointer? =
        if (failDataAllocation) {
            null
        } else {
            createdPointer().also { pointer -> data[pointer] = value.copyOf() }
        }

    override fun dataBytes(data: Pointer): ByteArray = this.data.getValue(data).copyOf()

    override fun secItemCopyMatching(
        query: Pointer,
        result: PointerByReference,
    ): Int {
        result.value =
            createdPointer().also { pointer ->
                data[pointer] = "value".toByteArray(StandardCharsets.UTF_8)
            }
        return ERR_SEC_SUCCESS
    }

    override fun secItemAdd(attributes: Pointer): Int = ERR_SEC_SUCCESS

    override fun secItemUpdate(
        query: Pointer,
        attributes: Pointer,
    ): Int = ERR_SEC_SUCCESS

    override fun secItemDelete(query: Pointer): Int = ERR_SEC_SUCCESS

    override fun release(value: Pointer) {
        releaseCounts[value] = releaseCounts.getOrElse(value) { 0 } + 1
    }

    private fun createdPointer(): Pointer = constant(nextPointer++).also(createdPointers::add)

    private companion object {
        fun constant(value: Long): Pointer = Pointer.createConstant(value)
    }
}
