// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlayerController
import kotlinx.coroutines.CoroutineScope

internal expect fun createAppleVlcKitController(
    session: Session,
    stateScope: CoroutineScope,
    localSubtitleFileStore: LocalSubtitleFileStore?,
    diagnosticPlatform: PlaybackDiagnosticPlatform,
    offlineArtifactResolver: OfflineArtifactResolver?,
): PlayerController
