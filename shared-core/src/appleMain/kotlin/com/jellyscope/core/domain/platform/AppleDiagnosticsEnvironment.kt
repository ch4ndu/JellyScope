// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.platform

import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIDevice

class AppleDiagnosticsEnvironment(
    diagnosticPlatform: PlaybackDiagnosticPlatform,
) : DiagnosticsEnvironment {
    override val platform: String =
        when (diagnosticPlatform) {
            PlaybackDiagnosticPlatform.TvOs -> "tvos"
            else -> "ios"
        }
    override val osVersion: String = NSProcessInfo.processInfo.operatingSystemVersionString
    override val appVersion: String =
        (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String)
            ?.takeIf(String::isNotBlank)
            ?: (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)
                ?.takeIf(String::isNotBlank)
            ?: "unknown"
    override val deviceModel: String = UIDevice.currentDevice.model
}
