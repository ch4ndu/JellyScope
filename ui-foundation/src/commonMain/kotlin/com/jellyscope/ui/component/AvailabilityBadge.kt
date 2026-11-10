// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.jellyscope.core.domain.model.SeerrAvailability
import com.jellyscope.ui.theme.AvailabilityColors
import com.jellyscope.ui.theme.Dimensions

/** Request status never implies that a complete library copy exists. */
@Composable
fun AvailabilityBadge(
    qualityLabel: String,
    availability: SeerrAvailability,
    description: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .background(AvailabilityColors.background, MaterialTheme.shapes.small)
                .padding(horizontal = Dimensions.badgeHorizontalPadding, vertical = Dimensions.badgeVerticalPadding)
                .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(Dimensions.availabilityBadgeGlyphGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            qualityLabel,
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.75f,
                    lineHeight = MaterialTheme.typography.labelSmall.lineHeight * 0.75f,
                ),
            fontWeight = FontWeight.Bold,
            color = AvailabilityColors.label,
        )
        AvailabilityGlyph(availability)
    }
}

@Composable
private fun AvailabilityGlyph(availability: SeerrAvailability) {
    Box(Modifier.size(Dimensions.availabilityBadgeGlyphSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val radius = size.minDimension / 2f
            val stroke = Dimensions.availabilityBadgeGlyphStroke.toPx()
            val color =
                when (availability) {
                    SeerrAvailability.Available -> AvailabilityColors.available
                    SeerrAvailability.PartiallyAvailable -> AvailabilityColors.partial
                    SeerrAvailability.Unrecognized -> AvailabilityColors.unknown
                    else -> AvailabilityColors.absent
                }
            when (availability) {
                SeerrAvailability.Available -> drawCircle(color)
                SeerrAvailability.PartiallyAvailable -> {
                    drawArc(color, 90f, 180f, useCenter = true)
                    drawCircle(color, radius - stroke / 2f, style = Stroke(stroke))
                }
                else -> drawCircle(color, radius - stroke / 2f, center = Offset(radius, radius), style = Stroke(stroke))
            }
        }
        if (availability == SeerrAvailability.Unrecognized) {
            Text(
                "?",
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.75f,
                        lineHeight = MaterialTheme.typography.labelSmall.fontSize * 0.75f,
                    ),
                color = AvailabilityColors.unknown,
                maxLines = 1,
            )
        }
    }
}
