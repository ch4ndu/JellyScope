// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.preview

import androidx.compose.runtime.Composable
import com.jellyscope.ui.screen.person.PersonContent
import com.jellyscope.ui.screen.person.PersonHeaderUi
import com.jellyscope.ui.screen.person.PersonUiState

@JellyScopeScreenPreviews
@Composable
private fun PersonContentPreview() {
    JellyScopePreviewSurface {
        PersonContent(
            session = PreviewFixtures.session,
            state =
                PersonUiState.Content(
                    movies = PreviewFixtures.mediaCards.take(3),
                    series = PreviewFixtures.mediaCards.drop(2).take(2),
                    header =
                        PersonHeaderUi(
                            id = "person-1",
                            name = "Avery Stone",
                            overview =
                                "A performer and filmmaker whose work spans quiet science fiction, " +
                                    "ensemble drama, and independent thrillers.",
                            imageUrl = null,
                            backdropUrl = null,
                        ),
                    totalCount = 12,
                    hasMore = true,
                ),
            onBack = {},
            onRetry = {},
            onLoadMore = {},
            onItemSelected = {},
            onPlayItem = { _, _ -> },
        )
    }
}
