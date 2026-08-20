// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.DownloadSettings
import com.jellyscope.core.domain.model.DownloadUsage
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackProgressEvent
import com.jellyscope.core.domain.playback.PlaybackProgressReporter
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoutingPlaybackProgressReporterTest {
    private val account = AccountIdentity("server-1", "user-1")
    private val session =
        Session(
            serverUrl = "https://jellyfin.example",
            serverId = account.serverId,
            serverName = "Home",
            userId = account.userId,
            userName = "User",
            accessToken = "token",
            deviceId = "device-1",
        )

    @Test
    fun localResumeIsFirstAndWatchedOnlyUsesExplicitCompletion() =
        runTest {
            val events = mutableListOf<String>()
            val repository = RecordingDownloadRepository(events)
            val remote = RecordingRemoteReporter(events)
            val reporter = RoutingPlaybackProgressReporter(repository, remote, LiveSessionRepository(session))
            val plan = offlinePlan()

            reporter.reportProgress(session, plan, "session", 1_000L, false, PlaybackProgressEvent.TimeUpdate)
            reporter.reportStopped(session, plan, "session", 2_000L, completed = false)
            reporter.reportStopped(session, plan, "session", 3_000L, completed = true)

            assertEquals(listOf(null, null, true), repository.watchedValues)
            assertEquals(
                listOf("local:null", "remote:progress", "local:null", "remote:stop", "local:true", "remote:stop"),
                events,
            )
        }

    @Test
    fun localPersistenceFailureStopsBeforeRemoteBestEffortCall() =
        runTest {
            val events = mutableListOf<String>()
            val repository = RecordingDownloadRepository(events, result = false)
            val remote = RecordingRemoteReporter(events)
            val reporter = RoutingPlaybackProgressReporter(repository, remote, LiveSessionRepository(session))

            assertFailsWith<IllegalStateException> {
                reporter.reportProgress(session, offlinePlan(), "session", 1_000L, false, PlaybackProgressEvent.TimeUpdate)
            }
            assertEquals(listOf("local:null"), events)
        }

    @Test
    fun remoteFailureIsSwallowedAfterSuccessfulLocalWrite() =
        runTest {
            val events = mutableListOf<String>()
            val repository = RecordingDownloadRepository(events)
            val remote = RecordingRemoteReporter(events, fail = true)
            val reporter = RoutingPlaybackProgressReporter(repository, remote, LiveSessionRepository(session))

            reporter.reportProgress(session, offlinePlan(), "session", 1_000L, false, PlaybackProgressEvent.TimeUpdate)

            assertTrue(repository.watchedValues.single() == null)
            assertEquals(listOf("local:null", "remote:progress"), events)
        }

    @Test
    fun accountSwitchPreventsRemoteCallForCapturedOfflineSession() =
        runTest {
            val events = mutableListOf<String>()
            val repository = RecordingDownloadRepository(events)
            val remote = RecordingRemoteReporter(events)
            val liveSession = LiveSessionRepository(session)
            val reporter = RoutingPlaybackProgressReporter(repository, remote, liveSession)
            liveSession.sessionState.value = SessionState.LoggedOut(null)

            reporter.reportProgress(session, offlinePlan(), "session", 1_000L, false, PlaybackProgressEvent.TimeUpdate)

            assertEquals(listOf("local:null"), events)
        }

    private fun offlinePlan(): PlaybackPlan =
        PlaybackPlan(
            itemId = "item-1",
            mediaSourceId = "source-1",
            startPositionMs = 0L,
            streamMode = StreamMode.Offline,
            streamUrl = "",
            progressReportingPolicy = ProgressReportingPolicy(10_000L),
            offlineArtifactRef =
                com.jellyscope.core.domain.model
                    .OfflineArtifactRef(DownloadId("download_a"), 4L),
            offlineArtifactKind = DownloadArtifactKind.OriginalFile,
            offlineAccountIdentity = account,
        )
}

private class LiveSessionRepository(
    session: Session?,
) : SessionRepository {
    override val sessionState =
        kotlinx.coroutines.flow.MutableStateFlow<SessionState>(
            session?.let { value -> SessionState.LoggedIn(value) } ?: SessionState.LoggedOut(null),
        )

    override suspend fun setLoggedIn(session: Session) = Unit

    override suspend fun setLoggedOut(
        serverUrl: String?,
        authorization: SessionRemovalAuthorization,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun switchTo(accountId: String): Result<Session> = Result.failure(UnsupportedOperationException("unused"))
}

private class RecordingDownloadRepository(
    private val events: MutableList<String>,
    private val result: Boolean = true,
) : DownloadRepository {
    val watchedValues = mutableListOf<Boolean?>()

    override fun observeDownloads(accountIdentity: AccountIdentity): Flow<List<DownloadRecord>> = emptyFlow()

    override suspend fun getDownload(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadRecord? = null

    override suspend fun getDownloadSettings(): DownloadSettings = DownloadSettings(null, 1L, 0L)

    override suspend fun getDownloadUsage(accountIdentity: AccountIdentity): DownloadUsage = error("unused")

    override suspend fun setQuotaBytes(quotaBytes: Long?): DownloadSettings = error("unused")

    override suspend fun enqueue(request: DownloadRequest): DownloadEnqueueResult = error("unused")

    override suspend fun pause(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult = error("unused")

    override suspend fun resume(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult = error("unused")

    override suspend fun retry(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult = error("unused")

    override suspend fun cancel(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadDeletionResult = error("unused")

    override suspend fun delete(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadDeletionResult = error("unused")

    override suspend fun updateLocalPlayback(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        resumePositionMs: Long,
        watched: Boolean?,
    ): Boolean {
        events += "local:$watched"
        watchedValues += watched
        return result
    }
}

private class RecordingRemoteReporter(
    private val events: MutableList<String>,
    private val fail: Boolean = false,
) : PlaybackProgressReporter {
    override suspend fun reportStart(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
    ) = Unit

    override suspend fun reportProgress(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
        isPaused: Boolean,
        eventName: PlaybackProgressEvent,
    ) {
        events += "remote:progress"
        if (fail) error("deliberate remote failure")
    }

    override suspend fun reportStopped(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
    ) {
        events += "remote:stop"
        if (fail) error("deliberate remote failure")
    }
}
