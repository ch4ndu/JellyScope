// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.OfflineArtifactRef
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OfflineControllerAccountBindingTest {
    @Test
    fun planAccountMustMatchControllerSessionBeforeResolverAdmission() =
        runTest {
            val planAccount = AccountIdentity(serverId = "server-owner", userId = "user-owner")
            val currentSessionAccount = AccountIdentity(serverId = "server-current", userId = "user-current")
            var resolverCalls = 0
            val resolver =
                object : OfflineArtifactResolver {
                    override suspend fun acquire(
                        expectedAccountIdentity: AccountIdentity,
                        reference: OfflineArtifactRef,
                    ): OfflineArtifactResolution {
                        resolverCalls += 1
                        return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.MissingArtifact)
                    }
                }
            val plan =
                PlaybackPlan(
                    itemId = "item-1",
                    mediaSourceId = "source-1",
                    startPositionMs = 0L,
                    streamMode = StreamMode.Offline,
                    streamUrl = "",
                    progressReportingPolicy = ProgressReportingPolicy(reportIntervalMs = 10_000L),
                    offlineArtifactRef = OfflineArtifactRef(DownloadId("download-1"), 2L),
                    offlineArtifactKind = DownloadArtifactKind.OriginalFile,
                    offlineAccountIdentity = planAccount,
                )

            val result = resolver.acquireForOfflinePlan(plan, currentSessionAccount)

            assertEquals(0, resolverCalls)
            assertEquals(
                OfflineArtifactResolutionFailure.UnauthorizedOrMissing,
                assertIs<OfflineArtifactResolution.Unavailable>(result).failure,
            )
        }
}
