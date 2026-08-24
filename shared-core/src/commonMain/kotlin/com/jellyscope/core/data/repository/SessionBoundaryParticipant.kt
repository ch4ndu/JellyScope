// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.GateHeldBoundaryCommit
import com.jellyscope.core.domain.action.SessionRemovalAuthorization
import com.jellyscope.core.domain.action.SessionRemovalScope
import com.jellyscope.core.domain.model.AccountIdentity

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
