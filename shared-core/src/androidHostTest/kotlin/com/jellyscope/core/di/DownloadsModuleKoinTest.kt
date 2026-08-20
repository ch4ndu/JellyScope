// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jellyscope.core.data.local.DownloadDatabase
import com.jellyscope.core.data.local.JellyfinStoreDatabase
import com.jellyscope.core.data.remote.ClientInfo
import com.jellyscope.core.data.repository.NoOpSessionBoundaryParticipant
import com.jellyscope.core.data.repository.SessionBoundaryParticipant
import com.jellyscope.core.data.repository.SessionRepository
import com.jellyscope.core.data.repository.SessionTransitionCoordinator
import com.jellyscope.core.domain.action.EnqueueFixedDownloadAction
import com.jellyscope.core.domain.usecase.FixedDownloadAdmission
import com.jellyscope.core.domain.usecase.FixedDownloadCapability
import com.jellyscope.core.domain.usecase.GetOfflinePlaybackPlanUseCase
import com.jellyscope.core.domain.usecase.PreviewFixedDownloadUseCase
import com.jellyscope.core.download.DownloadCleanupCoordinator
import com.jellyscope.core.download.DownloadHlsTransferCoordinator
import com.jellyscope.core.download.DownloadTransferCoordinator
import com.jellyscope.core.playback.OfflineArtifactResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
class DownloadsModuleKoinTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun supportedGraphInjectsRealParticipantRegardlessOfModuleOrder() {
        val orderings =
            listOf(
                listOf(androidCoreModule(context), coreModule, downloadsModule),
                listOf(downloadsModule, coreModule, androidCoreModule(context)),
            )

        orderings.forEach { graphModules ->
            val application =
                koinApplication(createEagerInstances = false) {
                    allowOverride(false)
                    androidContext(context)
                    modules(graphModules + testClientInfoModule)
                }
            try {
                application.koin.get<SessionRepository>()
                val coordinator = application.koin.get<SessionTransitionCoordinator>()
                val participant = application.koin.get<SessionBoundaryParticipant>()

                assertIs<DownloadCleanupCoordinator>(participant)
                assertSame(participant, coordinator.boundaryParticipant())
                assertIs<GetOfflinePlaybackPlanUseCase>(application.koin.get<GetOfflinePlaybackPlanUseCase>())
                assertIs<OfflineArtifactResolver>(application.koin.get<OfflineArtifactResolver>())
                assertIs<FixedDownloadAdmission>(application.koin.get<FixedDownloadAdmission>())
                assertIs<FixedDownloadCapability>(application.koin.get<FixedDownloadCapability>())
                assertIs<PreviewFixedDownloadUseCase>(application.koin.get<PreviewFixedDownloadUseCase>())
                assertIs<EnqueueFixedDownloadAction>(application.koin.get<EnqueueFixedDownloadAction>())
                assertIs<DownloadHlsTransferCoordinator>(application.koin.get<DownloadHlsTransferCoordinator>())
                assertIs<DownloadTransferCoordinator>(application.koin.get<DownloadTransferCoordinator>())
            } finally {
                application.closeDatabases()
            }
        }
    }

    @Test
    fun coreGraphWithoutDownloadsKeepsNoOpParticipantDefault() {
        val application =
            koinApplication(createEagerInstances = false) {
                allowOverride(false)
                androidContext(context)
                modules(androidCoreModule(context), coreModule)
            }
        try {
            application.koin.get<SessionRepository>()
            val coordinator = application.koin.get<SessionTransitionCoordinator>()

            assertNull(application.koin.getOrNull<SessionBoundaryParticipant>())
            assertNull(application.koin.getOrNull<FixedDownloadCapability>())
            assertSame(NoOpSessionBoundaryParticipant, coordinator.boundaryParticipant())
        } finally {
            application.closeDatabases()
        }
    }
}

private fun SessionTransitionCoordinator.boundaryParticipant(): SessionBoundaryParticipant {
    val field = SessionTransitionCoordinator::class.java.getDeclaredField("sessionBoundaryParticipant")
    field.isAccessible = true
    return checkNotNull(field.get(this) as? SessionBoundaryParticipant)
}

private val testClientInfoModule =
    module {
        single { ClientInfo(versionName = "test") }
    }

private fun KoinApplication.closeDatabases() {
    koin.getOrNull<CoroutineScope>()?.cancel()
    koin.getOrNull<DownloadDatabase>()?.close()
    koin.getOrNull<JellyfinStoreDatabase>()?.close()
    close()
}
