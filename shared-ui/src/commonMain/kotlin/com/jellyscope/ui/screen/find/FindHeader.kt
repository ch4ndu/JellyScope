// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.find

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import com.jellyscope.core.domain.model.RuntimeBucket
import com.jellyscope.core.domain.model.WatchedFilter
import com.jellyscope.ui.component.ClearGlyph
import com.jellyscope.ui.component.SearchGlyph
import com.jellyscope.ui.component.TooltipIconButton
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.find_anything
import com.jellyscope.ui.generated.resources.find_chip_cd
import com.jellyscope.ui.generated.resources.find_clear_cd
import com.jellyscope.ui.generated.resources.find_genre_action
import com.jellyscope.ui.generated.resources.find_genre_comedy
import com.jellyscope.ui.generated.resources.find_genre_drama
import com.jellyscope.ui.generated.resources.find_genre_family
import com.jellyscope.ui.generated.resources.find_genre_thriller
import com.jellyscope.ui.generated.resources.find_genres_title
import com.jellyscope.ui.generated.resources.find_questionnaire_title
import com.jellyscope.ui.generated.resources.find_results_all
import com.jellyscope.ui.generated.resources.find_results_episodes
import com.jellyscope.ui.generated.resources.find_results_movies
import com.jellyscope.ui.generated.resources.find_results_shows
import com.jellyscope.ui.generated.resources.find_runtime_title
import com.jellyscope.ui.generated.resources.find_runtime_under_120
import com.jellyscope.ui.generated.resources.find_runtime_under_30
import com.jellyscope.ui.generated.resources.find_runtime_under_60
import com.jellyscope.ui.generated.resources.find_search_cd
import com.jellyscope.ui.generated.resources.find_search_label
import com.jellyscope.ui.generated.resources.find_watched_in_progress
import com.jellyscope.ui.generated.resources.find_watched_title
import com.jellyscope.ui.generated.resources.find_watched_unwatched
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun FindSearchHeader(
    state: FindUiState,
    onQueryTextChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSearchSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        val clearContentDescription = stringResource(Res.string.find_clear_cd)
        val searchContentDescription = stringResource(Res.string.find_search_cd)
        TextField(
            value = state.queryText,
            onValueChange = onQueryTextChanged,
            modifier =
                Modifier
                    .widthIn(max = Dimensions.formControlMaxWidth)
                    .fillMaxWidth()
                    .semantics { contentDescription = searchContentDescription },
            label = { Text(stringResource(Res.string.find_search_label)) },
            leadingIcon = {
                SearchGlyph(modifier = Modifier.size(Dimensions.searchIconSize))
            },
            trailingIcon = {
                if (state.queryText.isNotBlank()) {
                    TooltipIconButton(
                        label = clearContentDescription,
                        onClick = onClearQuery,
                        modifier = Modifier.size(Dimensions.minTouchTarget),
                    ) {
                        ClearGlyph(
                            modifier = Modifier.size(Dimensions.searchIconSize),
                        )
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
            shape = MaterialTheme.shapes.medium,
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuestionnaireCard(
    state: FindUiState,
    onGenreToggled: (String) -> Unit,
    onRuntimeSelected: (RuntimeBucket) -> Unit,
    onWatchedFilterSelected: (WatchedFilter) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
    ) {
        Column(
            modifier = Modifier.padding(Dimensions.formSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
        ) {
            Text(
                text = stringResource(Res.string.find_questionnaire_title),
                style = MaterialTheme.typography.titleMedium,
            )
            ChipGroup(title = stringResource(Res.string.find_genres_title)) {
                moodGenres.forEach { genre ->
                    FindChip(
                        label = stringResource(genre.label),
                        selected = genre.name in state.selectedGenreNames,
                        onClick = { onGenreToggled(genre.name) },
                    )
                }
            }
            ChipGroup(title = stringResource(Res.string.find_runtime_title)) {
                runtimeChoices.forEach { choice ->
                    FindChip(
                        label = stringResource(choice.label),
                        selected = state.runtimeBucket == choice.bucket,
                        onClick = { onRuntimeSelected(choice.bucket) },
                    )
                }
            }
            ChipGroup(title = stringResource(Res.string.find_watched_title)) {
                watchedChoices.forEach { choice ->
                    FindChip(
                        label = stringResource(choice.label),
                        selected = state.watchedFilter == choice.filter,
                        onClick = { onWatchedFilterSelected(choice.filter) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
        ) {
            content()
        }
    }
}

@Composable
internal fun FindChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentDescription = stringResource(Res.string.find_chip_cd, label)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier =
            modifier
                .heightIn(min = Dimensions.minTouchTarget)
                .semantics { this.contentDescription = contentDescription },
    )
}

private val moodGenres: List<MoodGenre> =
    listOf(
        MoodGenre("Comedy", Res.string.find_genre_comedy),
        MoodGenre("Drama", Res.string.find_genre_drama),
        MoodGenre("Action", Res.string.find_genre_action),
        MoodGenre("Thriller", Res.string.find_genre_thriller),
        MoodGenre("Family", Res.string.find_genre_family),
    )

private data class MoodGenre(
    val name: String,
    val label: StringResource,
)

private val runtimeChoices: List<RuntimeChoice> =
    listOf(
        RuntimeChoice(RuntimeBucket.Under30, Res.string.find_runtime_under_30),
        RuntimeChoice(RuntimeBucket.Under60, Res.string.find_runtime_under_60),
        RuntimeChoice(RuntimeBucket.Under120, Res.string.find_runtime_under_120),
        RuntimeChoice(RuntimeBucket.Any, Res.string.find_anything),
    )

private data class RuntimeChoice(
    val bucket: RuntimeBucket,
    val label: StringResource,
)

private val watchedChoices: List<WatchedChoice> =
    listOf(
        WatchedChoice(WatchedFilter.Any, Res.string.find_anything),
        WatchedChoice(WatchedFilter.Unwatched, Res.string.find_watched_unwatched),
        WatchedChoice(WatchedFilter.InProgress, Res.string.find_watched_in_progress),
    )

private data class WatchedChoice(
    val filter: WatchedFilter,
    val label: StringResource,
)

internal val resultTabChoices: List<ResultTabChoice> =
    listOf(
        ResultTabChoice(FindResultTab.All, Res.string.find_results_all),
        ResultTabChoice(FindResultTab.Movies, Res.string.find_results_movies),
        ResultTabChoice(FindResultTab.Shows, Res.string.find_results_shows),
        ResultTabChoice(FindResultTab.Episodes, Res.string.find_results_episodes),
    )

internal data class ResultTabChoice(
    val tab: FindResultTab,
    val label: StringResource,
)
