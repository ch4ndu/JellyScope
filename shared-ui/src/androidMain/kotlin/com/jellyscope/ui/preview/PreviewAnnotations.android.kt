// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.preview

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@Preview(
    name = "Compact phone",
    group = "Phone",
    widthDp = 360,
    heightDp = 800,
    showBackground = true,
    backgroundColor = 0xFF081420,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "Phone landscape",
    group = "Phone",
    widthDp = 800,
    heightDp = 360,
    showBackground = true,
    backgroundColor = 0xFF081420,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "Foldable medium",
    group = "Medium",
    widthDp = 700,
    heightDp = 1000,
    showBackground = true,
    backgroundColor = 0xFF081420,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "Tablet expanded",
    group = "Expanded",
    widthDp = 1024,
    heightDp = 768,
    showBackground = true,
    backgroundColor = 0xFF081420,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "Desktop xlarge",
    group = "Desktop",
    widthDp = 1280,
    heightDp = 800,
    showBackground = true,
    backgroundColor = 0xFF081420,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
annotation class JellyScopeScreenPreviews

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@Preview(
    name = "Compact phone",
    group = "Phone",
    widthDp = 360,
    heightDp = 800,
    showBackground = true,
    backgroundColor = 0xFF081420,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "Phone landscape",
    group = "Phone",
    widthDp = 800,
    heightDp = 360,
    showBackground = true,
    backgroundColor = 0xFF081420,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
annotation class JellyScopeCompactPreviews

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@Preview(
    name = "Foldable medium",
    group = "Medium",
    widthDp = 700,
    heightDp = 1000,
    showBackground = true,
    backgroundColor = 0xFF081420,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "Tablet expanded",
    group = "Expanded",
    widthDp = 1024,
    heightDp = 768,
    showBackground = true,
    backgroundColor = 0xFF081420,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "Desktop xlarge",
    group = "Desktop",
    widthDp = 1280,
    heightDp = 800,
    showBackground = true,
    backgroundColor = 0xFF081420,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
annotation class JellyScopeExpandedPreviews

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@Preview(
    name = "TV 1080p dp",
    group = "TV",
    widthDp = 960,
    heightDp = 540,
    showBackground = true,
    backgroundColor = 0xFF081420,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "TV large",
    group = "TV",
    widthDp = 1280,
    heightDp = 720,
    showBackground = true,
    backgroundColor = 0xFF081420,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
annotation class JellyScopeTvPreviews
