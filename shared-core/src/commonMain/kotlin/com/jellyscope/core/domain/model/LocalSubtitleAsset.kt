// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

data class LocalSubtitleAsset(
    val id: String,
    val serverId: String,
    val userId: String,
    val itemId: String,
    val mediaSourceId: String,
    val provider: String,
    val providerSubtitleId: String,
    val providerFileId: String,
    val language: String,
    val label: String,
    val releaseName: String?,
    val originalFormat: String,
    val mimeType: String,
    val fileId: String,
    val hearingImpaired: Boolean,
    val forced: Boolean,
    val trusted: Boolean,
    val createdAtEpochMs: Long,
    val lastUsedAtEpochMs: Long,
    val syncState: LocalSubtitleSyncState,
    val confirmedStreamIndex: Int? = null,
    val uploadBaseline: String? = null,
)

sealed interface LocalSubtitleSyncState {
    data object Pending : LocalSubtitleSyncState

    data object Uploading : LocalSubtitleSyncState

    data object Reconciling : LocalSubtitleSyncState

    data class Confirmed(
        val streamIndex: Int,
    ) : LocalSubtitleSyncState

    data object UploadedUnconfirmed : LocalSubtitleSyncState

    data object LocalOnlyAlternateSource : LocalSubtitleSyncState

    data object PermissionDenied : LocalSubtitleSyncState

    data object FailedPermanent : LocalSubtitleSyncState
}

data class LocalSubtitleContext(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val mediaSourceId: String,
)
