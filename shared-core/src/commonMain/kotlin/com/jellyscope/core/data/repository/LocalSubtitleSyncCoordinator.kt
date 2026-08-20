// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.domain.model.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal class LocalSubtitleSyncCoordinator(
    sessionRepository: SessionRepository,
    assetStore: LocalSubtitleAssetStore,
    private val syncRepository: LocalSubtitleSyncRepository,
    scope: CoroutineScope,
) {
    init {
        scope.launch {
            combine(sessionRepository.sessionState, assetStore.observePendingSync()) { session, assets -> session to assets }
                .collectLatest { (state, assets) ->
                    val session = (state as? SessionState.LoggedIn)?.session ?: return@collectLatest
                    assets.filter { it.serverId == session.serverId && it.userId == session.userId }.forEach { asset ->
                        syncRepository.sync(session, asset)
                    }
                }
        }
    }
}
