// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.domain.playback.DeviceDecodingCapabilities
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerBackendPlatform
import com.jellyscope.core.domain.playback.PlayerBackendPolicy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GetAvailablePlayerBackendsUseCaseTest {
    private val policy =
        PlayerBackendPolicy(
            platform = PlayerBackendPlatform.Android,
            defaultBackend = PlayerBackend.ExoPlayer,
            visibleBackends = listOf(PlayerBackend.ExoPlayer, PlayerBackend.Mpv, PlayerBackend.LibVlc),
        )

    private class FakeProvider(
        override val backendPolicy: PlayerBackendPolicy,
        private val backends: () -> Set<PlayerBackend>,
    ) : DeviceProfileProvider {
        override val availableBackends: Set<PlayerBackend>
            get() = backends()

        override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
            DeviceDecodingCapabilities(
                videoCodecs = emptyList(),
                audioCodecs = emptyList(),
                supportsDolbyVision = false,
            )
    }

    @Test
    fun reportsTheBackendsTheProviderResolved() =
        runTest {
            val useCase =
                GetAvailablePlayerBackendsUseCase(
                    FakeProvider(policy) { setOf(PlayerBackend.ExoPlayer, PlayerBackend.Mpv) },
                )

            assertEquals(setOf(PlayerBackend.ExoPlayer, PlayerBackend.Mpv), useCase())
        }

    @Test
    fun fallsBackToTheDefaultBackendWhenTheProbeReportsNothing() =
        runTest {
            val useCase = GetAvailablePlayerBackendsUseCase(FakeProvider(policy) { emptySet() })

            assertEquals(setOf(PlayerBackend.ExoPlayer), useCase())
        }

    @Test
    fun fallsBackToTheDefaultBackendWhenTheProbeFails() =
        runTest {
            val useCase =
                GetAvailablePlayerBackendsUseCase(
                    FakeProvider(policy) { error("native probe unavailable") },
                )

            assertEquals(setOf(PlayerBackend.ExoPlayer), useCase())
        }
}
