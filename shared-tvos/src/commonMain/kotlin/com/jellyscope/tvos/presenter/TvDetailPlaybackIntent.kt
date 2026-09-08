// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

internal data class TvDetailPlaybackIntent(
    val itemId: String? = null,
    val mediaSourceId: String? = null,
    val explicitAudioStreamIndex: Int? = null,
    val explicitSubtitleMode: TvPlaybackSubtitleMode? = null,
    val explicitSubtitleStreamIndex: Int? = null,
) {
    fun retainFor(
        loadedItemId: String,
        selectedSourceId: String?,
    ): TvDetailPlaybackIntent =
        if (itemId == loadedItemId && mediaSourceId == selectedSourceId) {
            this
        } else {
            TvDetailPlaybackIntent(itemId = loadedItemId, mediaSourceId = selectedSourceId)
        }

    fun selectVersion(
        loadedItemId: String,
        selectedSourceId: String,
    ): TvDetailPlaybackIntent =
        if (itemId == loadedItemId && mediaSourceId == selectedSourceId) {
            this
        } else {
            TvDetailPlaybackIntent(itemId = loadedItemId, mediaSourceId = selectedSourceId)
        }

    fun selectAudio(streamIndex: Int): TvDetailPlaybackIntent = copy(explicitAudioStreamIndex = streamIndex)

    fun selectSubtitle(streamIndex: Int?): TvDetailPlaybackIntent =
        copy(
            explicitSubtitleMode =
                if (streamIndex == null) {
                    TvPlaybackSubtitleMode.Off
                } else {
                    TvPlaybackSubtitleMode.Track
                },
            explicitSubtitleStreamIndex = streamIndex,
        )
}
