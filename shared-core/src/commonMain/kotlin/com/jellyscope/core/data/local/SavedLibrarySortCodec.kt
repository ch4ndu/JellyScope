// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder

/**
 * Encodes the library sort value using the persisted format already on user
 * disks. The format is a persisted contract, so its shape cannot change.
 */
internal fun SavedLibrarySort.toStoredValue(): String = "${sortBy.name}:${sortOrder.name}"

/**
 * Decodes the persisted library sort value, returning null so the caller can
 * fall back to its default. The format is a persisted contract already on user
 * disks, so its shape must not change. Tolerance is deliberate: an unknown
 * enum name written by a newer build must not throw when an older build reads
 * it after a downgrade.
 */
internal fun String.toSavedLibrarySortOrNull(): SavedLibrarySort? {
    val parts = split(':')
    if (parts.size != 2) {
        return null
    }

    val sortBy = LibrarySortBy.entries.firstOrNull { sortBy -> sortBy.name == parts[0] } ?: return null
    val sortOrder = LibrarySortOrder.entries.firstOrNull { sortOrder -> sortOrder.name == parts[1] } ?: return null
    return SavedLibrarySort(sortBy = sortBy, sortOrder = sortOrder)
}
