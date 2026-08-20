// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.GateHeldBoundaryCommit
import com.jellyscope.core.domain.model.AccountIdentity

/**
 * Opaque authorization for a destructive session removal. UI modules can pass
 * a participant-issued value but cannot inspect or manufacture one.
 */
sealed interface SessionRemovalAuthorization {
    data object None : SessionRemovalAuthorization

    class Confirmed internal constructor(
        internal val participantToken: Any,
    ) : SessionRemovalAuthorization

    companion object {
        internal fun confirmed(participantToken: Any): SessionRemovalAuthorization = Confirmed(participantToken)
    }
}

sealed class SessionRemovalError(
    message: String,
) : Exception(message) {
    data object DownloadRemovalConfirmationRequired :
        SessionRemovalError("Download removal confirmation is required.")

    data object ConfirmationStale : SessionRemovalError("Download removal confirmation is stale.")

    data object ArtifactInUse : SessionRemovalError("A retained artifact is in use.")
}

sealed interface SessionRemovalScope {
    data class Account(
        val accountIdentity: AccountIdentity,
    ) : SessionRemovalScope

    /**
     * Stable account targets captured by core under the session mutation gate
     * before any logout marker or credential is removed.
     */
    data class FullLogout(
        val accountIdentities: List<AccountIdentity>,
    ) : SessionRemovalScope
}

/**
 * Participant-owned durable operation identity. Core carries it only between
 * prepare and completion and never interprets its payload.
 */
class PreparedSessionRemoval internal constructor(
    internal val participantToken: Any,
) {
    companion object {
        internal fun create(participantToken: Any): PreparedSessionRemoval = PreparedSessionRemoval(participantToken)
    }
}

/**
 * Core-owned secure-session capability supplied only while the session
 * boundary mutation gate is already held. Implementations must not inject a
 * session repository, store, or transition coordinator in order to obtain it.
 */
interface SessionRemovalExecutor {
    suspend fun removeAccount(accountIdentity: AccountIdentity): Result<Unit>

    suspend fun removeAllAccounts(): Result<Unit>

    suspend fun isAccountAbsent(accountIdentity: AccountIdentity): Result<Boolean>
}

/**
 * Generic session-boundary extension point. A supported feature may quiesce
 * account work and maintain its own durable removal facts, while core retains
 * exclusive ownership of credentials and session cleanup.
 */
interface SessionBoundaryParticipant {
    suspend fun beforeAccountSwitch(
        accountIdentity: AccountIdentity,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit>

    suspend fun prepareRemoval(
        scope: SessionRemovalScope,
        authorization: SessionRemovalAuthorization,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<PreparedSessionRemoval?>

    suspend fun completeRemoval(
        removal: PreparedSessionRemoval,
        executor: SessionRemovalExecutor,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit>

    suspend fun resumeIncompleteRemovalOperations(
        executor: SessionRemovalExecutor,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit>
}

object NoOpSessionBoundaryParticipant : SessionBoundaryParticipant {
    override suspend fun beforeAccountSwitch(
        accountIdentity: AccountIdentity,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun prepareRemoval(
        scope: SessionRemovalScope,
        authorization: SessionRemovalAuthorization,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<PreparedSessionRemoval?> = Result.success(null)

    override suspend fun completeRemoval(
        removal: PreparedSessionRemoval,
        executor: SessionRemovalExecutor,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun resumeIncompleteRemovalOperations(
        executor: SessionRemovalExecutor,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> = Result.success(Unit)
}
