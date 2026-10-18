// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvCompletionReadinessTest {
    @Test
    fun prepareGenerationCannotBeObservedBeforeReplacementEntryBinding() {
        val readiness = MpvCompletionReadiness()
        readiness.beginPrepare(generation = 1L)

        assertEquals(null, readiness.observeEof(generation = 1L, eofReached = false))

        readiness.bindPlaylistEntry(generation = 1L, playlistEntryId = null)
        assertTrue(
            requireNotNull(readiness.observeEof(generation = 1L, eofReached = false))
                .unresolvedEntryFallbackArmed,
        )
    }

    @Test
    fun staleEventsCannotArmAReplacementGeneration() {
        val readiness = MpvCompletionReadiness()
        readiness.beginPrepare(generation = 2L)
        readiness.bindPlaylistEntry(generation = 2L, playlistEntryId = 22L)

        readiness.onStartFile(generation = 2L, playlistEntryId = 11L)
        readiness.onFileLoaded(generation = 2L, currentPlaylistEntryId = 22L)

        assertFalse(readiness.isArmed(generation = 2L))
        assertFalse(readiness.observeEof(generation = 2L, eofReached = true)?.completed == true)

        readiness.onStartFile(generation = 2L, playlistEntryId = 22L)
        readiness.onFileLoaded(generation = 2L, currentPlaylistEntryId = 22L)

        assertTrue(readiness.isArmed(generation = 2L))
        assertTrue(readiness.observeEof(generation = 2L, eofReached = true)?.completed == true)
    }

    @Test
    fun unresolvedPlaylistEntryArmsOnlyAfterNonEofObservation() {
        val readiness = MpvCompletionReadiness()
        readiness.beginPrepare(generation = 3L)
        readiness.bindPlaylistEntry(generation = 3L, playlistEntryId = null)

        assertFalse(readiness.observeEof(generation = 3L, eofReached = true)?.completed == true)
        val clearedLatch = requireNotNull(readiness.observeEof(generation = 3L, eofReached = false))
        assertTrue(clearedLatch.unresolvedEntryFallbackArmed)
        assertFalse(clearedLatch.completed)
        assertTrue(readiness.observeEof(generation = 3L, eofReached = true)?.completed == true)
    }

    @Test
    fun unresolvedPlaylistEntryRequiresExplicitFalseAcrossTrueNullTrue() {
        val readiness = MpvCompletionReadiness()
        readiness.beginPrepare(generation = 4L)
        readiness.bindPlaylistEntry(generation = 4L, playlistEntryId = null)

        val firstTrue = requireNotNull(readiness.observeEof(generation = 4L, eofReached = true))
        val unavailable = requireNotNull(readiness.observeEof(generation = 4L, eofReached = null))
        val secondTrue = requireNotNull(readiness.observeEof(generation = 4L, eofReached = true))

        assertFalse(firstTrue.completed)
        assertFalse(unavailable.completed)
        assertFalse(secondTrue.completed)
        assertFalse(readiness.isArmed(generation = 4L))

        val explicitFalse = requireNotNull(readiness.observeEof(generation = 4L, eofReached = false))
        assertTrue(explicitFalse.unresolvedEntryFallbackArmed)
        assertTrue(readiness.isArmed(generation = 4L))
        assertTrue(readiness.observeEof(generation = 4L, eofReached = true)?.completed == true)
    }

    @Test
    fun supersessionAndInvalidationDisarmOlderCompletion() {
        val readiness = MpvCompletionReadiness()
        readiness.beginPrepare(generation = 5L)
        readiness.bindPlaylistEntry(generation = 5L, playlistEntryId = 44L)
        readiness.onStartFile(generation = 5L, playlistEntryId = 44L)
        readiness.onFileLoaded(generation = 5L, currentPlaylistEntryId = 44L)
        assertTrue(readiness.isArmed(generation = 5L))

        val staleObservation = requireNotNull(readiness.observeEof(generation = 5L, eofReached = true))
        readiness.beginPrepare(generation = 6L)
        assertFalse(readiness.isCurrent(staleObservation))
        assertEquals(null, readiness.observeEof(generation = 5L, eofReached = true))
        assertFalse(readiness.isArmed(generation = 6L))

        readiness.invalidate()
        assertEquals(null, readiness.observeEof(generation = 6L, eofReached = true))
    }
}
