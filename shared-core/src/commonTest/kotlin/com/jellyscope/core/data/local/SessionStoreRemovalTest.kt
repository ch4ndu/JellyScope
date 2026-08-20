// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionStoreRemovalTest {
    @Test
    fun removeAccountCarriesServerSiblingAnswerBeforeDeletion() =
        kotlinx.coroutines.test.runTest {
            val store = SessionStore(FakeSecureStore(), Json)
            val removed = storedSession("server-1", "user-1")
            val sibling = storedSession("server-1", "user-2")
            val otherServer = storedSession("server-2", "user-3")
            store.writeSession(removed)
            store.writeSession(sibling)
            store.writeSession(otherServer)

            val removal =
                kotlin.test.assertNotNull(
                    store.removeAccount(removed.accountId()),
                )

            assertTrue(removal.hasServerSibling)
            assertEquals(listOf(sibling, otherServer), store.readAccounts())
        }

    @Test
    fun siblingReadFailureAbortsBeforeAccountDeletion() =
        kotlinx.coroutines.test.runTest {
            val secureStore = ReadFailingSecureStore()
            val store = SessionStore(secureStore, Json)
            val removed = storedSession("server-1", "user-1")
            val sibling = storedSession("server-1", "user-2")
            store.writeSession(removed)
            store.writeSession(sibling)
            secureStore.failOnReadKey = "session_account:${sibling.accountId()}"

            assertFailsWith<IllegalStateException> {
                store.removeAccount(removed.accountId())
            }

            secureStore.failOnReadKey = null
            assertEquals(listOf(removed, sibling), store.readAccounts())
        }

    @Test
    fun mutationFailureAfterCredentialDeletionIsReportedInsteadOfThrown() =
        kotlinx.coroutines.test.runTest {
            // Once the credential is deleted the removal is irreversible. A later
            // index write failure must NOT propagate: throwing aborted the caller
            // before account cleanup and before the published transition, which is
            // the half-removed state this guards against.
            val secureStore = ReadFailingSecureStore()
            val store = SessionStore(secureStore, Json)
            val removed = storedSession("server-1", "user-1")
            val sibling = storedSession("server-1", "user-2")
            store.writeSession(removed)
            store.writeSession(sibling)
            // Fail the index write after the credential deletion.
            secureStore.failOnWriteKey = "session_accounts"
            secureStore.failOnWriteKeyAfterWrites = 0

            val removal =
                kotlin.test.assertNotNull(
                    store.removeAccount(removed.accountId()),
                    "removal must still be reported so cleanup and publication continue",
                )

            assertEquals(1, removal.mutationFailures.size)
            assertTrue(removal.hasServerSibling)
        }
}

private class ReadFailingSecureStore : SecureStore {
    private val delegate = FakeSecureStore()
    var failOnReadKey: String? = null
    var failOnWriteKey: String? = null

    /**
     * Writes to [failOnWriteKey] to let through before failing.
     */
    var failOnWriteKeyAfterWrites: Int = 0
    private var matchedWrites = 0

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
            matchedWrites += 1
            if (matchedWrites > failOnWriteKeyAfterWrites) {
                throw IllegalStateException("write failed")
            }
        }
        delegate.write(key, value)
    }

    override suspend fun remove(key: String) {
        delegate.remove(key)
    }

    override suspend fun clear() {
        delegate.clear()
    }
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
