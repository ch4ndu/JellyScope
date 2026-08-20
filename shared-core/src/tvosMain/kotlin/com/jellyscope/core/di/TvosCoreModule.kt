// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.di

import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import org.koin.core.module.Module

/**
 * tvOS DI entry. The Apple-family bindings are shared with iOS; this seam
 * exists for future tvOS-specific overrides (e.g. Apple TV device-profile
 * tuning) without touching the iOS module.
 */
fun tvosCoreModule(displaySupportsHdr: Boolean = false): Module =
    appleCoreModule(
        diagnosticPlatform = PlaybackDiagnosticPlatform.TvOs,
        displaySupportsHdr = displaySupportsHdr,
    )
