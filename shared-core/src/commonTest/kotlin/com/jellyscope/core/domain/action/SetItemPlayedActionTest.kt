// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SetItemPlayedActionTest {
    @Test
    fun delegatesPlayedSuccess() =
        runTest {
            val repository = MutatingMediaRepositoryFake(playedResult = Result.success(Unit))
            val result = SetItemPlayedAction(repository)(itemId = "movie-1", played = true)

            assertEquals(listOf("movie-1" to true), repository.playedCalls)
            assertEquals(Unit, result.getOrThrow())
        }

    @Test
    fun delegatesPlayedFailure() =
        runTest {
            val failure = IllegalStateException("failed")
            val repository = MutatingMediaRepositoryFake(playedResult = Result.failure(failure))
            val result = SetItemPlayedAction(repository)(itemId = "movie-1", played = false)

            assertEquals(listOf("movie-1" to false), repository.playedCalls)
            assertSame(failure, result.exceptionOrNull())
        }
}
