// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlayerController
import kotlinx.coroutines.CoroutineScope

internal actual fun createAppleVlcKitController(
    session: Session,
    stateScope: CoroutineScope,
    localSubtitleFileStore: LocalSubtitleFileStore?,
    diagnosticPlatform: PlaybackDiagnosticPlatform,
    offlineArtifactResolver: OfflineArtifactResolver?,
): PlayerController =
    AppleVlcKitPlayerController(
        session = session,
        stateScope = stateScope,
        localSubtitleFileStore = localSubtitleFileStore,
        diagnosticPlatform = diagnosticPlatform,
        surfaceOwnerFactory = { transport -> TvosVlcKitSurface(transport.currentGeneration()) },
    ).also { controller ->
        offlineArtifactResolver?.let(controller::setOfflineArtifactResolver)
    }
