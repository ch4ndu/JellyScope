// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import coil3.Image
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

actual fun platformAmbientColorExtractor(): AmbientColorExtractor = SkiaAmbientColorExtractor()

private class SkiaAmbientColorExtractor : AmbientColorExtractor {
    private val cache = mutableMapOf<String, androidx.compose.ui.graphics.Color>()
    private val cacheMutex = Mutex()

    override suspend fun extract(
        image: Image,
        key: String,
    ): androidx.compose.ui.graphics.Color? {
        cacheMutex.withLock { cache[key] }?.let { cached -> return cached }
        val color =
            withContext(Dispatchers.Default) {
                val bitmap = image.toBitmap(SAMPLE_SIZE, SAMPLE_SIZE)
                ambientColorFromArgbSamples(
                    buildList(SAMPLE_COUNT) {
                        repeat(SAMPLE_GRID) { y ->
                            repeat(SAMPLE_GRID) { x ->
                                add(bitmap.getColor(x * SAMPLE_STEP, y * SAMPLE_STEP))
                            }
                        }
                    },
                )
            } ?: return null
        cacheMutex.withLock {
            if (cache.size >= CACHE_SIZE) cache.remove(cache.keys.first())
            cache[key] = color
        }
        return color
    }
}

private const val SAMPLE_SIZE = 48
private const val SAMPLE_GRID = 12
private const val SAMPLE_STEP = SAMPLE_SIZE / SAMPLE_GRID
private const val SAMPLE_COUNT = SAMPLE_GRID * SAMPLE_GRID
private const val CACHE_SIZE = 64
