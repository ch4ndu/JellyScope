// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SetItemFavoriteActionTest {
    @Test
    fun delegatesFavoriteSuccess() =
        runTest {
            val repository = MutatingMediaRepositoryFake(favoriteResult = Result.success(Unit))
            val result = SetItemFavoriteAction(repository)(itemId = "movie-1", favorite = true)

            assertEquals(listOf("movie-1" to true), repository.favoriteCalls)
            assertEquals(Unit, result.getOrThrow())
        }

    @Test
    fun delegatesFavoriteFailure() =
        runTest {
            val failure = IllegalStateException("failed")
            val repository = MutatingMediaRepositoryFake(favoriteResult = Result.failure(failure))
            val result = SetItemFavoriteAction(repository)(itemId = "movie-1", favorite = false)

            assertEquals(listOf("movie-1" to false), repository.favoriteCalls)
            assertSame(failure, result.exceptionOrNull())
        }
}
