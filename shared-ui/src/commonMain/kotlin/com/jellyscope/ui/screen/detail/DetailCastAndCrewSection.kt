// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_cast_crew
import com.jellyscope.ui.generated.resources.detail_person_cd
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

// Detail and Series intentionally retain their existing card sizing contracts.
internal enum class CastAndCrewCardSizing {
    DetailPortrait,
    SeriesPoster,
}

@Composable
internal fun CastAndCrewSection(
    session: Session,
    people: List<CastAndCrewUi>,
    onPersonSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    cardSizing: CastAndCrewCardSizing = CastAndCrewCardSizing.DetailPortrait,
) {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()
    val rowListState = rememberLazyListState()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        SectionTitle(
            text = stringResource(Res.string.detail_cast_crew),
            modifier =
                Modifier.padding(
                    start = horizontalContentPadding.start,
                    end = horizontalContentPadding.end,
                ),
        )
        LazyRow(
            state = rowListState,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .desktopScrollInput(rowListState, DesktopScrollOrientation.Horizontal),
            contentPadding = horizontalContentPadding.asPaddingValues(),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
        ) {
            items(
                items = people,
                key = { person -> person.id },
            ) { person ->
                CastAndCrewCard(
                    session = session,
                    person = person,
                    cardSizing = cardSizing,
                    onClick = { onPersonSelected(person.id) },
                )
            }
        }
    }
}

@Composable
private fun CastAndCrewCard(
    session: Session,
    person: CastAndCrewUi,
    cardSizing: CastAndCrewCardSizing,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentDescription = stringResource(Res.string.detail_person_cd, person.name)
    Column(
        modifier =
            modifier
                .width(Dimensions.personCardWidth.tileScaled())
                .heightIn(min = Dimensions.minTouchTarget)
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                }.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(
                        when (cardSizing) {
                            CastAndCrewCardSizing.DetailPortrait -> Modifier.height(Dimensions.personCardImageHeight.tileScaled())
                            CastAndCrewCardSizing.SeriesPoster -> Modifier.aspectRatio(Dimensions.posterCardAspectRatio)
                        },
                    ),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            person.imageUrl?.let { imageUrl ->
                AsyncImage(
                    model = authenticatedImageRequest(imageUrl, session),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Text(
            text = person.name,
            style = MaterialTheme.typography.bodySmall,
            minLines =
                when (cardSizing) {
                    CastAndCrewCardSizing.DetailPortrait -> 2
                    CastAndCrewCardSizing.SeriesPoster -> 1
                },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        person.roleText()?.let { role ->
            Text(
                text = role,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
