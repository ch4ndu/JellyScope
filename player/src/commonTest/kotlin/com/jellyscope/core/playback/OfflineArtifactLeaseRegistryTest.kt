// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.data.local.DownloadArtifactPartKey
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineArtifactLeaseRegistryTest {
    private val identity = OfflineArtifactLeaseIdentity(DownloadId("download_a"), attemptGeneration = 4L)
    private val main = TrustedOfflineArtifactResource(DownloadArtifactPartKey.from("main.bin"), "/trusted/main.bin")

    @Test
    fun activeLeaseBlocksDeletionUntilEveryReaderReleases() =
        runTest {
            val registry = OfflineArtifactLeaseRegistry()
            val first = assertNotNull(registry.acquire(identity, DownloadArtifactKind.OriginalFile, main))
            val second = assertNotNull(registry.acquire(identity, DownloadArtifactKind.OriginalFile, main))

            assertTrue(registry.isLeased(identity))
            assertIs<OfflineArtifactDeletionGuardResult.ArtifactInUse>(
                registry.withDeletionGuard(identity) { error("deletion must not run") },
            )

            first.release()
            first.release()
            assertTrue(registry.isLeased(identity))
            second.release()
            assertFalse(registry.isLeased(identity))

            val result =
                assertIs<OfflineArtifactDeletionGuardResult.Granted<*>>(
                    registry.withDeletionGuard(identity) { "deleted" },
                )
            assertEquals("deleted", result.value)
        }

    @Test
    fun deletionAdmissionBlocksNewPlaybackLeaseForItsWholeCriticalSection() =
        runTest {
            val registry = OfflineArtifactLeaseRegistry()

            val result =
                registry.withDeletionGuard(identity) {
                    assertNull(registry.acquire(identity, DownloadArtifactKind.OriginalFile, main))
                    Unit
                }

            assertIs<OfflineArtifactDeletionGuardResult.Granted<*>>(result)
            assertNotNull(registry.acquire(identity, DownloadArtifactKind.OriginalFile, main))
                .release()
        }

    @Test
    fun multiArtifactDeletionAdmissionIsAllOrNothing() =
        runTest {
            val registry = OfflineArtifactLeaseRegistry()
            val other = OfflineArtifactLeaseIdentity(DownloadId("download_b"), attemptGeneration = 1L)
            val lease = assertNotNull(registry.acquire(other, DownloadArtifactKind.OriginalFile, main))

            assertIs<OfflineArtifactDeletionGuardResult.ArtifactInUse>(
                registry.withDeletionGuard(setOf(identity, other)) { error("partial deletion must not run") },
            )

            assertNotNull(registry.acquire(identity, DownloadArtifactKind.OriginalFile, main))
                .release()
            lease.release()
        }

    @Test
    fun detachedOldGenerationReleasesOnlyAfterReplacementLeaseIsInstalled() =
        runTest {
            val registry = OfflineArtifactLeaseRegistry()
            val replacementIdentity = OfflineArtifactLeaseIdentity(DownloadId("download_b"), attemptGeneration = 1L)
            val holder = OfflineArtifactLeaseHolder()
            val oldLease = assertNotNull(registry.acquire(identity, DownloadArtifactKind.OriginalFile, main))
            val replacementLease =
                assertNotNull(
                    registry.acquire(
                        replacementIdentity,
                        DownloadArtifactKind.OriginalFile,
                        TrustedOfflineArtifactResource(DownloadArtifactPartKey.from("replacement.bin"), "/trusted/replacement.bin"),
                    ),
                )

            holder.replace(oldLease)
            val detachedOld = assertNotNull(holder.detach())
            holder.replace(replacementLease)
            assertTrue(registry.isLeased(identity))
            assertTrue(registry.isLeased(replacementIdentity))

            detachedOld.release()
            assertFalse(registry.isLeased(identity))
            assertTrue(registry.isLeased(replacementIdentity))
            holder.release()
            assertFalse(registry.isLeased(replacementIdentity))
        }
}
