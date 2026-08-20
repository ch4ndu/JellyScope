// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

internal data class SecurityCommandResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
)

internal fun interface SecurityCommandRunner {
    fun run(arguments: List<String>): SecurityCommandResult
}

internal class MacKeychainSecureStore(
    private val legacyFile: File,
    private val commandRunner: SecurityCommandRunner = ProcessSecurityCommandRunner,
    private val json: Json = Json,
) : SecureStore {
    private val mutex = Mutex()
    private var migrationAttempted = false

    override suspend fun read(key: String): String? =
        mutex.withLock {
            ensureLegacyMigration()

            val result =
                runSecurityCommand(
                    listOf(
                        "find-generic-password",
                        "-s",
                        KEYCHAIN_SERVICE,
                        "-a",
                        key,
                        "-w",
                    ),
                )
            when {
                result.exitCode == 0 -> result.stdout.removeTrailingLineBreaks()
                result.isItemNotFound() -> null
                else -> throw securityCommandFailure("read", result)
            }
        }

    override suspend fun write(
        key: String,
        value: String,
    ) {
        mutex.withLock {
            ensureLegacyMigration()
            requireSecurityCommandSuccess(
                operation = "write",
                result =
                    runSecurityCommand(
                        listOf(
                            "add-generic-password",
                            "-U",
                            "-s",
                            KEYCHAIN_SERVICE,
                            "-a",
                            key,
                            "-w",
                            value,
                        ),
                    ),
            )
        }
    }

    override suspend fun remove(key: String) {
        mutex.withLock {
            ensureLegacyMigration()
            val result =
                runSecurityCommand(
                    listOf(
                        "delete-generic-password",
                        "-s",
                        KEYCHAIN_SERVICE,
                        "-a",
                        key,
                    ),
                )
            if (result.exitCode != 0 && !result.isItemNotFound()) {
                throw securityCommandFailure("remove", result)
            }
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            ensureLegacyMigration()
            // Each key is a separate generic-password item, and
            // `delete-generic-password` removes only one match per call, so loop
            // until the service has no remaining items. The bound guards against a
            // misbehaving runner reporting success without deleting anything.
            repeat(MAX_CLEAR_DELETIONS) {
                val result =
                    runSecurityCommand(
                        listOf(
                            "delete-generic-password",
                            "-s",
                            KEYCHAIN_SERVICE,
                        ),
                    )
                when {
                    result.isItemNotFound() -> return@withLock
                    result.exitCode != 0 -> throw securityCommandFailure("clear", result)
                }
            }
            throw IllegalStateException("Keychain clear exceeded $MAX_CLEAR_DELETIONS deletions for $KEYCHAIN_SERVICE.")
        }
    }

    private suspend fun ensureLegacyMigration() {
        if (migrationAttempted) {
            return
        }

        withContext(Dispatchers.IO) {
            if (legacyFile.isFile) {
                val values =
                    runCatching {
                        json.decodeFromString<Map<String, String>>(
                            Files.readString(legacyFile.toPath(), StandardCharsets.UTF_8),
                        )
                    }.getOrDefault(emptyMap())

                values.forEach { (key, value) ->
                    requireSecurityCommandSuccess(
                        operation = "migrate",
                        result =
                            commandRunner.run(
                                listOf(
                                    "add-generic-password",
                                    "-U",
                                    "-s",
                                    KEYCHAIN_SERVICE,
                                    "-a",
                                    key,
                                    "-w",
                                    value,
                                ),
                            ),
                    )
                }
                Files.deleteIfExists(legacyFile.toPath())
            }
            migrationAttempted = true
        }
    }

    private suspend fun runSecurityCommand(arguments: List<String>): SecurityCommandResult =
        withContext(Dispatchers.IO) {
            commandRunner.run(arguments)
        }

    private fun requireSecurityCommandSuccess(
        operation: String,
        result: SecurityCommandResult,
    ) {
        if (result.exitCode != 0) {
            throw securityCommandFailure(operation, result)
        }
    }

    private fun securityCommandFailure(
        operation: String,
        result: SecurityCommandResult,
    ): IllegalStateException =
        IllegalStateException(
            "macOS Keychain $operation failed with exit code ${result.exitCode}",
        )

    private fun SecurityCommandResult.isItemNotFound(): Boolean =
        exitCode == ERR_SEC_ITEM_NOT_FOUND ||
            exitCode == SECURITY_CLI_ITEM_NOT_FOUND_EXIT_CODE ||
            stderr.contains("could not be found", ignoreCase = true) ||
            stderr.contains("item not found", ignoreCase = true)

    private fun String.removeTrailingLineBreaks(): String = trimEnd('\r', '\n')

    private companion object {
        const val KEYCHAIN_SERVICE = "com.jellyscope.secure-store"
        const val ERR_SEC_ITEM_NOT_FOUND = -25300
        const val SECURITY_CLI_ITEM_NOT_FOUND_EXIT_CODE = 44
        const val MAX_CLEAR_DELETIONS = 10_000
    }
}

private object ProcessSecurityCommandRunner : SecurityCommandRunner {
    override fun run(arguments: List<String>): SecurityCommandResult {
        val process =
            ProcessBuilder(listOf("/usr/bin/security") + arguments)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return if (exitCode == 0) {
            SecurityCommandResult(exitCode = exitCode, stdout = output)
        } else {
            SecurityCommandResult(exitCode = exitCode, stderr = output)
        }
    }
}
