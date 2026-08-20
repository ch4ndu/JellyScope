// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackProgressEvent
import com.jellyscope.core.domain.playback.PlaybackProgressReporter
import com.jellyscope.core.domain.playback.StreamMode
import kotlinx.coroutines.CancellationException

/**
 * Supported-download graph reporter: local Offline settlement is durable and first, while the
 * existing remote shape remains a best-effort side effect for the matching online account.
 */
internal class RoutingPlaybackProgressReporter(
    private val downloadRepository: DownloadRepository,
    private val remoteReporter: PlaybackProgressReporter,
    private val sessionRepository: SessionRepository,
) : PlaybackProgressReporter {
    override suspend fun reportStart(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
    ) {
        routeLocal(
            session = session,
            plan = plan,
            positionMs = positionMs,
            watched = null,
        )
        reportRemoteBestEffort(session, plan, playSessionId) {
            remoteReporter.reportStart(session, plan, playSessionId, positionMs)
        }
    }

    override suspend fun reportProgress(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
        isPaused: Boolean,
        eventName: PlaybackProgressEvent,
    ) {
        routeLocal(
            session = session,
            plan = plan,
            positionMs = positionMs,
            watched = null,
        )
        reportRemoteBestEffort(session, plan, playSessionId) {
            remoteReporter.reportProgress(session, plan, playSessionId, positionMs, isPaused, eventName)
        }
    }

    override suspend fun reportStopped(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
    ) {
        reportStopped(session, plan, playSessionId, positionMs, completed = false)
    }

    override suspend fun reportStopped(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
        completed: Boolean,
    ) {
        // `false` is intentionally null: an ordinary stop updates resume only and can never
        // erase a previously watched record. Only controller-proven Completed writes true.
        routeLocal(
            session = session,
            plan = plan,
            positionMs = positionMs,
            watched = completed.takeIf { value -> value },
        )
        reportRemoteBestEffort(session, plan, playSessionId) {
            remoteReporter.reportStopped(session, plan, playSessionId, positionMs, completed)
        }
    }

    private suspend fun routeLocal(
        session: Session,
        plan: PlaybackPlan,
        positionMs: Long,
        watched: Boolean?,
    ) {
        val reference = plan.offlineArtifactRef ?: return
        if (plan.streamMode != StreamMode.Offline) return
        if (plan.offlineAccountIdentity != session.accountIdentity()) return
        try {
            val persisted =
                downloadRepository.updateLocalPlayback(
                    accountIdentity = session.accountIdentity(),
                    downloadId = reference.downloadId,
                    expectedAttemptGeneration = reference.attemptGeneration,
                    resumePositionMs = positionMs.coerceAtLeast(0L),
                    watched = watched,
                )
            check(persisted) { "Offline playback progress was not persisted." }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Throwable) {
            // Local durability is the primary Offline contract. Do not invoke the remote side or
            // report a successful queue command when the durable row rejected this update.
            throw OfflineProgressPersistenceException(exception)
        }
    }

    private suspend fun reportRemoteBestEffort(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        block: suspend () -> Unit,
    ) {
        // The captured playback Session is not sufficient authority after an account switch.
        // Read the live repository state immediately before the remote call and require both the
        // captured session and the explicit Offline plan scope to match the currently online
        // account. A logged-out/account-switched app gets no remote side effect.
        val currentOnlineAccountIdentity =
            (sessionRepository.sessionState.value as? SessionState.LoggedIn)
                ?.session
                ?.accountIdentity()
        if (currentOnlineAccountIdentity != session.accountIdentity()) {
            return
        }
        if (plan.streamMode == StreamMode.Offline &&
            plan.offlineArtifactRef != null &&
            plan.offlineAccountIdentity != currentOnlineAccountIdentity
        ) {
            return
        }
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Matching-online reporting is explicitly best effort and has no retry/outbox.
        }
    }

    private fun Session.accountIdentity(): AccountIdentity = AccountIdentity(serverId, userId)
}

/** Sanitized local-first failure; IDs, paths, URLs, and native messages never leave the router. */
private class OfflineProgressPersistenceException(
    cause: Throwable,
) : IllegalStateException("Offline playback progress could not be persisted.", cause)
