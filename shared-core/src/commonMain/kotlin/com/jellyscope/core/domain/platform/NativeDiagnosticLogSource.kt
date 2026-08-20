// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.platform

/**
 * Cleanup owner for platform-native diagnostic files. Raw native logs remain
 * app-internal and never enter client-log uploads.
 */
interface NativeDiagnosticLogSource {
    /** Deletes retained native logs; called when diagnostics collection is disabled. */
    suspend fun clear()

    object None : NativeDiagnosticLogSource {
        override suspend fun clear() = Unit
    }
}
