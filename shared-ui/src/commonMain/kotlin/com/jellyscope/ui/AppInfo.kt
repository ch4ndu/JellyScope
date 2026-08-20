// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui

data class AppInfo(
    val versionName: String,
    val sourceRevision: String = DistributionBuildInfo.SOURCE_REVISION,
)
