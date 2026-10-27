// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

sealed interface TvDownloadsIntent {
    data object Refresh : TvDownloadsIntent

    data class ConfigureQuota(
        val wholeGb: Long,
    ) : TvDownloadsIntent

    data class Pause(
        val downloadId: String,
    ) : TvDownloadsIntent

    data class Resume(
        val downloadId: String,
    ) : TvDownloadsIntent

    data class Retry(
        val downloadId: String,
    ) : TvDownloadsIntent

    data object ResumeAll : TvDownloadsIntent

    data object ResumeQueuedDownloads : TvDownloadsIntent

    data class Cancel(
        val downloadId: String,
        val expectedAttemptGeneration: Long,
    ) : TvDownloadsIntent

    data class Delete(
        val downloadId: String,
        val expectedAttemptGeneration: Long,
    ) : TvDownloadsIntent
}
