package com.bks.feature.diagnostics

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal interface DiagnosticEventCipher {
    fun encrypt(plainText: ByteArray): ByteArray
    fun decrypt(payload: ByteArray): ByteArray
    fun deleteKey()
}

internal class AndroidKeystoreDiagnosticCipher(
    private val alias: String = KEY_ALIAS
) : DiagnosticEventCipher {
    @Volatile
    private var cachedKey: SecretKey? = null

    override fun encrypt(plainText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher.iv + cipher.doFinal(plainText)
    }

    override fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > IV_BYTES) { "Invalid diagnostic payload" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_BYTES))
        )
        return cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size))
    }

    override fun deleteKey() {
        synchronized(this) {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            cachedKey = null
        }
    }

    private fun getOrCreateKey(): SecretKey = cachedKey ?: synchronized(this) {
        cachedKey ?: run {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(alias, null) as? SecretKey)
                ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                    init(
                        KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                        )
                            .setKeySize(256)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setRandomizedEncryptionRequired(true)
                            .build()
                    )
                    generateKey()
                }
        }.also { cachedKey = it }
    }

    private companion object {
        const val KEY_ALIAS = "bks_diagnostics_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

internal class JvmDiagnosticEventCipher(
    keyBytes: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)
) : DiagnosticEventCipher {
    private var key: SecretKeySpec? = SecretKeySpec(keyBytes.copyOf(), "AES")
    private val secureRandom = SecureRandom()

    override fun encrypt(plainText: ByteArray): ByteArray {
        val currentKey = getOrCreateKey()
        val iv = ByteArray(12).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, currentKey, GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plainText)
    }

    override fun decrypt(payload: ByteArray): ByteArray {
        val currentKey = checkNotNull(key) { "Diagnostic key is missing" }
        require(payload.size > 12) { "Invalid diagnostic payload" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, currentKey, GCMParameterSpec(128, payload.copyOfRange(0, 12)))
        return cipher.doFinal(payload.copyOfRange(12, payload.size))
    }

    override fun deleteKey() {
        key = null
    }

    private fun getOrCreateKey(): SecretKeySpec = key ?: SecretKeySpec(
        ByteArray(32).also(secureRandom::nextBytes),
        "AES"
    ).also { key = it }
}

internal class DiagnosticEncryptedStore(
    private val directory: File,
    private val cipher: DiagnosticEventCipher,
    private val maxSegmentBytes: Long = 1L * 1024 * 1024,
    private val maxTotalBytes: Long = 10L * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis
) {
    init {
        directory.mkdirs()
    }

    fun append(event: DiagnosticEvent): Boolean {
        directory.mkdirs()
        val plainText = DiagnosticEventCodec.encode(event).toByteArray(Charsets.UTF_8)
        val encodedLine = LINE_PREFIX + Base64.getEncoder().encodeToString(cipher.encrypt(plainText)) + "\n"
        val bytes = encodedLine.toByteArray(Charsets.US_ASCII)
        var segment = segments().lastOrNull()
        if (segment == null || segment.length() + bytes.size > maxSegmentBytes) {
            segment = nextSegment()
        }
        segment.appendBytes(bytes)
        return rotateIfNeeded()
    }

    fun readAll(): List<DiagnosticEvent> = readLatest(Int.MAX_VALUE)

    fun readLatest(limit: Int): List<DiagnosticEvent> {
        if (limit <= 0) return emptyList()
        val newestFirst = mutableListOf<DiagnosticEvent>()
        for (segment in segments().asReversed()) {
            val lines = runCatching { segment.readLines(Charsets.US_ASCII) }
                .getOrDefault(emptyList())
            for (line in lines.asReversed()) {
                decodeLine(line)?.let(newestFirst::add)
                if (newestFirst.size == limit) return newestFirst.asReversed()
            }
        }
        return newestFirst.asReversed()
    }

    fun eventCount(): Int = segments().sumOf { segment ->
        runCatching {
            segment.readLines(Charsets.US_ASCII).count { it.startsWith(LINE_PREFIX) }
        }.getOrDefault(0)
    }

    fun encryptedBytes(): Long = segments().sumOf(File::length)

    fun segmentCount(): Int = segments().size

    fun clear() {
        segments().forEach { runCatching { it.delete() } }
        runCatching { cipher.deleteKey() }
        runCatching { directory.delete() }
    }

    private fun segments(): List<File> = directory.listFiles { file ->
        file.isFile && file.extension == SEGMENT_EXTENSION
    }?.sortedBy(File::getName).orEmpty()

    private fun decodeLine(line: String): DiagnosticEvent? = runCatching {
        require(line.startsWith(LINE_PREFIX))
        val encrypted = Base64.getDecoder().decode(line.removePrefix(LINE_PREFIX))
        val json = cipher.decrypt(encrypted).toString(Charsets.UTF_8)
        DiagnosticEventCodec.decode(json)
    }.getOrNull()

    private fun nextSegment(): File {
        val base = clock()
        var sequence = 0
        while (true) {
            val candidate = File(directory, "segment-${base.toString().padStart(20, '0')}-$sequence.$SEGMENT_EXTENSION")
            if (!candidate.exists()) return candidate
            sequence += 1
        }
    }

    private fun rotateIfNeeded(): Boolean {
        val current = segments().toMutableList()
        var total = current.sumOf(File::length)
        var rotated = false
        while (total > maxTotalBytes && current.size > 1) {
            val oldest = current.removeAt(0)
            val size = oldest.length()
            if (oldest.delete()) {
                total -= size
                rotated = true
            } else {
                break
            }
        }
        return rotated
    }

    private companion object {
        const val LINE_PREFIX = "AADLOG1:"
        const val SEGMENT_EXTENSION = "aadlog"
    }
}
