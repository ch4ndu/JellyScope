// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionStoreRemovalTest {
    @Test
    fun removeAccountCarriesCommittedSiblingSnapshot() =
        runTest {
            val store = SessionStore(FakeSecureStore(), Json)
            val removed = storedSession("server-1", "user-1")
            val sibling = storedSession("server-1", "user-2")
            val otherServer = storedSession("server-2", "user-3")
            store.writeSession(removed)
            store.writeSession(sibling)
            store.writeSession(otherServer)

            val removal = kotlin.test.assertNotNull(store.removeAccount(removed.accountId()))

            assertTrue(removal.hasServerSibling)
            assertEquals(listOf(sibling, otherServer), removal.committedSnapshot.sessions)
            assertEquals(otherServer, removal.committedSnapshot.activeSession)
            assertEquals(listOf(sibling, otherServer), store.readAccounts())
        }

    @Test
    fun envelopeReadFailureAbortsBeforeCommit() =
        runTest {
            val secureStore = ReadWriteFailingSecureStore()
            val store = SessionStore(secureStore, Json)
            val removed = storedSession("server-1", "user-1")
            val sibling = storedSession("server-1", "user-2")
            store.writeSession(removed)
            store.writeSession(sibling)
            secureStore.failOnReadKey = SESSION_ENVELOPE_KEY

            assertFailsWith<IllegalStateException> {
                store.removeAccount(removed.accountId())
            }

            secureStore.failOnReadKey = null
            assertEquals(listOf(removed, sibling), store.readAccounts())
        }

    @Test
    fun envelopeWriteFailureLeavesPriorAuthority() =
        runTest {
            val secureStore = ReadWriteFailingSecureStore()
            val store = SessionStore(secureStore, Json)
            val removed = storedSession("server-1", "user-1")
            val sibling = storedSession("server-1", "user-2")
            store.writeSession(removed)
            store.writeSession(sibling)
            secureStore.failOnWriteKey = SESSION_ENVELOPE_KEY

            assertFailsWith<IllegalStateException> {
                store.removeAccount(removed.accountId())
            }

            secureStore.failOnWriteKey = null
            assertEquals(listOf(removed, sibling), SessionStore(secureStore, Json).readAccounts())
        }

    @Test
    fun legacyCleanupFailureAfterCommitIsReportedWithCommittedSnapshot() =
        runTest {
            val secureStore = FakeSecureStore()
            val store = SessionStore(secureStore, Json)
            val removed = storedSession("server-1", "user-1")
            val sibling = storedSession("server-1", "user-2")
            store.writeSession(removed)
            store.writeSession(sibling)
            secureStore.failOnRemoveKey = "session_account:${removed.accountId()}"

            val removal = kotlin.test.assertNotNull(store.removeAccount(removed.accountId()))

            assertEquals(1, removal.mutationFailures.size)
            assertEquals(listOf(sibling), removal.committedSnapshot.sessions)
            secureStore.failOnRemoveKey = null
            assertEquals(listOf(sibling), SessionStore(secureStore, Json).readAccounts())
        }

    @Test
    fun removeLastAccountCommitsEmptyTombstone() =
        runTest {
            val secureStore = FakeSecureStore()
            val store = SessionStore(secureStore, Json)
            val removed = storedSession("server-1", "user-1")
            store.writeSession(removed)

            val removal = kotlin.test.assertNotNull(store.removeAccount(removed.accountId()))
            secureStore.write("session", Json.encodeToString(removed))
            val recreated = SessionStore(secureStore, Json)

            assertEquals(emptyList(), removal.committedSnapshot.sessions)
            assertNull(removal.committedSnapshot.activeSession)
            assertNull(recreated.readSession())
            assertEquals(emptyList(), recreated.readAccounts())
        }
}

private class ReadWriteFailingSecureStore : SecureStore {
    private val delegate = FakeSecureStore()
    var failOnReadKey: String? = null
    var failOnWriteKey: String? = null

    override suspend fun read(key: String): String? {
        if (key == failOnReadKey) {
            throw IllegalStateException("read failed")
        }
        return delegate.read(key)
    }

    override suspend fun write(
        key: String,
        value: String,
    ) {
        if (key == failOnWriteKey) {
            throw IllegalStateException("write failed")
        }
        delegate.write(key, value)
    }

    override suspend fun remove(key: String) = delegate.remove(key)

    override suspend fun clear() = delegate.clear()
}

private fun storedSession(
    serverId: String,
    userId: String,
): StoredSession =
    StoredSession(
        serverUrl = "https://$serverId.example",
        serverId = serverId,
        serverName = serverId,
        userId = userId,
        userName = userId,
        accessToken = "token-$userId",
        deviceId = "device-1",
    )
