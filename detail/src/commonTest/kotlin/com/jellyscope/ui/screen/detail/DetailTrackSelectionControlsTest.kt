// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import com.jellyscope.core.domain.playback.AudioTrackOption
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleTrackOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailTrackSelectionControlsTest {
    @Test
    fun versionPickerHidesUntilTwoValidDistinctSourcesRemain() {
        val policy =
            mediaVersionPickerPolicy(
                versions =
                    listOf(
                        mediaVersion(id = "", name = "Invalid"),
                        mediaVersion(id = "source-1", name = "Version 1"),
                        mediaVersion(id = "source-1", name = "Duplicate"),
                    ),
                selectedMediaSourceId = "missing",
            )

        assertFalse(policy.visible)
        assertEquals(listOf("source-1"), policy.versions.map(MediaVersionUi::id))
        assertEquals("Version 1", policy.selectedVersion?.name)
        assertEquals(0, policy.selectedIndex)
        assertEquals(MediaVersionPickerRestoreTarget.Play, policy.restoreTarget)
    }

    @Test
    fun versionPickerSelectsCurrentRowAndRestoresToSurvivingVersionAction() {
        val versions =
            listOf(
                mediaVersion(id = "source-1", name = "Server label"),
                mediaVersion(id = "source-2", name = "Version 2"),
            )

        val policy = mediaVersionPickerPolicy(versions, selectedMediaSourceId = "source-2")

        assertTrue(policy.visible)
        assertEquals(1, policy.selectedIndex)
        assertEquals("source-2", policy.selectedVersion?.id)
        assertEquals("Version 2", policy.selectedVersion?.name)
        assertEquals(MediaVersionPickerRestoreTarget.Version, policy.restoreTarget)
        assertFalse(
            shouldDismissVersionPicker(
                openedMediaSourceId = "source-2",
                versions = versions,
                selectedMediaSourceId = "source-2",
            ),
        )
    }

    @Test
    fun versionPickerKeepsTheSelectedServerOrderIndexBeyondTheInitialViewport() {
        val versions =
            (1..12).map { number ->
                mediaVersion(id = "source-$number", name = "Version $number")
            }

        val policy = mediaVersionPickerPolicy(versions, selectedMediaSourceId = "source-10")

        assertEquals(9, policy.selectedIndex)
        assertEquals("source-10", policy.selectedVersion?.id)
    }

    @Test
    fun selectedVersionRemovalDismissesAndFallsBackToPlayWhenOneChoiceSurvives() {
        val survivingVersions = listOf(mediaVersion(id = "source-1", name = "Version 1"))

        assertTrue(
            shouldDismissVersionPicker(
                openedMediaSourceId = "source-2",
                versions = survivingVersions,
                selectedMediaSourceId = "source-1",
            ),
        )
        assertEquals(
            MediaVersionPickerRestoreTarget.Play,
            mediaVersionPickerPolicy(survivingVersions, selectedMediaSourceId = "source-1").restoreTarget,
        )
    }

    @Test
    fun untouchedResolvedDefaultProducesAutomaticRoute() {
        val selection = trackSelection(sourceId = "source-1", defaultAudioIndex = 1)
        val state =
            DetailTrackSelectionState(
                audioStreamIndex = selection.defaultAudioStreamIndex,
                subtitleStreamIndex = null,
                mediaSourceId = selection.mediaSourceId,
            )

        assertNull(state.audioStreamIndexForPlay(selection))
        assertFalse(state.audioExplicitlySelected)

        state.reconcile(selection)

        assertEquals(SubtitleSelectionIntent.Track(2), state.subtitleSelectionForPlay(selection))
    }

    @Test
    fun selectingAlreadyResolvedDefaultProducesExplicitRoute() {
        val selection = trackSelection(sourceId = "source-1", defaultAudioIndex = 1)
        val state =
            DetailTrackSelectionState(
                audioStreamIndex = selection.defaultAudioStreamIndex,
                subtitleStreamIndex = null,
                mediaSourceId = selection.mediaSourceId,
            )

        state.selectAudio(1)

        assertEquals(1, state.audioStreamIndexForPlay(selection))
        assertTrue(state.audioExplicitlySelected)
    }

    @Test
    fun explicitNonDefaultSelectionSurvivesReconciliation() {
        val selection = trackSelection(sourceId = "source-1", defaultAudioIndex = 1)
        val state =
            DetailTrackSelectionState(
                audioStreamIndex = selection.defaultAudioStreamIndex,
                subtitleStreamIndex = null,
                mediaSourceId = selection.mediaSourceId,
            )

        state.selectAudio(3)
        state.reconcile(selection)

        assertEquals(3, state.audioStreamIndexForPlay(selection))
        assertTrue(state.audioExplicitlySelected)
    }

    @Test
    fun sourceChangeClearsExplicitAudioProvenance() {
        val first = trackSelection(sourceId = "source-1", defaultAudioIndex = 1)
        val replacement = trackSelection(sourceId = "source-2", defaultAudioIndex = 3)
        val state =
            DetailTrackSelectionState(
                audioStreamIndex = first.defaultAudioStreamIndex,
                subtitleStreamIndex = null,
                mediaSourceId = first.mediaSourceId,
            )
        state.selectAudio(1)
        state.selectSubtitle(null)

        state.reconcile(replacement)

        assertEquals(3, state.selectedAudioStreamIndex)
        assertNull(state.audioStreamIndexForPlay(replacement))
        assertFalse(state.audioExplicitlySelected)
        assertEquals(2, state.selectedSubtitleStreamIndex)
        assertFalse(state.subtitleExplicitlySelected)
    }

    @Test
    fun subtitleSelectionMapperAppliesOffAndEmbeddedTrackToStateAndAction() {
        val selection = trackSelection(sourceId = "source-1", defaultAudioIndex = 1)
        val state =
            DetailTrackSelectionState(
                audioStreamIndex = selection.defaultAudioStreamIndex,
                subtitleStreamIndex = selection.defaultSubtitleStreamIndex,
                mediaSourceId = selection.mediaSourceId,
            )
        val selectedStreamIndexes = mutableListOf<Int?>()
        val actions =
            DetailSubtitlePickerActions(
                onSelectSubtitle = { streamIndex -> selectedStreamIndexes += streamIndex },
            )

        applySubtitleSelection(state, streamIndex = null, subtitleActions = actions)
        state.reconcile(selection)
        applySubtitleSelection(state, streamIndex = 2, subtitleActions = actions)
        state.reconcile(selection)

        assertEquals(
            listOf(null, 2),
            selectedStreamIndexes,
        )
        assertTrue(state.subtitleExplicitlySelected)
        assertEquals(2, state.selectedSubtitleStreamIndex)
        assertEquals(SubtitleSelectionIntent.Track(2), state.subtitleSelectionForPlay(selection))
    }

    @Test
    fun explicitOffSurvivesReconciliationAndPlaySelection() {
        val selection = trackSelection(sourceId = "source-1", defaultAudioIndex = 1)
        val state =
            DetailTrackSelectionState(
                audioStreamIndex = selection.defaultAudioStreamIndex,
                subtitleStreamIndex = selection.defaultSubtitleStreamIndex,
                mediaSourceId = selection.mediaSourceId,
            )

        state.selectSubtitle(null)
        state.reconcile(selection)

        assertNull(state.selectedSubtitleStreamIndex)
        assertTrue(state.subtitleExplicitlySelected)
        assertEquals(SubtitleSelectionIntent.Off, state.subtitleSelectionForPlay(selection))
    }

    @Test
    fun untouchedSubtitleSelectionUsesDefaultAndMissingExplicitTrackFallsBack() {
        val selection = trackSelection(sourceId = "source-1", defaultAudioIndex = 1)
        val state =
            DetailTrackSelectionState(
                audioStreamIndex = selection.defaultAudioStreamIndex,
                subtitleStreamIndex = null,
                mediaSourceId = selection.mediaSourceId,
            )

        state.reconcile(selection)

        assertEquals(selection.defaultSubtitleStreamIndex, state.selectedSubtitleStreamIndex)
        assertFalse(state.subtitleExplicitlySelected)
        assertEquals(SubtitleSelectionIntent.Track(2), state.subtitleSelectionForPlay(selection))

        state.selectSubtitle(2)
        state.reconcile(selection.copy(subtitleOptions = emptyList(), defaultSubtitleStreamIndex = null))

        assertNull(state.selectedSubtitleStreamIndex)
        assertFalse(state.subtitleExplicitlySelected)
        assertEquals(SubtitleSelectionIntent.Unspecified, state.subtitleSelectionForPlay(selection))
    }

    @Test
    fun localSubtitleSelectionClearsProvenanceBeforeSelectionIsDeleted() {
        val selection = trackSelection(sourceId = "source-1", defaultAudioIndex = 1)
        val selectionAfterDelete =
            selection.copy(
                initialSubtitleSelection = SubtitleSelectionIntent.Unspecified,
                defaultLocalSubtitleAssetId = null,
            )
        val state =
            DetailTrackSelectionState(
                audioStreamIndex = selection.defaultAudioStreamIndex,
                subtitleStreamIndex = selection.defaultSubtitleStreamIndex,
                mediaSourceId = selection.mediaSourceId,
            )
        val selectedAssetIds = mutableListOf<String>()
        val actions =
            DetailSubtitlePickerActions(
                onSelectLocalAsset = { assetId -> selectedAssetIds += assetId },
            )

        state.selectSubtitle(null)
        applyLocalSubtitleSelection(state, assetId = "asset-off", subtitleActions = actions)
        state.reconcile(selectionAfterDelete)
        assertEquals(SubtitleSelectionIntent.Track(2), state.subtitleSelectionForPlay(selectionAfterDelete))
        assertFalse(state.subtitleExplicitlySelected)

        state.selectSubtitle(2)
        applyLocalSubtitleSelection(state, assetId = "asset-track", subtitleActions = actions)
        state.reconcile(selectionAfterDelete)
        assertEquals(SubtitleSelectionIntent.Track(2), state.subtitleSelectionForPlay(selectionAfterDelete))
        assertFalse(state.subtitleExplicitlySelected)
        assertEquals(listOf("asset-off", "asset-track"), selectedAssetIds)
    }
}

private fun mediaVersion(
    id: String,
    name: String,
): MediaVersionUi = MediaVersionUi(id = id, name = name)

private fun trackSelection(
    sourceId: String,
    defaultAudioIndex: Int,
): DetailTrackSelectionUi =
    DetailTrackSelectionUi(
        mediaSourceId = sourceId,
        audioOptions =
            listOf(
                AudioTrackOption(
                    streamIndex = 1,
                    ordinal = 0,
                    displayName = "English",
                    language = "eng",
                    isDefault = defaultAudioIndex == 1,
                ),
                AudioTrackOption(
                    streamIndex = 3,
                    ordinal = 1,
                    displayName = "Spanish",
                    language = "spa",
                    isDefault = defaultAudioIndex == 3,
                ),
            ),
        defaultAudioStreamIndex = defaultAudioIndex,
        subtitleOptions =
            listOf(
                SubtitleTrackOption(
                    streamIndex = 2,
                    ordinal = 0,
                    displayName = "English",
                    language = "eng",
                    isDefault = true,
                    isExternal = true,
                ),
            ),
        defaultSubtitleStreamIndex = 2,
    )
