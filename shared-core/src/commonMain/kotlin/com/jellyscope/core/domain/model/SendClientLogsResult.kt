// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

sealed interface SendClientLogsResult {
    data class Success(
        val filename: String,
    ) : SendClientLogsResult

    data object UploadDisallowed : SendClientLogsResult

    data object Failure : SendClientLogsResult
}
