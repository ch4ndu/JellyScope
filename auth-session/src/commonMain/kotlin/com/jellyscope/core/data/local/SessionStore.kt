// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.AccountIdentity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val LEGACY_SESSION_KEY = "session"
private const val LEGACY_ACCOUNT_INDEX_KEY = "session_accounts"
private const val LEGACY_ACTIVE_ACCOUNT_KEY = "active_session_account"
private const val LEGACY_ACCOUNT_SESSION_KEY_PREFIX = "session_account:"
private const val ACCOUNT_ID_SEPARATOR = "|"
private const val DEVICE_ID_KEY = "device_id"
private const val LEGACY_LOGOUT_PENDING_KEY = "session_logout_pending"
private const val LEGACY_LOGOUT_PENDING_VALUE = "true"
internal const val SESSION_ENVELOPE_KEY = "session_envelope"
private const val SESSION_ENVELOPE_VERSION = 1
private const val SESSION_ENVELOPE_ERA = "single-envelope"

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
    val maxParentalRating: Int? = null,
)

internal data class CommittedSessionSnapshot(
    val sessions: List<StoredSession>,
    val activeAccountId: String?,
    val activeSession: StoredSession?,
    val logoutPending: Boolean,
)

internal data class StoredAccountRemoval(
    val removedSession: StoredSession,
    val hasServerSibling: Boolean,
    internal val committedSnapshot: CommittedSessionSnapshot,
    val mutationFailures: List<Throwable> = emptyList(),
) {
    val activeSession: StoredSession?
        get() = committedSnapshot.activeSession
}

@Serializable
private data class StoredSessionEnvelope(
    val version: Int,
    val era: String,
    val sessions: List<StoredSession>,
    val activeAccountId: String?,
    val logoutPending: Boolean,
)

@Serializable
private data class LegacyStoredAccountIndex(
    val accountIds: List<String>,
)

class SessionStore(
    private val secureStore: SecureStore,
    private val json: Json,
) : ServerScopedClearableStore {
    private val mutex = Mutex()

    internal suspend fun readSnapshot(): CommittedSessionSnapshot =
        mutex.withLock {
            readSnapshotLocked()
        }

    suspend fun readSession(): StoredSession? = readSnapshot().activeSession

    suspend fun readAccounts(): List<StoredSession> = readSnapshot().sessions

    suspend fun readActiveAccountId(): String? = readSnapshot().activeAccountId

    internal suspend fun writeSession(session: StoredSession): CommittedSessionSnapshot =
        mutex.withLock {
            val current = readSnapshotLocked()
            val accountId = session.accountId()
            val existingIndex = current.sessions.indexOfFirst { stored -> stored.accountId() == accountId }
            val sessions =
                if (existingIndex >= 0) {
                    current.sessions.toMutableList().apply { set(existingIndex, session) }
                } else {
                    current.sessions + session
                }
            commitSnapshotLocked(
                snapshot(
                    sessions = sessions,
                    activeAccountId = accountId,
                    logoutPending = false,
                ),
            ).snapshot
        }

    suspend fun clearSession() {
        mutex.withLock {
            val current = readSnapshotLocked()
            val commit =
                commitSnapshotLocked(
                    snapshot(
                        sessions = emptyList(),
                        activeAccountId = null,
                        logoutPending = current.logoutPending,
                    ),
                    additionalLegacyAccountIds = current.sessions.map(StoredSession::accountId),
                )
            commit.cleanupFailures.firstOrNull()?.let { failure -> throw failure }
        }
    }

    suspend fun markLogoutPending() {
        mutex.withLock {
            val current = readSnapshotLocked()
            commitSnapshotLocked(
                snapshot(
                    sessions = current.sessions,
                    activeAccountId = current.activeAccountId,
                    logoutPending = true,
                ),
            )
        }
    }

    suspend fun clearLogoutPending() {
        mutex.withLock {
            val current = readSnapshotLocked()
            commitSnapshotLocked(
                snapshot(
                    sessions = current.sessions,
                    activeAccountId = current.activeAccountId,
                    logoutPending = false,
                ),
            )
        }
    }

    suspend fun isLogoutPending(): Boolean = readSnapshot().logoutPending

    suspend fun clearAccount(accountIdentity: AccountIdentity) {
        mutex.withLock {
            clearMatchingAccountsLocked { session ->
                session.serverId == accountIdentity.serverId && session.userId == accountIdentity.userId
            }
        }
    }

    internal suspend fun switchActiveAccount(accountId: String): CommittedSessionSnapshot? =
        mutex.withLock {
            val current = readSnapshotLocked()
            if (current.sessions.none { session -> session.accountId() == accountId }) {
                return@withLock null
            }
            commitSnapshotLocked(
                snapshot(
                    sessions = current.sessions,
                    activeAccountId = accountId,
                    logoutPending = current.logoutPending,
                ),
            ).snapshot
        }

    internal suspend fun removeAccount(accountId: String): StoredAccountRemoval? =
        mutex.withLock {
            val current = readSnapshotLocked()
            val removedSession =
                current.sessions.firstOrNull { session -> session.accountId() == accountId }
                    ?: return@withLock null
            val remainingSessions = current.sessions.filterNot { session -> session.accountId() == accountId }
            val activeAccountId =
                current.activeAccountId
                    ?.takeIf { activeId -> activeId != accountId }
                    ?.takeIf { activeId -> remainingSessions.any { session -> session.accountId() == activeId } }
                    ?: remainingSessions.firstOrNull()?.accountId()
            val commit =
                commitSnapshotLocked(
                    snapshot(
                        sessions = remainingSessions,
                        activeAccountId = activeAccountId,
                        logoutPending = current.logoutPending,
                    ),
                    additionalLegacyAccountIds = current.sessions.map(StoredSession::accountId),
                )
            StoredAccountRemoval(
                removedSession = removedSession,
                hasServerSibling =
                    remainingSessions.any { session -> session.serverId == removedSession.serverId },
                committedSnapshot = commit.snapshot,
                mutationFailures = commit.cleanupFailures,
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
            clearMatchingAccountsLocked { session -> session.serverId == serverId }
        }
    }

    private suspend fun clearMatchingAccountsLocked(matches: (StoredSession) -> Boolean) {
        val current = readSnapshotLocked()
        val remainingSessions = current.sessions.filterNot(matches)
        if (remainingSessions.size == current.sessions.size) {
            return
        }
        val activeAccountId =
            current.activeAccountId
                ?.takeIf { activeId -> remainingSessions.any { session -> session.accountId() == activeId } }
                ?: remainingSessions.firstOrNull()?.accountId()
        val commit =
            commitSnapshotLocked(
                snapshot(
                    sessions = remainingSessions,
                    activeAccountId = activeAccountId,
                    logoutPending = current.logoutPending,
                ),
                additionalLegacyAccountIds = current.sessions.map(StoredSession::accountId),
            )
        commit.cleanupFailures.firstOrNull()?.let { failure -> throw failure }
    }

    private suspend fun readSnapshotLocked(): CommittedSessionSnapshot {
        val encoded = secureStore.read(SESSION_ENVELOPE_KEY)
        if (encoded != null) {
            return decodeEnvelope(encoded)
        }
        return migrateLegacyStateLocked()
    }

    private fun decodeEnvelope(encoded: String): CommittedSessionSnapshot {
        val envelope =
            try {
                json.decodeFromString<StoredSessionEnvelope>(encoded)
            } catch (_: Throwable) {
                throw IllegalStateException("Stored session envelope is invalid.")
            }
        if (envelope.version != SESSION_ENVELOPE_VERSION) {
            throw IllegalStateException("Stored session envelope has unsupported version ${envelope.version}.")
        }
        if (envelope.era != SESSION_ENVELOPE_ERA) {
            throw IllegalStateException("Stored session envelope is invalid.")
        }
        return validatedSnapshot(
            sessions = envelope.sessions,
            activeAccountId = envelope.activeAccountId,
            logoutPending = envelope.logoutPending,
        )
    }

    private suspend fun migrateLegacyStateLocked(): CommittedSessionSnapshot {
        val legacyIndex = secureStore.read(LEGACY_ACCOUNT_INDEX_KEY)
        val legacySingle = secureStore.read(LEGACY_SESSION_KEY)
        val activeAlias = secureStore.read(LEGACY_ACTIVE_ACCOUNT_KEY)
        val logoutPending = secureStore.read(LEGACY_LOGOUT_PENDING_KEY) == LEGACY_LOGOUT_PENDING_VALUE
        val indexedAccountIds =
            legacyIndex?.let { encoded ->
                try {
                    json.decodeFromString<LegacyStoredAccountIndex>(encoded).accountIds
                } catch (_: Throwable) {
                    throw IllegalStateException("Legacy stored session index is invalid.")
                }
            }
        val indexedSessions =
            if (indexedAccountIds == null) {
                legacySingle
                    ?.let { encoded -> decodeLegacySessionOrNull(encoded) }
                    ?.let { session ->
                        listOf(
                            IndexedLegacySession(
                                indexedAccountId = "${session.serverUrl}$ACCOUNT_ID_SEPARATOR${session.userId}",
                                session = session,
                            ),
                        )
                    }.orEmpty()
            } else {
                indexedAccountIds.mapNotNull { indexedAccountId ->
                    secureStore
                        .read(legacyAccountKey(indexedAccountId))
                        ?.let(::decodeLegacySessionOrNull)
                        ?.let { session -> IndexedLegacySession(indexedAccountId, session) }
                }
            }
        val canonicalSessions =
            indexedSessions
                .groupBy { indexed -> indexed.session.accountId() }
                .map { (_, aliases) ->
                    aliases.firstOrNull { indexed -> indexed.indexedAccountId == activeAlias }
                        ?: aliases.first()
                }
        val sessions = canonicalSessions.map { indexed -> indexed.session }
        val canonicalActiveAccountId =
            indexedSessions
                .firstOrNull { indexed -> indexed.indexedAccountId == activeAlias }
                ?.session
                ?.accountId()
                ?.takeIf { accountId -> sessions.any { session -> session.accountId() == accountId } }
                ?: sessions.firstOrNull()?.accountId()
        val commit =
            commitSnapshotLocked(
                snapshot(
                    sessions = sessions,
                    activeAccountId = canonicalActiveAccountId,
                    logoutPending = logoutPending,
                ),
                additionalLegacyAccountIds = indexedAccountIds.orEmpty(),
            )
        return commit.snapshot
    }

    private fun decodeLegacySessionOrNull(encoded: String): StoredSession? =
        try {
            json.decodeFromString<StoredSession>(encoded)
        } catch (_: Throwable) {
            null
        }

    private suspend fun commitSnapshotLocked(
        snapshot: CommittedSessionSnapshot,
        additionalLegacyAccountIds: List<String> = emptyList(),
    ): SnapshotCommit {
        val envelope =
            StoredSessionEnvelope(
                version = SESSION_ENVELOPE_VERSION,
                era = SESSION_ENVELOPE_ERA,
                sessions = snapshot.sessions,
                activeAccountId = snapshot.activeAccountId,
                logoutPending = snapshot.logoutPending,
            )
        secureStore.write(SESSION_ENVELOPE_KEY, json.encodeToString(envelope))
        val cleanupFailures =
            cleanupLegacyAliasesBestEffort(
                additionalLegacyAccountIds + snapshot.sessions.map(StoredSession::accountId),
            )
        return SnapshotCommit(
            snapshot = snapshot,
            cleanupFailures = cleanupFailures,
        )
    }

    private suspend fun cleanupLegacyAliasesBestEffort(accountIds: List<String>): List<Throwable> {
        val aliases =
            buildList {
                add(LEGACY_SESSION_KEY)
                add(LEGACY_ACCOUNT_INDEX_KEY)
                add(LEGACY_ACTIVE_ACCOUNT_KEY)
                add(LEGACY_LOGOUT_PENDING_KEY)
                accountIds.distinct().forEach { accountId -> add(legacyAccountKey(accountId)) }
            }
        val failures = mutableListOf<Throwable>()
        aliases.forEach { alias ->
            try {
                secureStore.remove(alias)
            } catch (throwable: Throwable) {
                // The envelope is already authoritative; legacy cleanup cannot
                // invalidate or roll back the committed snapshot.
                failures += throwable
            }
        }
        return failures.toList()
    }
}

private data class SnapshotCommit(
    val snapshot: CommittedSessionSnapshot,
    val cleanupFailures: List<Throwable>,
)

private data class IndexedLegacySession(
    val indexedAccountId: String,
    val session: StoredSession,
)

private fun snapshot(
    sessions: List<StoredSession>,
    activeAccountId: String?,
    logoutPending: Boolean,
): CommittedSessionSnapshot =
    CommittedSessionSnapshot(
        sessions = sessions.toList(),
        activeAccountId = activeAccountId,
        activeSession = sessions.firstOrNull { session -> session.accountId() == activeAccountId },
        logoutPending = logoutPending,
    )

private fun validatedSnapshot(
    sessions: List<StoredSession>,
    activeAccountId: String?,
    logoutPending: Boolean,
): CommittedSessionSnapshot {
    val accountIds = sessions.map(StoredSession::accountId)
    val valid =
        accountIds.distinct().size == accountIds.size &&
            when {
                sessions.isEmpty() -> activeAccountId == null
                activeAccountId == null -> false
                else -> activeAccountId in accountIds
            }
    if (!valid) {
        throw IllegalStateException("Stored session envelope is incomplete.")
    }
    return snapshot(sessions, activeAccountId, logoutPending)
}

fun StoredSession.accountId(): String = "$serverId$ACCOUNT_ID_SEPARATOR$userId"

private fun legacyAccountKey(accountId: String): String = "$LEGACY_ACCOUNT_SESSION_KEY_PREFIX$accountId"
