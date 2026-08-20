// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.find

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import com.jellyscope.ui.adaptive.expandIntoHorizontalContentPadding
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.find_error
import com.jellyscope.ui.generated.resources.find_loading
import com.jellyscope.ui.generated.resources.find_person_cd
import com.jellyscope.ui.generated.resources.find_person_suggestions
import com.jellyscope.ui.generated.resources.find_recent_cd
import com.jellyscope.ui.generated.resources.find_recent_clear
import com.jellyscope.ui.generated.resources.find_recent_searches
import com.jellyscope.ui.generated.resources.find_retry
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RecentSearches(
    searches: List<String>,
    onRecentSelected: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.find_recent_searches),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = onClear) {
                Text(text = stringResource(Res.string.find_recent_clear))
            }
        }
        searches.forEach { search ->
            val contentDescription = stringResource(Res.string.find_recent_cd, search)
            ListItem(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimensions.minTouchTarget)
                        .clip(MaterialTheme.shapes.small)
                        .semantics {
                            this.contentDescription = contentDescription
                            role = Role.Button
                        }.clickable { onRecentSelected(search) },
                headlineContent = { Text(text = search) },
                colors =
                    ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    ),
            )
        }
    }
}

@Composable
internal fun PersonSuggestions(
    session: Session,
    people: List<PersonUi>,
    onPersonSelected: (PersonUi) -> Unit,
) {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()
    val rowListState = rememberLazyListState()

    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
        Text(
            text = stringResource(Res.string.find_person_suggestions),
            style = MaterialTheme.typography.titleMedium,
        )
        LazyRow(
            state = rowListState,
            modifier =
                Modifier
                    .expandIntoHorizontalContentPadding(horizontalContentPadding)
                    .fillMaxWidth()
                    .desktopScrollInput(rowListState, DesktopScrollOrientation.Horizontal),
            contentPadding = horizontalContentPadding.asPaddingValues(),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
        ) {
            items(
                items = people,
                key = { person -> person.id },
            ) { person ->
                PersonSuggestion(
                    session = session,
                    person = person,
                    onClick = { onPersonSelected(person) },
                )
            }
        }
    }
}

@Composable
private fun PersonSuggestion(
    session: Session,
    person: PersonUi,
    onClick: () -> Unit,
) {
    val contentDescription = stringResource(Res.string.find_person_cd, person.name)
    Column(
        modifier =
            Modifier
                .width(Dimensions.personAvatarSize)
                .heightIn(min = Dimensions.minTouchTarget)
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                }.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        Surface(
            modifier = Modifier.size(Dimensions.personAvatarSize),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            val imageUrl = person.imageUrl
            if (imageUrl != null) {
                AsyncImage(
                    model = authenticatedImageRequest(imageUrl, session),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(Dimensions.personAvatarSize)
                            .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = person.name.take(1),
                        modifier = Modifier.padding(Dimensions.contentSpacing),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        Text(
            text = person.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun LoadingRow() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(Dimensions.progressIndicatorSize),
            strokeWidth = Dimensions.progressIndicatorStroke,
        )
        Text(
            text = stringResource(Res.string.find_loading),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun ErrorRow(onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.find_error),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.heightIn(min = Dimensions.minTouchTarget),
        ) {
            Text(stringResource(Res.string.find_retry))
        }
    }
}
