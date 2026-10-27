// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.ui.component.MediaCardUi

sealed interface KidsCatalogueState {
    data object Loading : KidsCatalogueState

    data object Empty : KidsCatalogueState

    data object Error : KidsCatalogueState

    data object Content : KidsCatalogueState
}

sealed interface KidsOfflineState {
    data object Idle : KidsOfflineState

    data object Error : KidsOfflineState
}

data class KidsWatchItem(
    val card: MediaCardUi,
    val offlineDownloadId: DownloadId? = null,
)

data class KidsWatchUiState(
    val catalogueState: KidsCatalogueState = KidsCatalogueState.Loading,
    val offlineState: KidsOfflineState = KidsOfflineState.Idle,
    val selectedItemId: String,
    val recommendations: List<KidsWatchItem> = emptyList(),
    val offlineFallback: List<KidsWatchItem> = emptyList(),
    val hasMissingOfflineArtifacts: Boolean = false,
)

sealed interface KidsWatchIntent {
    data object RetryCatalogue : KidsWatchIntent

    data object RetryOfflineQualification : KidsWatchIntent

    data class ExternalLaunchAccepted(
        val itemId: String,
        val offlineDownloadId: DownloadId?,
    ) : KidsWatchIntent

    data class SelectionAccepted(
        val itemId: String,
    ) : KidsWatchIntent

    data class RemoveOfflineTarget(
        val downloadId: DownloadId,
    ) : KidsWatchIntent
}

internal sealed interface KidsWatchChange {
    data class CatalogueChanged(
        val state: KidsCatalogueState,
    ) : KidsWatchChange

    data class MissingArtifactsChanged(
        val missing: Boolean,
    ) : KidsWatchChange

    data class Selected(
        val itemId: String,
    ) : KidsWatchChange

    data object OfflineFailed : KidsWatchChange

    data class OfflineQualified(
        val hasMissingArtifacts: Boolean,
    ) : KidsWatchChange

    data class ProjectionLoaded(
        val selectedId: String,
        val recommendations: List<KidsWatchItem>,
        val offlineFallback: List<KidsWatchItem>,
    ) : KidsWatchChange
}

internal fun reduceKidsWatch(
    current: KidsWatchUiState,
    change: KidsWatchChange,
): KidsWatchUiState =
    when (change) {
        is KidsWatchChange.CatalogueChanged -> current.copy(catalogueState = change.state)
        is KidsWatchChange.MissingArtifactsChanged -> current.copy(hasMissingOfflineArtifacts = change.missing)
        is KidsWatchChange.Selected -> current.copy(selectedItemId = change.itemId)
        KidsWatchChange.OfflineFailed -> current.copy(offlineState = KidsOfflineState.Error)
        is KidsWatchChange.OfflineQualified ->
            current.copy(
                offlineState = KidsOfflineState.Idle,
                hasMissingOfflineArtifacts = change.hasMissingArtifacts,
            )
        is KidsWatchChange.ProjectionLoaded ->
            if (change.selectedId !=
                current.selectedItemId
            ) {
                current
            } else {
                current.copy(recommendations = change.recommendations, offlineFallback = change.offlineFallback)
            }
    }
