// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.ui.unit.Dp

internal fun settingsColumnCount(
    availableWidth: Dp,
    minColumnWidth: Dp,
    gap: Dp,
    maxColumns: Int,
): Int {
    if (!availableWidth.value.isFinite() || availableWidth.value <= 0f) return 1

    val denominator = minColumnWidth.value + gap.value
    if (!denominator.isFinite() || denominator <= 0f) return 1

    val columnCount = ((availableWidth.value + gap.value) / denominator).toInt()
    return columnCount.coerceIn(1, maxColumns.coerceAtLeast(1))
}
