// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

/**
 * Supported-platform boundary for the isolated download database.
 *
 * Implementations keep this database and its journals under the same coherent private storage
 * policy as media artifacts. The interface is intentionally not bound by the common or tvOS
 * graph.
 */
internal fun interface DownloadDatabaseFactory {
    fun create(): DownloadDatabase
}
