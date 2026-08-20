// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

/**
 * Marks that the complete fixed-quality download vertical is installed.
 *
 * The detail UI uses this concrete capability instead of treating one nullable
 * admission service as proof that transport and queue execution are available.
 * Only [com.jellyscope.core.di.downloadsModule] installs it, so graphs that
 * intentionally omit downloads (including tvOS/core-only graphs) do not expose
 * fixed-quality choices.
 */
class FixedDownloadCapability internal constructor()
