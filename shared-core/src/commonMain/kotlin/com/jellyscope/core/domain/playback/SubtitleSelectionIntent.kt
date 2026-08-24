// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

data class SubtitleSelectionKey(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val mediaSourceId: String,
)

sealed interface SubtitleSelectionIntent {
    data object Unspecified : SubtitleSelectionIntent

    data object Off : SubtitleSelectionIntent

    data class Track(
        val streamIndex: Int,
    ) : SubtitleSelectionIntent {
        init {
            require(streamIndex >= 0) { "Subtitle stream index must be non-negative." }
        }
    }

    data class LocalAsset(
        val assetId: String,
    ) : SubtitleSelectionIntent {
        init {
            require(assetId.isNotBlank()) { "Local subtitle asset id must not be blank." }
        }
    }

    fun wireIndexOrNull(): Int? =
        when (this) {
            Unspecified -> null
            Off -> OFF_SUBTITLE_STREAM_INDEX
            is Track -> streamIndex
            is LocalAsset -> OFF_SUBTITLE_STREAM_INDEX
        }

    fun selectedIndexOrNull(): Int? = (this as? Track)?.streamIndex

    companion object {
        fun fromWireIndex(index: Int?): SubtitleSelectionIntent =
            when {
                index == null -> Unspecified
                index == OFF_SUBTITLE_STREAM_INDEX -> Off
                index >= 0 -> Track(index)
                else -> Unspecified
            }
    }
}

const val OFF_SUBTITLE_STREAM_INDEX = -1

fun Int?.toExplicitSubtitleSelectionIntent(): SubtitleSelectionIntent =
    this?.takeIf { index -> index >= 0 }?.let(SubtitleSelectionIntent::Track)
        ?: SubtitleSelectionIntent.Off
