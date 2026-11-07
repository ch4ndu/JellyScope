// SPDX-License-Identifier: MPL-2.0

@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.jellyscope.core.data.local

import com.jellyscope.core.data.remote.SeerrCookie
import com.jellyscope.core.data.remote.normalizeSeerrEndpoint
import com.jellyscope.core.data.remote.validSeerrCookies
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.SeerrLoginMethod
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.native.HiddenFromObjC
import kotlin.time.Clock

/** One addressable envelope; cleanup never depends on the feature being constructed. */
@HiddenFromObjC
class SeerrSessionStore(
    private val secureStore: SecureStore,
    json: Json,
) : AccountScopedClearableStore {
    private val json =
        Json(json) {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
        }
    private val mutex = Mutex()

    suspend fun read(account: AccountIdentity): StoredSeerrSession? =
        mutex.withLock {
            readEnvelope().entries.firstOrNull { it.serverId == account.serverId && it.userId == account.userId }?.session?.let {
                it.copy(cookies = it.cookies.filterNot { cookie -> cookie.expired(Clock.System.now().toEpochMilliseconds()) })
            }
        }

    /** Caller holds the current account lease and connection guard after /auth/me verification. */
    suspend fun writeVerified(
        account: AccountIdentity,
        session: StoredSeerrSession,
    ) = mutex.withLock {
        require(session.valid())
        val previous = readEnvelope().entries
        val entries =
            previous.filterNot { it.serverId == account.serverId && it.userId == account.userId } +
                StoredSeerrAccount(account.serverId, account.userId, session)
        val encoded = json.encodeToString(StoredSeerrEnvelope(1, entries))
        require(encoded.length <= MAX_ENVELOPE_CHARS)
        secureStore.write(SEERR_SESSION_KEY, encoded)
    }

    override suspend fun clearServerScoped() = mutex.withLock { secureStore.remove(SEERR_SESSION_KEY) }

    override suspend fun clearServerScoped(serverId: String) = removeEntries { it.serverId == serverId }

    override suspend fun clearAccount(accountIdentity: AccountIdentity) =
        removeEntries {
            it.serverId == accountIdentity.serverId && it.userId == accountIdentity.userId
        }

    private suspend fun removeEntries(predicate: (StoredSeerrAccount) -> Boolean) =
        mutex.withLock {
            val entries = readEnvelope().entries.filterNot(predicate)
            if (entries.isEmpty()) {
                secureStore.remove(SEERR_SESSION_KEY)
            } else {
                secureStore.write(SEERR_SESSION_KEY, json.encodeToString(StoredSeerrEnvelope(1, entries)))
            }
        }

    private suspend fun readEnvelope(): StoredSeerrEnvelope {
        val value = secureStore.read(SEERR_SESSION_KEY) ?: return StoredSeerrEnvelope(1, emptyList())
        val envelope =
            try {
                require(value.length <= MAX_ENVELOPE_CHARS)
                json.decodeFromString<StoredSeerrEnvelope>(value).also { decoded ->
                    require(decoded.version == 1)
                    require(decoded.entries.all { it.serverId.isNotBlank() && it.userId.isNotBlank() && it.session.valid() })
                    require(
                        decoded.entries
                            .map { it.serverId to it.userId }
                            .distinct()
                            .size == decoded.entries.size,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.w { formatSafeFailureDiagnostic("seerr-persistence", "envelope-invalid", failure) }
                null
            }
        if (envelope != null) return envelope
        // Corrupt credentials are never partially restored. Deletion failure is observable.
        secureStore.remove(SEERR_SESSION_KEY)
        return StoredSeerrEnvelope(1, emptyList())
    }
}

@HiddenFromObjC
@Serializable
data class StoredSeerrSession(
    val endpoint: String,
    val seerrUserId: Int,
    val loginMethod: String,
    val cookies: List<SeerrCookie>,
) {
    internal fun valid(): Boolean =
        seerrUserId > 0 &&
            loginMethod in SeerrLoginMethod.entries.map { it.name } &&
            normalizeSeerrEndpoint(endpoint) == endpoint &&
            validSeerrCookies(endpoint, cookies)
}

@Serializable
private data class StoredSeerrAccount(
    val serverId: String,
    val userId: String,
    val session: StoredSeerrSession,
)

@Serializable
private data class StoredSeerrEnvelope(
    val version: Int,
    val entries: List<StoredSeerrAccount>,
)

internal const val SEERR_SESSION_KEY = "seerr_session_envelope"
private const val MAX_ENVELOPE_CHARS = 8 * 1024 * 1024
private val logger = diagnosticLogger(DiagnosticTag.SessionRepository)
