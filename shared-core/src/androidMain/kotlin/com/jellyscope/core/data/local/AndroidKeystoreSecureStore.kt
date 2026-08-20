// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "com.jellyscope.securestore"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private const val IV_BYTE_COUNT = 12
private const val FILE_NAME = "secure-store.bin"
private const val MAX_KEY_FAILURE_CAUSE_DEPTH = 8

class AndroidKeystoreSecureStore(
    context: Context,
    private val json: Json = Json,
) : SecureStore {
    private var keyProvider: (() -> SecretKey)? = null
    private val storeFile = File(context.noBackupFilesDir, FILE_NAME)
    private val mutex = Mutex()

    internal constructor(
        context: Context,
        keyProvider: () -> SecretKey,
    ) : this(context) {
        this.keyProvider = keyProvider
    }

    override suspend fun read(key: String): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                readMap()[key]
            }
        }

    override suspend fun write(
        key: String,
        value: String,
    ) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val updated = readMap().toMutableMap()
                updated[key] = value
                writeMap(updated)
            }
        }
    }

    override suspend fun remove(key: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val updated = readMap().toMutableMap()
                updated.remove(key)
                writeMap(updated)
            }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                writeMap(emptyMap())
            }
        }
    }

    private fun readMap(): Map<String, String> {
        if (!storeFile.exists()) {
            return emptyMap()
        }

        // Keep transient I/O and Keystore/provider failures outside the corrupt-store
        // recovery path so they propagate rather than destroying valid credentials.
        // Permanent key failures recover narrowly below; corruption/decryption/format
        // failures still recover by discarding the blob.
        val encrypted = storeFile.readBytes()
        if (encrypted.size <= IV_BYTE_COUNT) {
            return recoverFromCorruptStore(throwable = null)
        }
        val key =
            try {
                encryptionKey()
            } catch (throwable: Throwable) {
                if (!isPermanentKeyFailure(throwable)) {
                    throw throwable
                }
                recoverFromPermanentKeyFailure(throwable)
                return emptyMap()
            }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = encrypted.copyOfRange(0, IV_BYTE_COUNT)
        val ciphertext = encrypted.copyOfRange(IV_BYTE_COUNT, encrypted.size)
        return try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val encoded = cipher.doFinal(ciphertext).decodeToString()
            json.decodeFromString<Map<String, String>>(encoded)
        } catch (throwable: GeneralSecurityException) {
            recoverFromCorruptStore(throwable)
        } catch (throwable: SerializationException) {
            recoverFromCorruptStore(throwable)
        }
    }

    private fun recoverFromCorruptStore(throwable: Throwable?): Map<String, String> {
        runCatching { storeFile.delete() }
        secureStoreLogger.w {
            // A truncated blob is detected by length and carries no throwable, so that
            // record omits exceptionType rather than reporting a fabricated one.
            throwable?.let { failure ->
                formatSafeFailureDiagnostic(
                    stage = "secure-store",
                    event = "corrupt-blob-recovery",
                    throwable = failure,
                )
            } ?: "stage=secure-store event=corrupt-blob-recovery"
        }
        return emptyMap()
    }

    private fun writeMap(values: Map<String, String>) {
        val encoded = json.encodeToString(values).encodeToByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val key =
            try {
                encryptionKey()
            } catch (throwable: Throwable) {
                if (!isPermanentKeyFailure(throwable)) {
                    throw throwable
                }
                recoverFromPermanentKeyFailure(throwable)
                throw throwable
            }
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(encoded)
        check(iv.size == IV_BYTE_COUNT)

        val tempFile = File(storeFile.parentFile, "$FILE_NAME.tmp")
        tempFile.writeBytes(iv + ciphertext)
        if (!tempFile.renameTo(storeFile)) {
            tempFile.delete()
            error("Unable to atomically write secure store.")
        }
    }

    private fun recoverFromPermanentKeyFailure(throwable: Throwable) {
        runCatching { storeFile.delete() }
        runCatching {
            KeyStore
                .getInstance(ANDROID_KEYSTORE)
                .apply { load(null) }
                .deleteEntry(KEY_ALIAS)
        }
        secureStoreLogger.w {
            formatSafeFailureDiagnostic(
                stage = "secure-store",
                event = "permanent-key-recovery",
                throwable = throwable,
            )
        }
    }

    // Walks a bounded cause chain because Keystore failures commonly reach callers
    // wrapped (a ProviderException around a KeyStoreException). The recovery trigger set
    // is unchanged by the walk — only these two types ever recover, at any depth.
    private fun isPermanentKeyFailure(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < MAX_KEY_FAILURE_CAUSE_DEPTH) {
            val failure = current
            if (failure is UnrecoverableKeyException) {
                return true
            }
            // The android.security.KeyStoreException reference must stay inside this
            // version check: the class only became public API in 33, and minSdk is 25.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                failure is android.security.KeyStoreException
            ) {
                return !failure.isTransientFailure()
            }
            current = failure.cause
            depth++
        }

        // Below API 33 there is no isTransientFailure(), so a Keystore/provider failure is
        // never classified permanent: forgoing self-healing on older devices is preferred
        // over guessing and destroying recoverable credentials.
        return false
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore =
            KeyStore
                .getInstance(ANDROID_KEYSTORE)
                .apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            return existing
        }

        val keyGenerator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            )
        keyGenerator.init(
            KeyGenParameterSpec
                .Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private fun encryptionKey(): SecretKey = keyProvider?.invoke() ?: getOrCreateKey()
}

private val secureStoreLogger = diagnosticLogger(DiagnosticTag.AndroidKeystoreSecureStore)
