// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import android.content.Context
import com.jellyscope.core.domain.playback.playbackExceptionType
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC

/** Process-wide, best-effort fontconfig warmup shared by startup and first playback. */
object AndroidLibVlcFontCache {
    private val mutex = Mutex()
    private var attempted = false
    private val logger = diagnosticLogger(DiagnosticTag.LibVlcPlayerController)

    suspend fun warmUp(context: Context) {
        val appContext = context.applicationContext
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (attempted) return@withLock
                attempted = true
                logger.i { "stage=NativePlayer event=FontCacheWarmup platform=Android backend=LibVlc result=Started" }
                // Own a separate engine for the entire blocking call. Controller
                // release cannot race it, and later players reuse the disk cache.
                val result =
                    runCatching {
                        val engine = LibVLC(appContext, arrayListOf("--no-video-title-show"))
                        try {
                            engine.buildFontCache()
                        } finally {
                            engine.release()
                        }
                    }
                val outcome =
                    when {
                        result.isFailure -> "Failed"
                        result.getOrDefault(false) -> "Ready"
                        else -> "NotWritten"
                    }
                logger.i {
                    "stage=NativePlayer event=FontCacheWarmup platform=Android backend=LibVlc result=$outcome " +
                        "exceptionType=${result.exceptionOrNull()?.playbackExceptionType()}"
                }
                // False/exception leaves normal text-renderer initialization as
                // the fallback; never retry this expensive scan for every item.
            }
        }
    }
}
