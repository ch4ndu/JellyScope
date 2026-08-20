// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.AndroidMpvRuntimeAvailability
import com.jellyscope.core.domain.playback.AndroidMpvUnavailableReason
import com.jellyscope.core.domain.playback.PlaybackBackendConstructionStage
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class AndroidPlaybackModuleDiagnosticTest {
    @Test
    fun failedMpvConstructionNamesTheSanitizedGateStageAndExceptionType() {
        val failure = IllegalStateException("secret path and token must never be logged")
        val diagnostic =
            androidMpvConstructionDiagnostic(
                availability = AndroidMpvRuntimeAvailability.Bundled("armeabi-v7a"),
                stage = PlaybackBackendConstructionStage.CreateTrustBundle,
                controllerCreated = false,
                failure = failure,
            )

        assertContains(diagnostic, "event=backend-construction")
        assertContains(diagnostic, "backendAvailability=Bundled")
        assertContains(diagnostic, "backendAbi=armeabi-v7a")
        assertContains(diagnostic, "backendConstructionStage=CreateTrustBundle")
        assertContains(diagnostic, "backendConstructionResult=Failed")
        assertContains(diagnostic, "exceptionType=IllegalStateException")
        assertFalse(diagnostic.contains(failure.message.orEmpty()))
    }

    @Test
    fun unavailableMpvConstructionNamesTheClosedAvailabilityReason() {
        val diagnostic =
            androidMpvConstructionDiagnostic(
                availability =
                    AndroidMpvRuntimeAvailability.Unavailable(
                        AndroidMpvUnavailableReason.PlayerLibraryMissing,
                    ),
                stage = PlaybackBackendConstructionStage.NotAttempted,
                controllerCreated = false,
                failure = null,
            )

        assertContains(diagnostic, "backendAvailability=Unavailable")
        assertContains(diagnostic, "backendUnavailableReason=PlayerLibraryMissing")
        assertContains(diagnostic, "backendConstructionResult=Unavailable")
        assertFalse(diagnostic.contains("exceptionType="))
    }

    @Test
    fun rejectedMpvOptionNamesTheSafeKeyAndNativeCodeWithoutAValue() {
        val failure =
            AndroidMpvInitializationException(
                constructionStage = PlaybackBackendConstructionStage.ApplyNativeOption,
                configurationKey = "scripts",
                nativeFailureCode = -5,
            )
        val diagnostic =
            androidMpvConstructionDiagnostic(
                availability = AndroidMpvRuntimeAvailability.Bundled("armeabi-v7a"),
                stage = PlaybackBackendConstructionStage.CreateController,
                controllerCreated = false,
                failure = failure,
            )

        assertContains(diagnostic, "backendConstructionStage=ApplyNativeOption")
        assertContains(diagnostic, "backendConfigurationKey=scripts")
        assertContains(diagnostic, "nativeCode=-5")
        assertFalse(diagnostic.contains("exceptionType="))
    }
}
