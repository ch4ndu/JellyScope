// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

val DownloadState.isPersistedDownloadState: Boolean
    get() = this != DownloadState.NotDownloaded

val DownloadState.ownsActiveDownloadSlot: Boolean
    get() = this == DownloadState.Downloading || this == DownloadState.Finalizing

val DownloadState.retainsOutstandingReservation: Boolean
    get() =
        this == DownloadState.Queued ||
            this == DownloadState.Downloading ||
            this == DownloadState.Paused ||
            this == DownloadState.BlockedByQuota ||
            this == DownloadState.Finalizing

fun DownloadState.canTransitionTo(next: DownloadState): Boolean =
    when (this) {
        DownloadState.NotDownloaded -> next == DownloadState.Queued
        DownloadState.Queued -> next == DownloadState.Downloading || next == DownloadState.BlockedByQuota
        DownloadState.Downloading ->
            next == DownloadState.Queued ||
                next == DownloadState.Paused ||
                next == DownloadState.BlockedByQuota ||
                next == DownloadState.Finalizing ||
                next == DownloadState.Failed

        DownloadState.Paused,
        DownloadState.BlockedByQuota,
        DownloadState.Failed,
        -> next == DownloadState.Queued

        DownloadState.Finalizing -> next == DownloadState.Completed || next == DownloadState.Failed
        DownloadState.Completed -> next == DownloadState.NotDownloaded
    }

fun nextDownloadAttemptGeneration(current: Long): Long? =
    current
        .takeIf { generation -> generation >= 0L && generation < Long.MAX_VALUE }
        ?.plus(1L)

fun calculateDownloadUsage(
    entries: Iterable<DownloadUsageEntry>,
    quotaBytes: Long?,
    deviceAvailableBytes: Long,
    safetyReserveBytes: Long = DOWNLOAD_DEVICE_SAFETY_RESERVE_BYTES,
    currentAccountPhysicalBytes: Long = 0L,
): DownloadUsage {
    require(quotaBytes == null || quotaBytes >= 0L) { "Quota must be non-negative when present." }
    require(deviceAvailableBytes >= 0L) { "Device available bytes must be non-negative." }
    require(safetyReserveBytes >= 0L) { "Safety reserve must be non-negative." }

    var physicalBytes = 0L
    var outstandingReservationBytes = 0L
    entries.forEach { entry ->
        physicalBytes = saturatingAddNonNegative(physicalBytes, entry.physicalBytes)
        physicalBytes = saturatingAddNonNegative(physicalBytes, entry.presentationBytes)
        if (entry.state.retainsOutstandingReservation) {
            outstandingReservationBytes =
                saturatingAddNonNegative(
                    outstandingReservationBytes,
                    // Reservation remains media-only even though persisted use also includes presentation.
                    subtractClamped(entry.reservationBytes, entry.physicalBytes),
                )
        }
    }
    val projectedCommittedBytes = saturatingAddNonNegative(physicalBytes, outstandingReservationBytes)
    val safeAvailableBytes = subtractClamped(deviceAvailableBytes, safetyReserveBytes)
    val maximumConfigurableQuotaBytes = saturatingAddNonNegative(physicalBytes, safeAvailableBytes)
    val remainingQuotaBytes = quotaBytes?.let { quota -> subtractClamped(quota, projectedCommittedBytes) }
    require(currentAccountPhysicalBytes in 0L..physicalBytes) {
        "Current-account physical bytes must be within the device-global total."
    }

    return DownloadUsage(
        physicalBytes = physicalBytes,
        currentAccountPhysicalBytes = currentAccountPhysicalBytes,
        otherAccountsPhysicalBytes = physicalBytes - currentAccountPhysicalBytes,
        outstandingReservationBytes = outstandingReservationBytes,
        projectedCommittedBytes = projectedCommittedBytes,
        quotaBytes = quotaBytes,
        remainingQuotaBytes = remainingQuotaBytes,
        deviceAvailableBytes = deviceAvailableBytes,
        safetyReserveBytes = safetyReserveBytes,
        maximumConfigurableQuotaBytes = maximumConfigurableQuotaBytes,
        overAllocation = quotaBytes != null && projectedCommittedBytes > quotaBytes,
    )
}

fun evaluateDownloadAdmission(
    usage: DownloadUsage,
    additionalReservationBytes: Long,
): DownloadAdmissionDecision {
    require(additionalReservationBytes > 0L) { "Additional reservation must be positive." }
    val quota = usage.quotaBytes ?: return DownloadAdmissionDecision.QuotaUnconfigured
    if (!fitsWithinLimit(usage.projectedCommittedBytes, additionalReservationBytes, quota)) {
        return DownloadAdmissionDecision.QuotaExceeded
    }
    if (!fitsWithinLimit(
            usage.projectedCommittedBytes,
            additionalReservationBytes,
            usage.maximumConfigurableQuotaBytes,
        )
    ) {
        return DownloadAdmissionDecision.DeviceStorageLow
    }
    return DownloadAdmissionDecision.Allowed
}

/** Converts the whole-GB Settings value without permitting integer wraparound. */
fun wholeGbDownloadQuotaBytes(wholeGb: Long): Long? =
    wholeGb
        .takeIf { value -> value > 0L && value <= Long.MAX_VALUE / DOWNLOAD_BYTES_PER_GB }
        ?.times(DOWNLOAD_BYTES_PER_GB)

/** Fixed-quality admission estimate: bitrate x duration plus a conservative ten percent. */
fun estimateFixedDownloadBytes(
    maxBitrateBps: Long,
    durationMs: Long,
): Long? {
    if (maxBitrateBps <= 0L || durationMs <= 0L) return null
    val baseBytes = multiplyDivideSaturating(maxBitrateBps, durationMs, 8_000L)
    val varianceBytes = ceilDivideNonNegative(baseBytes, 10L)
    return saturatingAddNonNegative(baseBytes, varianceBytes)
}

sealed interface DownloadQueueSelection {
    data object Empty : DownloadQueueSelection

    data object ActiveSlotOccupied : DownloadQueueSelection

    data class Ready(
        val record: DownloadRecord,
    ) : DownloadQueueSelection

    data class BlockedByQuota(
        val record: DownloadRecord,
    ) : DownloadQueueSelection
}

/** Active-account eligibility over one device-global active slot and durable FIFO sequence. */
fun selectDownloadQueueHead(
    records: Iterable<DownloadRecord>,
    activeAccount: AccountIdentity,
): DownloadQueueSelection {
    val materialized = records.toList()
    if (materialized.any { record -> record.state.ownsActiveDownloadSlot }) {
        return DownloadQueueSelection.ActiveSlotOccupied
    }
    val head =
        materialized
            .asSequence()
            .filter { record -> record.businessKey.accountIdentity == activeAccount }
            .filter { record -> record.state == DownloadState.Queued || record.state == DownloadState.BlockedByQuota }
            .minWithOrNull(compareBy<DownloadRecord> { record -> record.fifoSequence }.thenBy { record -> record.downloadId.value })
            ?: return DownloadQueueSelection.Empty

    return if (head.state == DownloadState.BlockedByQuota) {
        DownloadQueueSelection.BlockedByQuota(head)
    } else {
        DownloadQueueSelection.Ready(head)
    }
}

internal fun saturatingAddNonNegative(
    first: Long,
    second: Long,
): Long {
    require(first >= 0L && second >= 0L)
    return if (first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second
}

private fun subtractClamped(
    first: Long,
    second: Long,
): Long = if (first <= second) 0L else first - second

private fun fitsWithinLimit(
    current: Long,
    additional: Long,
    limit: Long,
): Boolean = current <= limit && additional <= limit - current

/** Exact floor(a*b/divisor) until the mathematical result exceeds Long, then saturates. */
private fun multiplyDivideSaturating(
    first: Long,
    second: Long,
    divisor: Long,
): Long {
    require(first >= 0L && second >= 0L && divisor > 0L)
    val firstQuotient = first / divisor
    val firstRemainder = first % divisor
    val secondQuotient = second / divisor
    val secondRemainder = second % divisor

    val wholeProduct = saturatingMultiplyNonNegative(firstQuotient, second)
    val remainderWholeProduct = saturatingMultiplyNonNegative(firstRemainder, secondQuotient)
    val remainderProduct = multiplyDivideBelowDivisor(firstRemainder, secondRemainder, divisor)
    return saturatingAddNonNegative(
        saturatingAddNonNegative(wholeProduct, remainderWholeProduct),
        remainderProduct,
    )
}

/** Exact floor(a*b/divisor) when both operands are below divisor, without multiplying them. */
private fun multiplyDivideBelowDivisor(
    first: Long,
    second: Long,
    divisor: Long,
): Long {
    var factor = first
    var multiplicandQuotient = 0L
    var multiplicandRemainder = second
    var resultQuotient = 0L
    var resultRemainder = 0L
    while (factor > 0L) {
        if ((factor and 1L) == 1L) {
            resultQuotient += multiplicandQuotient
            val distanceToDivisor = divisor - multiplicandRemainder
            if (resultRemainder >= distanceToDivisor) {
                resultQuotient += 1L
                resultRemainder -= distanceToDivisor
            } else {
                resultRemainder += multiplicandRemainder
            }
        }
        factor = factor ushr 1
        if (factor > 0L) {
            multiplicandQuotient *= 2L
            val distanceToDivisor = divisor - multiplicandRemainder
            if (multiplicandRemainder >= distanceToDivisor) {
                multiplicandQuotient += 1L
                multiplicandRemainder -= distanceToDivisor
            } else {
                multiplicandRemainder += multiplicandRemainder
            }
        }
    }
    return resultQuotient
}

private fun saturatingMultiplyNonNegative(
    first: Long,
    second: Long,
): Long {
    require(first >= 0L && second >= 0L)
    if (first == 0L || second == 0L) return 0L
    return if (first > Long.MAX_VALUE / second) Long.MAX_VALUE else first * second
}

private fun ceilDivideNonNegative(
    value: Long,
    divisor: Long,
): Long {
    require(value >= 0L && divisor > 0L)
    return value / divisor + if (value % divisor == 0L) 0L else 1L
}
