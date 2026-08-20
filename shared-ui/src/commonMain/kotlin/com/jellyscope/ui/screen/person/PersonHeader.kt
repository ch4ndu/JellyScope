// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.person

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.adaptive.LocalWindowWidthTier
import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.ImageDecode
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_person_cd
import com.jellyscope.ui.generated.resources.person_backdrop_cd
import com.jellyscope.ui.generated.resources.person_title
import com.jellyscope.ui.generated.resources.tv_overview_fallback
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

internal enum class PersonHeaderLayout {
    StackedBackdrop,
    Portrait,
}

internal fun WindowWidthTier.personHeaderLayout(): PersonHeaderLayout =
    when (this) {
        WindowWidthTier.Compact -> PersonHeaderLayout.StackedBackdrop
        WindowWidthTier.Medium,
        WindowWidthTier.Expanded,
        WindowWidthTier.XLarge,
        -> PersonHeaderLayout.Portrait
    }

@Composable
internal fun PersonHeader(
    session: Session,
    header: PersonHeaderUi?,
) {
    when (LocalWindowWidthTier.current.personHeaderLayout()) {
        PersonHeaderLayout.StackedBackdrop -> CompactPersonHeader(session, header)
        PersonHeaderLayout.Portrait -> PortraitPersonHeader(session, header)
    }
}

@Composable
private fun CompactPersonHeader(
    session: Session,
    header: PersonHeaderUi?,
) {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
    ) {
        PersonBackdrop(
            session = session,
            header = header,
        )
        Column(
            modifier =
                Modifier.padding(
                    start = horizontalContentPadding.start,
                    end = horizontalContentPadding.end,
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
        ) {
            Text(
                text = header?.name ?: stringResource(Res.string.person_title),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            header?.overview?.let { overview ->
                Text(
                    text = overview,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun PortraitPersonHeader(
    session: Session,
    header: PersonHeaderUi?,
) {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()
    val title = header?.name ?: stringResource(Res.string.person_title)
    val contentDescription = stringResource(Res.string.detail_person_cd, title)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = horizontalContentPadding.start,
                    end = horizontalContentPadding.end,
                ),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier =
                Modifier
                    .width(Dimensions.detailPosterWidth.tileScaled())
                    .aspectRatio(Dimensions.posterCardAspectRatio)
                    .semantics { this.contentDescription = contentDescription },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                header?.imageUrl?.let { imageUrl ->
                    AsyncImage(
                        model = authenticatedImageRequest(imageUrl, session),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } ?: Text(
                    text = title.take(1),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = header?.overview ?: stringResource(Res.string.tv_overview_fallback),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PersonBackdrop(
    session: Session,
    header: PersonHeaderUi?,
) {
    val title = header?.name ?: stringResource(Res.string.person_title)
    val contentDescription = stringResource(Res.string.person_backdrop_cd, title)
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(Dimensions.detailBackdropAspectRatio)
                .semantics { this.contentDescription = contentDescription },
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box {
            val backdropUrl = header?.backdropUrl ?: header?.imageUrl
            if (backdropUrl != null) {
                AsyncImage(
                    model = authenticatedImageRequest(backdropUrl, session, ImageDecode.Backdrop),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(Dimensions.screenPadding)
                        .background(
                            Brush.verticalGradient(
                                colors =
                                    listOf(
                                        MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                        MaterialTheme.colorScheme.background,
                                    ),
                            ),
                        ),
            )
        }
    }
}
