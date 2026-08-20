// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.OpenSubtitleResultPreference
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionStoreTest {
    @Test
    fun roundTripsStoredSession() =
        runTest {
            val store = SessionStore(FakeSecureStore(), Json)
            val session =
                StoredSession(
                    serverUrl = "https://jellyfin.example",
                    serverId = "server-1",
                    serverName = "Home Jellyfin",
                    userId = "user-1",
                    userName = "Demo User",
                    accessToken = "token-1",
                    deviceId = "device-1",
                    enableContentDownloading = true,
                )

            store.writeSession(session)

            assertEquals(session, store.readSession())
            assertEquals(listOf(session), store.readAccounts())
        }

    @Test
    fun clearServerScopedRemovesSessionButKeepsDeviceId() =
        runTest {
            val store = SessionStore(FakeSecureStore(), Json)
            store.readOrCreateDeviceId { "device-1" }
            store.writeSession(
                StoredSession(
                    serverUrl = "https://jellyfin.example",
                    serverId = "server-1",
                    serverName = "Home Jellyfin",
                    userId = "user-1",
                    userName = "Demo User",
                    accessToken = "token-1",
                    deviceId = "device-1",
                ),
            )

            store.clearServerScoped()

            assertNull(store.readSession())
            assertEquals("device-1", store.readOrCreateDeviceId { "device-2" })
        }

    @Test
    fun clearServerScopedKeepsOpenSubtitlesConsumerKey() =
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
    fun logoutPendingMarkerSurvivesStoreRecreationUntilExplicitlyCleared() =
        runTest {
            val secureStore = FakeSecureStore()
            val firstStore = SessionStore(secureStore, Json)

            firstStore.markLogoutPending()

            val recreatedStore = SessionStore(secureStore, Json)
            assertTrue(recreatedStore.isLogoutPending())

            recreatedStore.clearLogoutPending()

            assertFalse(recreatedStore.isLogoutPending())
        }

    @Test
    fun writeSessionClearsStaleLogoutPendingMarker() =
        runTest {
            val secureStore = FakeSecureStore()
            val store = SessionStore(secureStore, Json)
            store.markLogoutPending()
            assertTrue(store.isLogoutPending())

            // A successful login persists a session and must supersede a stale
            // failed-logout marker, else a later cold restore would wipe it.
            store.writeSession(storedSession(serverUrl = "https://jellyfin.example", accessToken = "token-1"))

            assertFalse(store.isLogoutPending())
        }

    @Test
    fun legacySingleSessionMigratesToActiveAccount() =
        runTest {
            val secureStore = FakeSecureStore()
            val json = Json
            val store = SessionStore(secureStore, json)
            val session =
                StoredSession(
                    serverUrl = "https://jellyfin.example",
                    serverId = "server-1",
                    serverName = "Home Jellyfin",
                    userId = "user-1",
                    userName = "Demo User",
                    accessToken = "token-1",
                    deviceId = "device-1",
                )
            secureStore.write("session", json.encodeToString(session))

            assertEquals(session, store.readSession())
            assertEquals(listOf(session), store.readAccounts())
            assertEquals(session.accountId(), store.readActiveAccountId())
            assertNull(secureStore.read("session"))
        }

    @Test
    fun missingDownloadsPermissionDefaultsToFalseOnLegacyRestore() =
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
            val store = SessionStore(secureStore, Json)

            assertFalse(store.readSession()?.enableContentDownloading ?: true)
        }

    @Test
    fun urlAliasesCollapseToCanonicalAccountAndActiveAliasWins() =
        runTest {
            val secureStore = FakeSecureStore()
            val first = storedSession(serverUrl = "https://lan.example", accessToken = "lan-token")
            val active = storedSession(serverUrl = "https://remote.example", accessToken = "remote-token")
            val firstLegacyId = "${first.serverUrl}|${first.userId}"
            val activeLegacyId = "${active.serverUrl}|${active.userId}"
            secureStore.write(
                "session_accounts",
                "{\"accountIds\":[\"$firstLegacyId\",\"$activeLegacyId\"]}",
            )
            secureStore.write("active_session_account", activeLegacyId)
            secureStore.write("session_account:$firstLegacyId", Json.encodeToString(first))
            secureStore.write("session_account:$activeLegacyId", Json.encodeToString(active))
            val store = SessionStore(secureStore, Json)

            assertEquals(listOf(active), store.readAccounts())
            assertEquals("server-1|user-1", store.readActiveAccountId())
            assertEquals(active, store.readSession())
            assertNull(secureStore.read("session_account:$firstLegacyId"))
            assertNull(secureStore.read("session_account:$activeLegacyId"))
            assertEquals(listOf(active), store.readAccounts())
        }

    @Test
    fun canonicalReadsDoNotWriteToSecureStore() =
        runTest {
            val delegate = FakeSecureStore()
            val session = storedSession(serverUrl = "https://jellyfin.example", accessToken = "token-1")
            val accountId = session.accountId()
            delegate.write("session_accounts", Json.encodeToString(mapOf("accountIds" to listOf(accountId))))
            delegate.write("session_account:$accountId", Json.encodeToString(session))
            delegate.write("active_session_account", accountId)
            val store = SessionStore(WriteFailingSecureStore(delegate), Json)

            assertEquals(session, store.readSession())
            assertEquals(listOf(session), store.readAccounts())
            assertEquals(accountId, store.readActiveAccountId())
        }

    @Test
    fun loginThroughNewAliasUpdatesCanonicalRecordWithoutDuplicating() =
        runTest {
            val store = SessionStore(FakeSecureStore(), Json)
            store.writeSession(storedSession(serverUrl = "https://lan.example", accessToken = "old-token"))
            val replacement = storedSession(serverUrl = "https://remote.example", accessToken = "new-token")

            store.writeSession(replacement)

            assertEquals(listOf(replacement), store.readAccounts())
            assertEquals(replacement, store.readSession())
        }

    @Test
    fun malformedLegacySessionIsDiscardedWithoutCreatingAnActiveAccount() =
        runTest {
            val secureStore = FakeSecureStore()
            secureStore.write("session", "not-json")
            val store = SessionStore(secureStore, Json)

            assertNull(store.readSession())
            assertEquals(emptyList(), store.readAccounts())
            assertNull(secureStore.read("session"))
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

private class WriteFailingSecureStore(
    private val delegate: SecureStore,
) : SecureStore {
    override suspend fun read(key: String): String? = delegate.read(key)

    override suspend fun write(
        key: String,
        value: String,
    ) {
        error("Canonical reads must not write $key")
    }

    override suspend fun remove(key: String) = error("Canonical reads must not remove $key")

    override suspend fun clear() = error("Canonical reads must not clear secure storage")
}
