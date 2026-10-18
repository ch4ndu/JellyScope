// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AndroidMpvNetworkPolicyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val session =
        Session(
            serverUrl = "https://jellyfin.example",
            serverId = "server",
            serverName = "Jellyfin",
            userId = "user",
            userName = "User",
            accessToken = "secret-token",
            deviceId = "device",
        )

    @Test
    fun sameOriginUsesSanitizedUrlAndModernTokenOnlyHeader() {
        val policy = AndroidMpvNetworkPolicy(context, session, ioDispatcher = Dispatchers.Unconfined)

        val result = policy.loadRequest(plan("https://jellyfin.example/videos/1?api_key=old&x=1"), null)
        val accepted = assertIs<AndroidMpvNetworkResult.Accepted>(result)

        assertEquals("https://jellyfin.example/videos/1?x=1", accepted.request.url)
        assertEquals("Authorization: MediaBrowser Token=\"secret-token\"", accepted.request.authorizationHeader)
        assertTrue("old" !in accepted.request.url)
    }

    @Test
    fun foreignCleartextMainUrlIsRejectedEvenWhenPlatformAllowsCleartext() {
        val policy =
            AndroidMpvNetworkPolicy(
                context = context,
                session = session,
                ioDispatcher = Dispatchers.Unconfined,
                cleartextPermitted = { true },
            )

        assertEquals(
            AndroidMpvNetworkResult.Rejected(AndroidMpvNetworkRejection.CrossOrigin),
            policy.loadRequest(plan("http://other.example/videos/1"), null),
        )
    }

    @Test
    fun sameOriginCleartextMainUrlStillRequiresPlatformPermission() {
        val cleartextSession = session.copy(serverUrl = "http://jellyfin.example")
        val policy =
            AndroidMpvNetworkPolicy(
                context = context,
                session = cleartextSession,
                ioDispatcher = Dispatchers.Unconfined,
                cleartextPermitted = { false },
            )

        assertEquals(
            AndroidMpvNetworkResult.Rejected(AndroidMpvNetworkRejection.CleartextNotPermitted),
            policy.loadRequest(plan("http://jellyfin.example/videos/1"), null),
        )
    }

    @Test
    fun foreignRemoteSubtitleIsRejectedWithoutLeakingMainToken() {
        val policy = AndroidMpvNetworkPolicy(context, session, ioDispatcher = Dispatchers.Unconfined)
        val subtitle =
            SubtitleAsset.JellyfinRemote(
                url = "https://other.example/subtitles/1.srt",
                mimeType = "application/x-subrip",
                label = "English",
                language = "en",
            )

        val result = assertIs<AndroidMpvNetworkResult.Accepted>(policy.loadRequest(plan("https://jellyfin.example/videos/1"), subtitle))

        assertEquals(null, result.request.subtitleUrl)
        assertEquals(null, result.request.localSubtitlePath)
        assertEquals(AndroidMpvNetworkRejection.CrossOrigin, result.request.subtitleRejection)
        assertEquals("Authorization: MediaBrowser Token=\"secret-token\"", result.request.authorizationHeader)
    }

    @Test
    fun localSubtitleNeverReceivesRemoteCredentials() {
        val store =
            object : LocalSubtitleFileStore {
                override suspend fun writeAtomically(
                    fileId: String,
                    bytes: ByteArray,
                ) = Unit

                override suspend fun read(fileId: String): ByteArray? = null

                override suspend fun exists(fileId: String): Boolean = true

                override suspend fun delete(fileId: String) = Unit

                override suspend fun listFileIds(): Set<String> = setOf("sub.srt")

                override fun resolvePath(fileId: String): String? = "/data/user/0/app/files/$fileId"
            }
        val policy = AndroidMpvNetworkPolicy(context, session, store, Dispatchers.Unconfined)
        val subtitle =
            SubtitleAsset.LocalFile(
                assetId = "asset",
                fileId = "sub.srt",
                mimeType = "application/x-subrip",
                label = "English",
                language = "en",
            )

        val accepted = assertIs<AndroidMpvNetworkResult.Accepted>(policy.loadRequest(plan("https://jellyfin.example/videos/1"), subtitle))

        assertEquals("/data/user/0/app/files/sub.srt", accepted.request.localSubtitlePath)
        assertEquals(null, accepted.request.subtitleUrl)
        assertEquals("Authorization: MediaBrowser Token=\"secret-token\"", accepted.request.authorizationHeader)
    }

    @Test
    fun pemProjectionIsBoundedAndUsesCertificateMarkers() {
        val pem = certificatesToPem(emptyList<X509Certificate>())
        assertEquals("", pem)
        assertTrue(File(context.cacheDir, "mpv").path.isNotBlank())
    }

    @Test
    fun trustBundleReplacesStaleContentAtomically() =
        runTest {
            val policy =
                AndroidMpvNetworkPolicy(
                    context = context,
                    session = session,
                    ioDispatcher = Dispatchers.Unconfined,
                    trustCertificates = { emptyList() },
                )

            val bundle = policy.ensureTrustBundle()
            bundle.writeText("stale-certificate")
            val replaced = policy.ensureTrustBundle()

            assertEquals(bundle, replaced)
            assertEquals("", replaced.readText())
            replaced.delete()
        }

    private fun plan(url: String) =
        PlaybackPlan(
            itemId = "item",
            mediaSourceId = "source",
            startPositionMs = 0L,
            streamMode = StreamMode.DirectPlay,
            streamUrl = url,
            progressReportingPolicy = ProgressReportingPolicy(reportIntervalMs = 10_000L),
        )
}
