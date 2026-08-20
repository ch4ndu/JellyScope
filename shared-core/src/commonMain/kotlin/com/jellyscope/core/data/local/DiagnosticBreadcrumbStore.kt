// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

/** A scrubbed record ready for bounded diagnostic persistence. */
data class DiagnosticBreadcrumbRecord(
    val recordId: String,
    val line: String,
    val byteCount: Int,
)

/** A persisted record with the database-owned monotonic sequence. */
data class DiagnosticBreadcrumbStoredRecord(
    val recordId: String,
    val line: String,
    val byteCount: Int,
    val sequence: Long,
)

/** The complete bounded state returned at a serialized database barrier. */
data class DiagnosticBreadcrumbStorageState(
    val records: List<DiagnosticBreadcrumbStoredRecord> = emptyList(),
    val truncationRevision: Long = 0L,
)

/**
 * Narrow persistence boundary for safe breadcrumbs.
 *
 * Implementations must only receive already-scrubbed records. An implementation may throw for
 * an ambiguous open or transaction failure; [com.jellyscope.core.util.LogBufferStore] then keeps
 * a bounded in-memory fallback and never deletes the database in response to that exception.
 */
interface DiagnosticBreadcrumbStore {
    suspend fun hydrate(): DiagnosticBreadcrumbStorageState

    suspend fun appendAndTrim(
        records: List<DiagnosticBreadcrumbRecord>,
        ingressOverflowed: Boolean,
        targetTruncationRevision: Long? = null,
    ): DiagnosticBreadcrumbStorageState

    suspend fun clearThrough(
        maxSequence: Long,
        representedTruncationRevision: Long,
        // Fallback rows may have no durable sequence; delete them by exact local record ID.
        fallbackRecordIds: Set<String> = emptySet(),
    )

    suspend fun clear()

    /** Closes the diagnostic handle and removes only this store's exact file and sidecars. */
    suspend fun purge()
}
