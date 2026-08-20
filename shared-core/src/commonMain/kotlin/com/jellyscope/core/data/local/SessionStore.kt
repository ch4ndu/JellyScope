// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.AccountIdentity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val LEGACY_SESSION_KEY = "session"
private const val ACCOUNT_INDEX_KEY = "session_accounts"
private const val ACTIVE_ACCOUNT_KEY = "active_session_account"
private const val ACCOUNT_SESSION_KEY_PREFIX = "session_account:"
private const val ACCOUNT_ID_SEPARATOR = "|"
private const val DEVICE_ID_KEY = "device_id"
private const val LOGOUT_PENDING_KEY = "session_logout_pending"
private const val LOGOUT_PENDING_VALUE = "true"

@Serializable
data class StoredSession(
    val serverUrl: String,
    val serverId: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    val accessToken: String,
    val deviceId: String,
    val enableContentDownloading: Boolean = false,
)

data class StoredAccountRemoval(
    val removedSession: StoredSession,
    val activeSession: StoredSession?,
    val hasServerSibling: Boolean,
    /**
     * Failures from mutations attempted **after** the credential was deleted.
     *
     * Once the account key is gone the removal cannot be un-done, so a later
     * index or active-account write failure must not abort the caller: it would
     * skip account-scoped cleanup and never publish the transition, leaving
     * exactly the half-removed state this type exists to prevent. The caller
     * finishes the transition and surfaces these afterwards.
     */
    val mutationFailures: List<Throwable> = emptyList(),
    /**
     * The accounts that remain, read **before** the credential was deleted.
     *
     * The caller must publish these rather than re-reading the store. A read
     * after deletion re-enters the migration pass, which can retry the index and
     * active-account writes — so on a persistently failing store it
     * throws inside publication, skipping the failure aggregate and leaving the
     * published account list stale after the session state has already changed.
     */
    val remainingSessions: List<StoredSession> = emptyList(),
)

@Serializable
private data class StoredAccountIndex(
    val accountIds: List<String>,
)

class SessionStore(
    private val secureStore: SecureStore,
    private val json: Json,
) : ServerScopedClearableStore {
    private val mutex = Mutex()

    suspend fun readSession(): StoredSession? =
        mutex.withLock {
            migrateSessionsIfNeededLocked()
            val accountIds = readAccountIdsLocked()
            val activeAccountId = readActiveAccountIdLocked(accountIds) ?: return@withLock null
            readAccountLocked(activeAccountId)
        }

    suspend fun readAccounts(): List<StoredSession> =
        mutex.withLock {
            migrateSessionsIfNeededLocked()
            readStoredSessionsLocked()
        }

    suspend fun readActiveAccountId(): String? =
        mutex.withLock {
            migrateSessionsIfNeededLocked()
            readActiveAccountIdLocked(readAccountIdsLocked())
        }

    suspend fun writeSession(session: StoredSession) {
        mutex.withLock {
            migrateSessionsIfNeededLocked()
            val accountId = session.accountId()
            val accountIds = readAccountIdsLocked()
            val updatedAccountIds =
                if (accountId in accountIds) {
                    accountIds
                } else {
                    accountIds + accountId
                }

            secureStore.write(accountKey(accountId), json.encodeToString(session))
            writeAccountIdsLocked(updatedAccountIds)
            secureStore.write(ACTIVE_ACCOUNT_KEY, accountId)
            secureStore.remove(LEGACY_SESSION_KEY)
            // A newly persisted session supersedes any prior failed-logout marker;
            // leaving it set would make a later cold restore wipe this session.
            secureStore.remove(LOGOUT_PENDING_KEY)
        }
    }

    suspend fun clearSession() {
        mutex.withLock {
            val accountIds = readAccountIdsLocked()
            accountIds.forEach { accountId -> secureStore.remove(accountKey(accountId)) }
            secureStore.remove(ACCOUNT_INDEX_KEY)
            secureStore.remove(ACTIVE_ACCOUNT_KEY)
            secureStore.remove(LEGACY_SESSION_KEY)
        }
    }

    suspend fun markLogoutPending() {
        mutex.withLock {
            secureStore.write(LOGOUT_PENDING_KEY, LOGOUT_PENDING_VALUE)
        }
    }

    suspend fun clearLogoutPending() {
        mutex.withLock {
            secureStore.remove(LOGOUT_PENDING_KEY)
        }
    }

    suspend fun isLogoutPending(): Boolean =
        mutex.withLock {
            secureStore.read(LOGOUT_PENDING_KEY) == LOGOUT_PENDING_VALUE
        }

    suspend fun clearAccount(accountIdentity: AccountIdentity) {
        mutex.withLock {
            migrateSessionsIfNeededLocked()
            clearMatchingAccountsLocked { session ->
                session.serverId == accountIdentity.serverId && session.userId == accountIdentity.userId
            }
        }
    }

    suspend fun switchActiveAccount(accountId: String): StoredSession? =
        mutex.withLock {
            migrateSessionsIfNeededLocked()
            val accountIds = readAccountIdsLocked()
            if (accountId !in accountIds) {
                return@withLock null
            }

            val session = readAccountLocked(accountId) ?: return@withLock null
            secureStore.write(ACTIVE_ACCOUNT_KEY, accountId)
            session
        }

    suspend fun removeAccount(accountId: String): StoredAccountRemoval? =
        mutex.withLock {
            migrateSessionsIfNeededLocked()
            val accountIds = readAccountIdsLocked()
            if (accountId !in accountIds) {
                return@withLock null
            }

            val removedSession = readAccountLocked(accountId) ?: return@withLock null

            // Everything fallible that only READS happens before the first
            // destructive write. Secure stores throw on read failure, so resolving
            // the sibling answer and the post-removal active account up front means
            // a read failure aborts with the account still intact instead of
            // leaving it deleted and the outcome unknown.
            val hasServerSibling =
                accountIds.any { storedAccountId ->
                    storedAccountId != accountId &&
                        readAccountLocked(storedAccountId)?.serverId == removedSession.serverId
                }
            val remainingAccountIds = accountIds.filterNot { id -> id == accountId }
            val previousActiveAccountId = secureStore.read(ACTIVE_ACCOUNT_KEY)
            val nextActiveAccountId =
                if (
                    previousActiveAccountId == null ||
                    previousActiveAccountId == accountId ||
                    previousActiveAccountId !in remainingAccountIds
                ) {
                    remainingAccountIds.firstOrNull()
                } else {
                    previousActiveAccountId
                }
            val activeSession = nextActiveAccountId?.let { id -> readAccountLocked(id) }
            // Read for the caller to publish directly; see remainingSessions.
            val remainingSessions = remainingAccountIds.mapNotNull { id -> readAccountLocked(id) }

            // First destructive write. Nothing is lost yet if it throws, so let it
            // propagate and abort the removal.
            secureStore.remove(accountKey(accountId))

            // Past this point the credential is gone and the removal is
            // irreversible, so each remaining mutation records its failure rather
            // than throwing. Aborting here would skip account cleanup and the
            // published transition — the half-removed state this guards against.
            val mutationFailures = mutableListOf<Throwable>()
            try {
                writeAccountIdsLocked(remainingAccountIds)
            } catch (throwable: Throwable) {
                mutationFailures += throwable
            }
            if (nextActiveAccountId != previousActiveAccountId) {
                try {
                    if (nextActiveAccountId == null) {
                        secureStore.remove(ACTIVE_ACCOUNT_KEY)
                    } else {
                        secureStore.write(ACTIVE_ACCOUNT_KEY, nextActiveAccountId)
                    }
                } catch (throwable: Throwable) {
                    mutationFailures += throwable
                }
            }

            StoredAccountRemoval(
                removedSession = removedSession,
                activeSession = activeSession,
                hasServerSibling = hasServerSibling,
                mutationFailures = mutationFailures.toList(),
                remainingSessions = remainingSessions,
            )
        }

    suspend fun readOrCreateDeviceId(createDeviceId: () -> String): String {
        mutex.withLock {
            val existing = secureStore.read(DEVICE_ID_KEY)
            if (!existing.isNullOrBlank()) {
                return existing
            }

            val created = createDeviceId()
            secureStore.write(DEVICE_ID_KEY, created)
            return created
        }
    }

    override suspend fun clearServerScoped() {
        clearSession()
    }

    override suspend fun clearServerScoped(serverId: String) {
        mutex.withLock {
            migrateSessionsIfNeededLocked()
            clearMatchingAccountsLocked { session -> session.serverId == serverId }
        }
    }

    private suspend fun clearMatchingAccountsLocked(matches: (StoredSession) -> Boolean) {
        val accountIds = readAccountIdsLocked()
        val storedAccounts =
            accountIds.mapNotNull { accountId ->
                readAccountLocked(accountId)?.let { session -> accountId to session }
            }
        val removedIds = storedAccounts.filter { (_, session) -> matches(session) }.map { (accountId, _) -> accountId }
        removedIds.forEach { accountId -> secureStore.remove(accountKey(accountId)) }
        val remainingIds = accountIds.filterNot { accountId -> accountId in removedIds }
        writeAccountIdsLocked(remainingIds)
        val activeAccountId = secureStore.read(ACTIVE_ACCOUNT_KEY)
        if (activeAccountId !in remainingIds) {
            val fallbackAccountId = remainingIds.firstOrNull()
            if (fallbackAccountId == null) {
                secureStore.remove(ACTIVE_ACCOUNT_KEY)
            } else {
                secureStore.write(ACTIVE_ACCOUNT_KEY, fallbackAccountId)
            }
        }
    }

    private suspend fun migrateSessionsIfNeededLocked() {
        if (secureStore.read(ACCOUNT_INDEX_KEY) == null) {
            val legacyEncoded = secureStore.read(LEGACY_SESSION_KEY) ?: return
            val legacySession =
                runCatching { json.decodeFromString<StoredSession>(legacyEncoded) }.getOrNull()
                    ?: run {
                        secureStore.remove(LEGACY_SESSION_KEY)
                        return
                    }
            val legacyAccountId = "${legacySession.serverUrl}$ACCOUNT_ID_SEPARATOR${legacySession.userId}"
            secureStore.write(accountKey(legacyAccountId), json.encodeToString(legacySession))
            writeAccountIdsLocked(listOf(legacyAccountId))
            secureStore.write(ACTIVE_ACCOUNT_KEY, legacyAccountId)
            secureStore.remove(LEGACY_SESSION_KEY)
        }

        val indexedAccountIds = readAccountIdsLocked()
        val activeAccountId = secureStore.read(ACTIVE_ACCOUNT_KEY)
        val indexedSessions =
            indexedAccountIds.mapNotNull { indexedAccountId ->
                val session =
                    secureStore.read(accountKey(indexedAccountId))?.let { encoded ->
                        runCatching { json.decodeFromString<StoredSession>(encoded) }.getOrNull()
                    } ?: return@mapNotNull null
                IndexedSession(indexedAccountId, session)
            }
        val canonicalSessions =
            indexedSessions
                .groupBy { indexed -> indexed.session.accountId() }
                .map { (canonicalAccountId, aliases) ->
                    aliases
                        .firstOrNull { indexed -> indexed.indexedAccountId == activeAccountId }
                        ?.copy(indexedAccountId = canonicalAccountId)
                        ?: aliases.first().copy(indexedAccountId = canonicalAccountId)
                }
        val canonicalAccountIds = canonicalSessions.map { indexed -> indexed.indexedAccountId }
        val canonicalActiveAccountId =
            indexedSessions
                .firstOrNull { indexed -> indexed.indexedAccountId == activeAccountId }
                ?.session
                ?.accountId()
                ?.takeIf { accountId -> accountId in canonicalAccountIds }
                ?: canonicalAccountIds.firstOrNull()

        canonicalSessions.forEach { indexed ->
            val key = accountKey(indexed.indexedAccountId)
            val canonicalValue = json.encodeToString(indexed.session)
            if (secureStore.read(key) != canonicalValue) {
                secureStore.write(key, canonicalValue)
            }
        }
        indexedAccountIds
            .filterNot { indexedAccountId -> indexedAccountId in canonicalAccountIds }
            .forEach { indexedAccountId -> secureStore.remove(accountKey(indexedAccountId)) }
        writeAccountIdsIfChangedLocked(canonicalAccountIds)
        if (canonicalActiveAccountId == null) {
            if (secureStore.read(ACTIVE_ACCOUNT_KEY) != null) {
                secureStore.remove(ACTIVE_ACCOUNT_KEY)
            }
        } else {
            if (secureStore.read(ACTIVE_ACCOUNT_KEY) != canonicalActiveAccountId) {
                secureStore.write(ACTIVE_ACCOUNT_KEY, canonicalActiveAccountId)
            }
        }
    }

    private suspend fun readStoredSessionsLocked(): List<StoredSession> =
        readAccountIdsLocked().mapNotNull { accountId -> readAccountLocked(accountId) }

    private suspend fun readAccountLocked(accountId: String): StoredSession? =
        secureStore.read(accountKey(accountId))?.let { encoded ->
            json.decodeFromString<StoredSession>(encoded)
        }

    private suspend fun readAccountIdsLocked(): List<String> =
        secureStore
            .read(ACCOUNT_INDEX_KEY)
            ?.let { encoded ->
                json.decodeFromString<StoredAccountIndex>(encoded).accountIds
            }.orEmpty()

    private suspend fun writeAccountIdsLocked(accountIds: List<String>) {
        secureStore.write(
            ACCOUNT_INDEX_KEY,
            json.encodeToString(StoredAccountIndex(accountIds.distinct())),
        )
    }

    private suspend fun writeAccountIdsIfChangedLocked(accountIds: List<String>) {
        val canonicalValue = json.encodeToString(StoredAccountIndex(accountIds.distinct()))
        if (secureStore.read(ACCOUNT_INDEX_KEY) != canonicalValue) {
            secureStore.write(ACCOUNT_INDEX_KEY, canonicalValue)
        }
    }

    private suspend fun readActiveAccountIdLocked(accountIds: List<String>): String? {
        val activeAccountId = secureStore.read(ACTIVE_ACCOUNT_KEY)
        if (activeAccountId in accountIds) {
            return activeAccountId
        }

        val fallbackAccountId = accountIds.firstOrNull()
        if (fallbackAccountId == null) {
            if (secureStore.read(ACTIVE_ACCOUNT_KEY) != null) {
                secureStore.remove(ACTIVE_ACCOUNT_KEY)
            }
        } else if (activeAccountId != fallbackAccountId) {
            secureStore.write(ACTIVE_ACCOUNT_KEY, fallbackAccountId)
        }
        return fallbackAccountId
    }
}

private data class IndexedSession(
    val indexedAccountId: String,
    val session: StoredSession,
)

fun StoredSession.accountId(): String = "$serverId$ACCOUNT_ID_SEPARATOR$userId"

private fun accountKey(accountId: String): String = "$ACCOUNT_SESSION_KEY_PREFIX$accountId"
