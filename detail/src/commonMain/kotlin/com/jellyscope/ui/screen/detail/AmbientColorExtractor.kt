// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.runtime.Composable
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.ui.component.rememberArtworkAmbientColor

// Keep existing detail and platform consumers source compatible with the shared contract.
typealias AmbientImageSource = com.jellyscope.ui.component.AmbientImageSource
typealias AmbientColorExtractor = com.jellyscope.ui.component.AmbientColorExtractor
internal typealias AmbientColorState = com.jellyscope.ui.component.AmbientColorState

expect fun platformAmbientColorExtractor(): AmbientColorExtractor

@Composable
internal fun rememberAmbientColor(
    session: Session,
    itemId: String,
    selectedArtworkUrl: String?,
    extractor: AmbientColorExtractor,
): AmbientColorState =
    rememberArtworkAmbientColor(
        accountIdentity = session.accountIdentity(),
        entry = itemId,
        selectedArtworkUrl = selectedArtworkUrl,
        extractor = extractor,
    )
