// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.domain.model.DownloadPlatformWorkIdentity
import com.jellyscope.core.domain.model.DownloadPlatformWorkKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AndroidDownloadHostPolicyTest {
    @Test
    fun api34AndNewerSelectOnlyUidt() {
        assertEquals(AndroidDownloadExecutionPath.Uidt, androidDownloadExecutionPath(34))
        assertEquals(AndroidDownloadExecutionPath.Uidt, androidDownloadExecutionPath(36))
    }

    @Test
    fun api33AndLowerSelectOnlyWorkManager() {
        assertEquals(AndroidDownloadExecutionPath.WorkManager, androidDownloadExecutionPath(25))
        assertEquals(AndroidDownloadExecutionPath.WorkManager, androidDownloadExecutionPath(33))
    }

    @Test
    fun workIdentityCarriesExactlyTheSelectedPlatformKind() {
        assertEquals(
            DownloadPlatformWorkKind.AndroidUserInitiatedJob,
            androidDownloadWorkIdentity(AndroidDownloadExecutionPath.Uidt, "uidt-job").kind,
        )
        assertEquals(
            DownloadPlatformWorkKind.AndroidWorkManager,
            androidDownloadWorkIdentity(AndroidDownloadExecutionPath.WorkManager, "work-id").kind,
        )
    }

    @Test
    fun notificationCancelIntentRoundTripsTheExactPlatformIdentity() {
        val payload =
            AndroidDownloadScheduler.cancelPayload(
                DownloadPlatformWorkIdentity(
                    kind = DownloadPlatformWorkKind.AndroidWorkManager,
                    value = "work-identity",
                ),
            )
        val identity =
            AndroidDownloadScheduler.identityFromPayload(
                action = payload.action,
                platformWorkKind = payload.platformWorkKind,
                platformWorkIdentity = payload.platformWorkIdentity,
            )

        assertNotNull(identity)
        assertEquals(DownloadPlatformWorkKind.AndroidWorkManager, identity.kind)
        assertEquals("work-identity", identity.value)
    }

    @Test
    fun unrelatedIntentCannotInvokeDownloadCancellation() {
        assertNull(
            AndroidDownloadScheduler.identityFromPayload(
                action = "other.action",
                platformWorkKind = DownloadPlatformWorkKind.AndroidWorkManager.name,
                platformWorkIdentity = "work-identity",
            ),
        )
    }

    @Test
    fun notificationCancellationSettlesDurableRowBeforeNativeWork() =
        runBlocking {
            val events = mutableListOf<String>()
            val result =
                cancelDurableThenNative(
                    cancelRequested = {
                        events += "durable"
                        Result.success(Unit)
                    },
                    cancelNative = {
                        events += "native"
                        Result.success(Unit)
                    },
                )

            assertEquals(listOf("durable", "native"), events)
            assertEquals(true, result.isSuccess)
        }

    @Test
    fun nativeCancellationStillRunsWhenDurableCommandIsRejected() =
        runBlocking {
            val events = mutableListOf<String>()
            val result =
                cancelDurableThenNative(
                    cancelRequested = {
                        events += "durable"
                        Result.failure(IllegalStateException("stale row"))
                    },
                    cancelNative = {
                        events += "native"
                        Result.success(Unit)
                    },
                )

            assertEquals(listOf("durable", "native"), events)
            assertEquals(true, result.isFailure)
        }
}
