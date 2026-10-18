// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

/**
 * Supported-platform boundary for the isolated download database.
 *
 * Implementations keep this database and its journals under the same coherent private storage
 * policy as media artifacts. Platform composition supplies the factory; the common download
 * graph owns its database singleton.
 */
@OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
@kotlin.native.HiddenFromObjC
fun interface DownloadDatabaseFactory {
    fun create(): DownloadDatabase
}
