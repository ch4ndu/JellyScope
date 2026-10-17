// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

sealed interface DownloadCommandResult {
    data object Applied : DownloadCommandResult

    data object NotFound : DownloadCommandResult

    data object AccountNotOwned : DownloadCommandResult

    data object RemovalInProgress : DownloadCommandResult

    /** The durable row is active, but no live writer registration can safely quiesce it. */
    data object ActiveAttemptUnavailable : DownloadCommandResult

    /** The durable command was applied, but the platform could not admit the follow-up wake. */
    data object SchedulingRejected : DownloadCommandResult

    data object InvalidState : DownloadCommandResult
}

sealed interface DownloadDeletionResult {
    data object Deleted : DownloadDeletionResult

    data object NotFound : DownloadDeletionResult

    data object InvalidState : DownloadDeletionResult

    data object AccountNotOwned : DownloadDeletionResult

    data object RemovalInProgress : DownloadDeletionResult

    /** The durable row is active, but no live writer registration can safely quiesce it. */
    data object ActiveAttemptUnavailable : DownloadDeletionResult

    data object ArtifactInUse : DownloadDeletionResult
}
