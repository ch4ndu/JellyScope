// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import coil3.Image
import coil3.compose.AsyncImage
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.ImageDecode
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.theme.Dimensions

@Composable
internal fun Backdrop(
    session: Session,
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onImageLoaded: (Image) -> Unit = {},
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(Dimensions.detailBackdropAspectRatio)
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box {
            if (imageUrl != null) {
                AsyncImage(
                    model = authenticatedImageRequest(imageUrl, session, ImageDecode.Backdrop),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onSuccess = { state -> onImageLoaded(state.result.image) },
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

@Composable
internal fun Poster(
    session: Session,
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .width(Dimensions.detailPosterWidth.tileScaled())
                .aspectRatio(Dimensions.posterCardAspectRatio)
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (imageUrl != null) {
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
}

@Composable
internal fun TextSection(
    title: String,
    body: String,
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
        SectionTitle(title)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium,
    )
}

internal fun CastAndCrewUi.roleText(): String? = role ?: fallbackRoleType?.name
