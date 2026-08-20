// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JvmPlainFileSecureStoreTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("jellyscope-secure-store-test")
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun valuesPersistAcrossStoreInstances() =
        runTest {
            val file = tempDir.resolve("secure-store.json").toFile()

            JvmPlainFileSecureStore(file).write(key = "token", value = "abc")

            assertEquals("abc", JvmPlainFileSecureStore(file).read("token"))
        }

    @Test
    fun removeAndClearPersistToDisk() =
        runTest {
            val file = tempDir.resolve("secure-store.json").toFile()
            val store = JvmPlainFileSecureStore(file)

            store.write(key = "one", value = "1")
            store.write(key = "two", value = "2")
            store.remove("one")

            assertNull(JvmPlainFileSecureStore(file).read("one"))
            assertEquals("2", JvmPlainFileSecureStore(file).read("two"))

            store.clear()

            assertNull(JvmPlainFileSecureStore(file).read("two"))
        }

    @Test
    fun corruptFileBehavesAsEmptyAndCanBeOverwritten() =
        runTest {
            val file = tempDir.resolve("secure-store.json").toFile()
            Files.writeString(file.toPath(), "{", StandardCharsets.UTF_8)
            val store = JvmPlainFileSecureStore(file)

            assertNull(store.read("token"))

            store.write(key = "token", value = "abc")

            assertEquals("abc", JvmPlainFileSecureStore(file).read("token"))
        }

    @Test
    fun sessionStoreRestoresSavedSessionAcrossStoreInstances() =
        runTest {
            val file = tempDir.resolve("secure-store.json").toFile()
            val session =
                StoredSession(
                    serverUrl = "https://jellyfin.example",
                    serverId = "server-1",
                    serverName = "Home",
                    userId = "user-1",
                    userName = "demo-user",
                    accessToken = "token-1",
                    deviceId = "device-1",
                )

            SessionStore(JvmPlainFileSecureStore(file), Json).writeSession(session)

            assertEquals(session, SessionStore(JvmPlainFileSecureStore(file), Json).readSession())
        }
}
