// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_critic_rating
import com.jellyscope.ui.generated.resources.detail_external_imdb
import com.jellyscope.ui.generated.resources.detail_external_imdb_cd
import com.jellyscope.ui.generated.resources.detail_external_tmdb
import com.jellyscope.ui.generated.resources.detail_external_tmdb_cd
import com.jellyscope.ui.generated.resources.detail_genres
import com.jellyscope.ui.generated.resources.detail_metadata
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DetailMetadataSection(
    detail: DetailUi,
    modifier: Modifier = Modifier,
) {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    start = horizontalContentPadding.start,
                    end = horizontalContentPadding.end,
                ),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        SectionTitle(stringResource(Res.string.detail_metadata))
        RatingsRow(detail)
        ExternalLinksRow(detail)
        detail.genresLine?.let { genres ->
            Text(
                text = stringResource(Res.string.detail_genres, genres),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        detail.directedByLine?.let { directedBy ->
            Text(
                text = directedBy,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        detail.studioLine?.let { studios ->
            Text(
                text = studios,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun RatingsRow(detail: DetailUi) {
    val ratings = listOfNotNull(detail.officialRating, detail.communityRating)

    if (ratings.isEmpty() && detail.criticRatingText == null) {
        return
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ratings.takeIf { values -> values.isNotEmpty() }?.let { values ->
            Text(
                text = values.joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        detail.criticRatingText?.let { rating ->
            InfoBadge(
                text = stringResource(Res.string.detail_critic_rating, rating),
                accent = true,
            )
        }
    }
}

@Composable
internal fun ExternalLinksRow(
    detail: DetailUi,
    modifier: Modifier = Modifier,
) {
    val imdbUrl = detail.imdbUrl
    val tmdbUrl = detail.tmdbUrl
    if (imdbUrl == null && tmdbUrl == null) {
        return
    }

    val uriHandler = LocalUriHandler.current

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        imdbUrl?.let { url ->
            ExternalLinkChip(
                label = stringResource(Res.string.detail_external_imdb),
                contentDescription = stringResource(Res.string.detail_external_imdb_cd, detail.title),
                onClick = { uriHandler.openUri(url) },
            )
        }
        tmdbUrl?.let { url ->
            ExternalLinkChip(
                label = stringResource(Res.string.detail_external_tmdb),
                contentDescription = stringResource(Res.string.detail_external_tmdb_cd, detail.title),
                onClick = { uriHandler.openUri(url) },
            )
        }
    }
}

@Composable
private fun ExternalLinkChip(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .heightIn(min = Dimensions.minTouchTarget)
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                }.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier =
                Modifier.padding(
                    horizontal = Dimensions.badgeHorizontalPadding,
                    vertical = Dimensions.badgeVerticalPadding,
                ),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.labelGlyphSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimensions.controlButtonIconSize),
            )
        }
    }
}
