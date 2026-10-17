// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

const val PLAYBACK_SELECTION_SCHEMA_VERSION = 1

data class PlaybackSelectionKey(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val mediaSourceId: String,
) {
    init {
        require(serverId.isNotBlank()) { "serverId must not be blank." }
        require(userId.isNotBlank()) { "userId must not be blank." }
        require(itemId.isNotBlank()) { "itemId must not be blank." }
        require(mediaSourceId.isNotBlank()) { "mediaSourceId must not be blank." }
    }
}

data class PlaybackSelection(
    val audioStreamIndex: Int? = null,
    val schemaVersion: Int = PLAYBACK_SELECTION_SCHEMA_VERSION,
) {
    fun normalized(): PlaybackSelection =
        copy(
            audioStreamIndex = audioStreamIndex?.takeIf { index -> index >= 0 },
            schemaVersion = PLAYBACK_SELECTION_SCHEMA_VERSION,
        )
}
