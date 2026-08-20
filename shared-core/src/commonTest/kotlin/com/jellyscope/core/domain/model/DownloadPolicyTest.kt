// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DownloadPolicyTest {
    @Test
    fun usageSnapshotPartitionsGlobalPhysicalBytesWithoutChangingQuotaMath() {
        val usage =
            calculateDownloadUsage(
                entries =
                    listOf(
                        DownloadUsageEntry(DownloadState.Completed, physicalBytes = 400L, reservationBytes = 400L),
                        DownloadUsageEntry(DownloadState.Queued, physicalBytes = 100L, reservationBytes = 300L),
                    ),
                quotaBytes = 1_000L,
                deviceAvailableBytes = DOWNLOAD_DEVICE_SAFETY_RESERVE_BYTES + 1_000L,
                currentAccountPhysicalBytes = 400L,
            )

        assertEquals(500L, usage.physicalBytes)
        assertEquals(400L, usage.currentAccountPhysicalBytes)
        assertEquals(100L, usage.otherAccountsPhysicalBytes)
        assertEquals(200L, usage.outstandingReservationBytes)
        assertEquals(700L, usage.projectedCommittedBytes)
        assertEquals(1_500L, usage.maximumConfigurableQuotaBytes)
    }

    @Test
    fun finalizingRetainsReservationUntilCompletedAndBothHardLimitsApply() {
        val quota = DOWNLOAD_BYTES_PER_GIB
        val finalizing = DownloadUsageEntry(DownloadState.Finalizing, physicalBytes = 400L, reservationBytes = 1_000L)
        val completed = DownloadUsageEntry(DownloadState.Completed, physicalBytes = 100L, reservationBytes = 100L)

        val usage =
            calculateDownloadUsage(
                entries = listOf(finalizing, completed),
                quotaBytes = quota,
                deviceAvailableBytes = DOWNLOAD_DEVICE_SAFETY_RESERVE_BYTES + 1_000L,
            )

        assertEquals(500L, usage.physicalBytes)
        assertEquals(600L, usage.outstandingReservationBytes)
        assertEquals(1_100L, usage.projectedCommittedBytes)
        assertEquals(1_500L, usage.maximumConfigurableQuotaBytes)
        assertEquals(DownloadAdmissionDecision.Allowed, evaluateDownloadAdmission(usage, 400L))
        assertEquals(DownloadAdmissionDecision.DeviceStorageLow, evaluateDownloadAdmission(usage, 401L))

        val quotaBound = usage.copy(quotaBytes = 1_499L)
        assertEquals(DownloadAdmissionDecision.QuotaExceeded, evaluateDownloadAdmission(quotaBound, 400L))

        val afterCompletion =
            calculateDownloadUsage(
                entries = listOf(finalizing.copy(state = DownloadState.Completed, reservationBytes = 400L), completed),
                quotaBytes = quota,
                deviceAvailableBytes = DOWNLOAD_DEVICE_SAFETY_RESERVE_BYTES,
            )
        assertEquals(0L, afterCompletion.outstandingReservationBytes)
        assertEquals(500L, afterCompletion.maximumConfigurableQuotaBytes)
    }

    @Test
    fun quotaAndEstimateArithmeticSaturateInsteadOfWrapping() {
        val usage =
            calculateDownloadUsage(
                entries =
                    listOf(
                        DownloadUsageEntry(
                            state = DownloadState.Queued,
                            physicalBytes = Long.MAX_VALUE - 10L,
                            reservationBytes = Long.MAX_VALUE,
                        ),
                        DownloadUsageEntry(
                            state = DownloadState.Completed,
                            physicalBytes = 20L,
                            reservationBytes = 20L,
                        ),
                    ),
                quotaBytes = Long.MAX_VALUE,
                deviceAvailableBytes = Long.MAX_VALUE,
            )

        assertEquals(Long.MAX_VALUE, usage.physicalBytes)
        assertEquals(10L, usage.outstandingReservationBytes)
        assertEquals(Long.MAX_VALUE, usage.projectedCommittedBytes)
        assertEquals(Long.MAX_VALUE, usage.maximumConfigurableQuotaBytes)
        assertEquals(DownloadAdmissionDecision.QuotaExceeded, evaluateDownloadAdmission(usage, 1L))

        assertEquals(1_100_000L, estimateFixedDownloadBytes(maxBitrateBps = 8_000_000L, durationMs = 1_000L))
        assertEquals(8_798L, estimateFixedDownloadBytes(maxBitrateBps = 7_999L, durationMs = 7_999L))
        assertEquals(Long.MAX_VALUE, estimateFixedDownloadBytes(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(DOWNLOAD_BYTES_PER_GIB, wholeGibDownloadQuotaBytes(1L))
        assertNull(wholeGibDownloadQuotaBytes(Long.MAX_VALUE))
    }

    @Test
    fun activeAccountUsesDurableFifoWithoutSkippingABlockedHead() {
        val activeAccount = AccountIdentity("server", "active")
        val inactiveAccount = AccountIdentity("server", "inactive")
        val inactiveFirst = record("inactive", inactiveAccount, fifoSequence = 1L, state = DownloadState.Queued)
        val blockedHead = record("blocked", activeAccount, fifoSequence = 2L, state = DownloadState.BlockedByQuota)
        val later = record("later", activeAccount, fifoSequence = 3L, state = DownloadState.Queued)

        assertEquals(
            blockedHead,
            assertIs<DownloadQueueSelection.BlockedByQuota>(
                selectDownloadQueueHead(listOf(later, inactiveFirst, blockedHead), activeAccount),
            ).record,
        )

        val requeuedHead = blockedHead.copy(state = DownloadState.Queued)
        assertEquals(
            requeuedHead,
            assertIs<DownloadQueueSelection.Ready>(
                selectDownloadQueueHead(listOf(later, inactiveFirst, requeuedHead), activeAccount),
            ).record,
        )

        val deviceActive = inactiveFirst.copy(state = DownloadState.Finalizing)
        assertIs<DownloadQueueSelection.ActiveSlotOccupied>(
            selectDownloadQueueHead(listOf(later, requeuedHead, deviceActive), activeAccount),
        )
    }

    @Test
    fun legalTransitionsAndAttemptGenerationFailClosed() {
        assertEquals(true, DownloadState.Downloading.canTransitionTo(DownloadState.Finalizing))
        assertEquals(false, DownloadState.Finalizing.canTransitionTo(DownloadState.Queued))
        assertEquals(true, DownloadState.Finalizing.ownsActiveDownloadSlot)
        assertEquals(true, DownloadState.Finalizing.retainsOutstandingReservation)
        assertEquals(8L, nextDownloadAttemptGeneration(7L))
        assertNull(nextDownloadAttemptGeneration(-1L))
        assertNull(nextDownloadAttemptGeneration(Long.MAX_VALUE))
    }

    private fun record(
        suffix: String,
        accountIdentity: AccountIdentity,
        fifoSequence: Long,
        state: DownloadState,
    ): DownloadRecord =
        DownloadRecord(
            request =
                DownloadRequest(
                    downloadId = DownloadId("download_$suffix"),
                    businessKey = DownloadBusinessKey(accountIdentity, "item_$suffix", "source_$suffix"),
                    quality = DownloadQuality.Original,
                    artifactKind = DownloadArtifactKind.OriginalFile,
                    selectedAudioStreamIndex = null,
                    subtitleSelection = DownloadSubtitleSelection.Off,
                    admissionEstimateBytes = 100L,
                    initialReservationBytes = 100L,
                    expectedSourceBytes = 100L,
                    artifactKey = DownloadArtifactKey("artifact_$suffix"),
                    snapshot =
                        OfflineMediaSnapshot(
                            title = "Title $suffix",
                            itemKind = MediaKind.Movie,
                            backendSource = BackendSourceDescriptor("mkv", "h264", "aac", false),
                        ),
                    createdAtEpochMs = 1L,
                ),
            fifoSequence = fifoSequence,
            state = state,
            reservationBytes = 100L,
            physicalBytes = 0L,
            checkpointBytes = 0L,
            attemptGeneration = 0L,
            updatedAtEpochMs = 1L,
        )
}
