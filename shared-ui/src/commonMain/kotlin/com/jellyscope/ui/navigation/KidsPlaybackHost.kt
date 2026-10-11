// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.screen.player.KidsPlaybackLaunchRequest
import com.jellyscope.ui.screen.player.PlayerScreen

@Stable
internal class KidsPlaybackHostState {
    var activeSession by mutableStateOf<KidsPlaybackSession?>(null)
        private set

    fun launch(request: KidsPlaybackLaunchRequest) {
        val current = activeSession
        if (current == null) {
            activeSession = KidsPlaybackSession(request)
        } else {
            current.expanded = true
            current.request = request
        }
    }

    fun close(session: KidsPlaybackSession? = activeSession): Boolean {
        if (session == null || activeSession !== session) return false
        activeSession = null
        session.viewModelStore.clear()
        return true
    }
}

@Stable
internal class KidsPlaybackSession(
    val firstRequest: KidsPlaybackLaunchRequest,
) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    var request by mutableStateOf(firstRequest)
    var expanded by mutableStateOf(true)
}

/** One account-bound surface; closing removes its store before a later launch can create another. */
@Composable
internal fun KidsPlaybackHost(
    state: KidsPlaybackHostState,
    session: Session,
    boundaryEpoch: Long,
    railWidth: () -> Dp,
    bottomPadding: Dp,
    onOpenPlaybackSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val playbackSession = state.activeSession ?: return
    key(playbackSession) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides playbackSession) {
            val first = playbackSession.firstRequest
            val options = first.options
            PlayerScreen(
                session = session,
                boundaryEpoch = boundaryEpoch,
                itemId = first.itemId,
                startPositionTicks = options.startPositionTicks,
                mediaSourceId = options.mediaSourceId,
                initialAudioStreamIndex = options.initialAudioStreamIndex,
                initialSubtitleSelection = options.initialSubtitleSelection,
                queue = options.queue,
                offlineDownloadId = options.offlineDownloadId,
                offlineRestartFromBeginning = options.offlineRestartFromBeginning,
                launchPolicy = options.launchPolicy,
                onBack = { state.close(playbackSession) },
                onOpenPlaybackSettings = {
                    if (state.close(playbackSession)) onOpenPlaybackSettings()
                },
                onOpenDownloads = {
                    if (state.close(playbackSession)) onOpenDownloads()
                },
                modifier = Modifier.fillMaxSize(),
                kidsLaunchRequest = playbackSession.request,
                miniPlayerRailWidth = railWidth,
                miniPlayerBottomPadding = bottomPadding,
                onKidsExpandedChanged = { expanded ->
                    if (state.activeSession === playbackSession) playbackSession.expanded = expanded
                },
            )
        }
    }
}
