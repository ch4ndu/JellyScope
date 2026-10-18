// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class JvmPreviousRunFailureStore(
    private val file: File = File(jellyscopeDataDirectory(), PREVIOUS_RUN_FAILURE_FILE_NAME),
) : PreviousRunFailureStore {
    override fun write(
        throwable: Throwable,
        platform: PreviousRunFailurePlatform,
    ) {
        val marker = previousRunFailureMarker(throwable, platform)
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile ?: File("."), "${file.name}.tmp")
            Files.writeString(
                temporary.toPath(),
                buildString {
                    append(PREVIOUS_RUN_FAILURE_SCHEMA_VERSION)
                    append('\n')
                    append(marker.exceptionType)
                    append('\n')
                    append(marker.platform.wireValue)
                },
                StandardCharsets.UTF_8,
            )
            runCatching {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    override fun consume(): PreviousRunFailureMarker? {
        val marker =
            runCatching {
                val lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)
                decodePreviousRunFailureMarker(
                    schemaVersion = lines.getOrNull(0)?.toIntOrNull(),
                    exceptionType = lines.getOrNull(1),
                    platform = lines.getOrNull(2),
                )
            }.getOrNull()
        clear()
        return marker
    }

    override fun clear() {
        runCatching { Files.deleteIfExists(file.toPath()) }
    }
}

private const val PREVIOUS_RUN_FAILURE_FILE_NAME = "previous-run-failure.marker"
