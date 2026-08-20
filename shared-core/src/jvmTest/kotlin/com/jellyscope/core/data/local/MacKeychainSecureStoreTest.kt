// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

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
import kotlin.test.assertFalse
import kotlin.test.assertNull

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
    fun migratesLegacyJsonThroughInjectedRunnerAndDeletesIt() =
        runTest {
            val legacyFile = tempDir.resolve("secure-store.json").toFile()
            Files.writeString(
                legacyFile.toPath(),
                Json.encodeToString(mapOf("token" to "legacy-token", "server" to "https://example.test")),
                StandardCharsets.UTF_8,
            )
            val runner = FakeSecurityCommandRunner()
            val store = MacKeychainSecureStore(legacyFile = legacyFile, commandRunner = runner)

            assertEquals("legacy-token", store.read("token"))
            assertEquals("https://example.test", store.read("server"))
            assertFalse(legacyFile.exists())

            store.write("token", "new-token")
            assertEquals("new-token", store.read("token"))

            store.remove("server")
            assertNull(store.read("server"))
            store.clear()
            assertNull(store.read("token"))
        }

    @Test
    fun clearRemovesEveryServiceItemNotJustOne() =
        runTest {
            val legacyFile = tempDir.resolve("absent.json").toFile()
            val store = MacKeychainSecureStore(legacyFile = legacyFile, commandRunner = FakeSecurityCommandRunner())
            store.write("token", "t")
            store.write("server", "s")
            store.write("device", "d")

            store.clear()

            assertNull(store.read("token"))
            assertNull(store.read("server"))
            assertNull(store.read("device"))
        }

    private class FakeSecurityCommandRunner : SecurityCommandRunner {
        private val values = mutableMapOf<String, String>()

        override fun run(arguments: List<String>): SecurityCommandResult =
            when (arguments.firstOrNull()) {
                "add-generic-password" -> {
                    val account = arguments.valueAfter("-a")
                    val value = arguments.valueAfter("-w")
                    if (account == null || value == null) {
                        SecurityCommandResult(exitCode = 1)
                    } else {
                        values[account] = value
                        SecurityCommandResult(exitCode = 0)
                    }
                }

                "find-generic-password" -> {
                    val account = arguments.valueAfter("-a")
                    val value = account?.let(values::get)
                    if (value == null) {
                        SecurityCommandResult(
                            exitCode = 44,
                            stderr = "The specified item could not be found in the keychain.",
                        )
                    } else {
                        SecurityCommandResult(exitCode = 0, stdout = "$value\n")
                    }
                }

                "delete-generic-password" -> {
                    val account = arguments.valueAfter("-a")
                    if (account == null) {
                        // The real security CLI removes only ONE matching item per call.
                        val firstKey = values.keys.firstOrNull()
                        if (firstKey == null) {
                            SecurityCommandResult(exitCode = 44)
                        } else {
                            values.remove(firstKey)
                            SecurityCommandResult(exitCode = 0)
                        }
                    } else if (values.remove(account) == null) {
                        SecurityCommandResult(exitCode = 44)
                    } else {
                        SecurityCommandResult(exitCode = 0)
                    }
                }

                else -> SecurityCommandResult(exitCode = 1)
            }

        private fun List<String>.valueAfter(flag: String): String? {
            val index = indexOf(flag)
            return if (index >= 0) getOrNull(index + 1) else null
        }
    }
}
