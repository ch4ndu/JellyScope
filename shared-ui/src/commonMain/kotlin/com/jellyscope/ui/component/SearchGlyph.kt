// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.jellyscope.ui.theme.Dimensions

@Composable
fun SearchGlyph(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface

    Canvas(modifier = modifier) {
        val strokeWidth = Dimensions.searchIconStroke.toPx()
        val radius = size.minDimension * 0.28f
        val center = Offset(size.width * 0.43f, size.height * 0.43f)
        drawCircle(
            color = color,
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth),
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.62f, size.height * 0.62f),
            end = Offset(size.width * 0.82f, size.height * 0.82f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun ClearGlyph(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface

    Canvas(modifier = modifier) {
        val strokeWidth = Dimensions.searchIconStroke.toPx()
        drawLine(
            color = color,
            start = Offset(size.width * 0.3f, size.height * 0.3f),
            end = Offset(size.width * 0.7f, size.height * 0.7f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.7f, size.height * 0.3f),
            end = Offset(size.width * 0.3f, size.height * 0.7f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}
