// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.OpenSubtitleResultPreference
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionStoreTest {
    @Test
    fun roundTripsStoredSessionAndReturnsCommittedSnapshot() =
        runTest {
            val store = SessionStore(FakeSecureStore(), Json)
            val session = storedSession(serverUrl = "https://jellyfin.example", accessToken = "token-1")

            val committed = store.writeSession(session)

            assertEquals(listOf(session), committed.sessions)
            assertEquals(session.accountId(), committed.activeAccountId)
            assertEquals(session, committed.activeSession)
            assertFalse(committed.logoutPending)
            assertEquals(session, store.readSession())
            assertEquals(listOf(session), store.readAccounts())
        }

    @Test
    fun clearServerScopedCommitsEmptyEnvelopeAndKeepsDeviceId() =
        runTest {
            val secureStore = FakeSecureStore()
            val store = SessionStore(secureStore, Json)
            store.readOrCreateDeviceId { "device-1" }
            store.writeSession(storedSession(serverUrl = "https://jellyfin.example", accessToken = "token-1"))

            store.clearServerScoped()
            secureStore.write("session", Json.encodeToString(storedSession("https://stale.example", "stale")))
            val recreated = SessionStore(secureStore, Json)

            assertNull(recreated.readSession())
            assertEquals(emptyList(), recreated.readAccounts())
            assertEquals("device-1", recreated.readOrCreateDeviceId { "device-2" })
        }

    @Test
    fun clearServerScopedKeepsOpenSubtitlesSettings() =
        runTest {
            val secureStore = FakeSecureStore()
            val sessionStore = SessionStore(secureStore, Json)
            val openSubtitlesStore = OpenSubtitlesSettingsStore(secureStore)
            openSubtitlesStore.setApiKey("consumer-key")
            openSubtitlesStore.setResultPreference(OpenSubtitleResultPreference.PreferForced)

            sessionStore.clearServerScoped()

            assertEquals("consumer-key", openSubtitlesStore.apiKey())
            assertEquals(OpenSubtitleResultPreference.PreferForced, openSubtitlesStore.resultPreference())
        }

    @Test
    fun logoutPendingLivesInEnvelopeUntilExplicitlyCleared() =
        runTest {
            val secureStore = FakeSecureStore()
            SessionStore(secureStore, Json).markLogoutPending()

            val recreated = SessionStore(secureStore, Json)
            assertTrue(recreated.isLogoutPending())

            recreated.clearLogoutPending()

            assertFalse(SessionStore(secureStore, Json).isLogoutPending())
            assertNull(secureStore.read("session_logout_pending"))
        }

    @Test
    fun writeSessionClearsLogoutPendingInSameCommit() =
        runTest {
            val store = SessionStore(FakeSecureStore(), Json)
            store.markLogoutPending()

            val committed =
                store.writeSession(storedSession(serverUrl = "https://jellyfin.example", accessToken = "token-1"))

            assertFalse(committed.logoutPending)
            assertFalse(store.isLogoutPending())
        }

    @Test
    fun legacySingleSessionMigratesToCanonicalActiveAccount() =
        runTest {
            val secureStore = FakeSecureStore()
            val session = storedSession(serverUrl = "https://jellyfin.example", accessToken = "token-1")
            secureStore.write("session", Json.encodeToString(session))

            val snapshot = SessionStore(secureStore, Json).readSnapshot()

            assertEquals(listOf(session), snapshot.sessions)
            assertEquals(session.accountId(), snapshot.activeAccountId)
            assertEquals(session, snapshot.activeSession)
            assertNull(secureStore.read("session"))
            assertTrue(secureStore.read(SESSION_ENVELOPE_KEY) != null)
        }

    @Test
    fun missingDownloadsPermissionDefaultsToFalseDuringLegacyMigration() =
        runTest {
            val secureStore = FakeSecureStore()
            secureStore.write(
                "session",
                """
                {
                  "serverUrl":"https://jellyfin.example",
                  "serverId":"server-1",
                  "serverName":"Home Jellyfin",
                  "userId":"user-1",
                  "userName":"Demo User",
                  "accessToken":"token-1",
                  "deviceId":"device-1"
                }
                """.trimIndent(),
            )

            assertFalse(SessionStore(secureStore, Json).readSession()?.enableContentDownloading ?: true)
        }

    @Test
    fun activeUrlAliasWinsWhenLegacyAliasesCollapseToCanonicalIdentity() =
        runTest {
            val secureStore = FakeSecureStore()
            val first = storedSession(serverUrl = "https://lan.example", accessToken = "lan-token")
            val active = storedSession(serverUrl = "https://remote.example", accessToken = "remote-token")
            val firstLegacyId = "${first.serverUrl}|${first.userId}"
            val activeLegacyId = "${active.serverUrl}|${active.userId}"
            secureStore.write("session_accounts", "{\"accountIds\":[\"$firstLegacyId\",\"$activeLegacyId\"]}")
            secureStore.write("active_session_account", activeLegacyId)
            secureStore.write("session_account:$firstLegacyId", Json.encodeToString(first))
            secureStore.write("session_account:$activeLegacyId", Json.encodeToString(active))

            val snapshot = SessionStore(secureStore, Json).readSnapshot()

            assertEquals(listOf(active), snapshot.sessions)
            assertEquals("server-1|user-1", snapshot.activeAccountId)
            assertEquals(active, snapshot.activeSession)
            assertNull(secureStore.read("session_account:$firstLegacyId"))
            assertNull(secureStore.read("session_account:$activeLegacyId"))
        }

    @Test
    fun firstReadWithoutLegacyMaterialCommitsEmptyAuthority() =
        runTest {
            val secureStore = FakeSecureStore()

            assertEquals(emptyList(), SessionStore(secureStore, Json).readAccounts())
            assertTrue(secureStore.read(SESSION_ENVELOPE_KEY) != null)

            secureStore.write(
                "session",
                Json.encodeToString(storedSession(serverUrl = "https://stale.example", accessToken = "stale-token")),
            )
            assertNull(SessionStore(secureStore, Json).readSession())
        }

    @Test
    fun presentEmptyEnvelopeNeverReadsStaleAliases() =
        runTest {
            val secureStore = FakeSecureStore()
            SessionStore(secureStore, Json).readSnapshot()
            secureStore.write(
                "session",
                Json.encodeToString(storedSession(serverUrl = "https://stale.example", accessToken = "stale-token")),
            )
            val guarded = AliasReadRejectingSecureStore(secureStore)

            val snapshot = SessionStore(guarded, Json).readSnapshot()

            assertEquals(emptyList(), snapshot.sessions)
            assertEquals(listOf(SESSION_ENVELOPE_KEY), guarded.readKeys)
        }

    @Test
    fun corruptPresentEnvelopeFailsClosedWithoutReadingValidAliases() =
        runTest {
            val secureStore = FakeSecureStore()
            secureStore.write(SESSION_ENVELOPE_KEY, "not-json")
            secureStore.write(
                "session",
                Json.encodeToString(storedSession(serverUrl = "https://valid.example", accessToken = "valid-token")),
            )
            val guarded = AliasReadRejectingSecureStore(secureStore)

            assertFailsWith<IllegalStateException> {
                SessionStore(guarded, Json).readSnapshot()
            }

            assertEquals(listOf(SESSION_ENVELOPE_KEY), guarded.readKeys)
            assertEquals("not-json", secureStore.read(SESSION_ENVELOPE_KEY))
        }

    @Test
    fun unsupportedOrIncompleteEnvelopeFailsClosed() =
        runTest {
            val unsupported = FakeSecureStore()
            unsupported.write(
                SESSION_ENVELOPE_KEY,
                """{"version":2,"era":"single-envelope","sessions":[],"activeAccountId":null,"logoutPending":false}""",
            )
            val incomplete = FakeSecureStore()
            incomplete.write(
                SESSION_ENVELOPE_KEY,
                """{"version":1,"era":"single-envelope","sessions":[],"activeAccountId":null}""",
            )

            assertFailsWith<IllegalStateException> { SessionStore(unsupported, Json).readSnapshot() }
            assertFailsWith<IllegalStateException> { SessionStore(incomplete, Json).readSnapshot() }
        }

    @Test
    fun envelopeCommitPrecedesBestEffortLegacyCleanup() =
        runTest {
            val secureStore = FakeSecureStore()
            secureStore.write(
                "session",
                Json.encodeToString(storedSession(serverUrl = "https://legacy.example", accessToken = "legacy-token")),
            )
            secureStore.failOnRemoveKey = "session"

            val snapshot = SessionStore(secureStore, Json).readSnapshot()

            assertEquals("legacy-token", snapshot.activeSession?.accessToken)
            assertTrue(secureStore.read(SESSION_ENVELOPE_KEY) != null)
            secureStore.failOnRemoveKey = null
            assertEquals("legacy-token", SessionStore(secureStore, Json).readSession()?.accessToken)
        }

    @Test
    fun loginCommitSucceedsWhenLegacyCleanupFails() =
        runTest {
            val secureStore = FakeSecureStore()
            secureStore.failOnRemoveKey = "session"
            val session = storedSession(serverUrl = "https://jellyfin.example", accessToken = "token-1")

            val committed = SessionStore(secureStore, Json).writeSession(session)

            assertEquals(session, committed.activeSession)
            assertEquals(session, SessionStore(secureStore, Json).readSession())
        }

    @Test
    fun failedEnvelopeCommitLeavesPriorAuthority() =
        runTest {
            val secureStore = FakeSecureStore()
            val first = storedSession(serverUrl = "https://first.example", accessToken = "first-token")
            val replacement = first.copy(serverUrl = "https://replacement.example", accessToken = "replacement-token")
            val store = SessionStore(secureStore, Json)
            store.writeSession(first)
            secureStore.failOnWriteKey = SESSION_ENVELOPE_KEY

            assertFailsWith<IllegalStateException> { store.writeSession(replacement) }

            secureStore.failOnWriteKey = null
            assertEquals(first, SessionStore(secureStore, Json).readSession())
        }

    @Test
    fun presentEnvelopeReadsDoNotWriteOrCleanAliases() =
        runTest {
            val secureStore = FakeSecureStore()
            val session = storedSession(serverUrl = "https://jellyfin.example", accessToken = "token-1")
            SessionStore(secureStore, Json).writeSession(session)
            val store = SessionStore(WriteFailingSecureStore(secureStore), Json)

            assertEquals(session, store.readSession())
            assertEquals(listOf(session), store.readAccounts())
            assertEquals(session.accountId(), store.readActiveAccountId())
        }

    @Test
    fun malformedLegacySessionBecomesDurableEmptyAuthority() =
        runTest {
            val secureStore = FakeSecureStore()
            secureStore.write("session", "not-json")
            val store = SessionStore(secureStore, Json)

            assertNull(store.readSession())
            assertEquals(emptyList(), store.readAccounts())
            assertNull(secureStore.read("session"))
            secureStore.write(
                "session",
                Json.encodeToString(storedSession(serverUrl = "https://stale.example", accessToken = "stale-token")),
            )
            assertNull(SessionStore(secureStore, Json).readSession())
        }
}

private fun storedSession(
    serverUrl: String,
    accessToken: String,
): StoredSession =
    StoredSession(
        serverUrl = serverUrl,
        serverId = "server-1",
        serverName = "Home Jellyfin",
        userId = "user-1",
        userName = "Demo User",
        accessToken = accessToken,
        deviceId = "device-1",
    )

private class AliasReadRejectingSecureStore(
    private val delegate: SecureStore,
) : SecureStore {
    val readKeys = mutableListOf<String>()

    override suspend fun read(key: String): String? {
        readKeys += key
        if (key != SESSION_ENVELOPE_KEY) {
            error("Authoritative envelope read inspected legacy alias $key")
        }
        return delegate.read(key)
    }

    override suspend fun write(
        key: String,
        value: String,
    ) = delegate.write(key, value)

    override suspend fun remove(key: String) = delegate.remove(key)

    override suspend fun clear() = delegate.clear()
}

private class WriteFailingSecureStore(
    private val delegate: SecureStore,
) : SecureStore {
    override suspend fun read(key: String): String? = delegate.read(key)

    override suspend fun write(
        key: String,
        value: String,
    ) {
        error("Envelope reads must not write $key")
    }

    override suspend fun remove(key: String) = error("Envelope reads must not remove $key")

    override suspend fun clear() = error("Envelope reads must not clear secure storage")
}
