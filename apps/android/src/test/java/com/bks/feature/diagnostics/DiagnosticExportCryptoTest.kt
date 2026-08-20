package com.bks.feature.diagnostics

import com.bks.data.crypto.PassphraseAesGcm
import java.util.Base64
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticExportCryptoTest {
    @Test
    fun diagnosticsPrefixRoundTripsWithCorrectPassphrase() {
        val jsonl = "{\"event\":\"ocr\",\"merchant\":\"测试商户\"}\n"
        val passphrase = "correct-passphrase".toCharArray()
        val encrypted = PassphraseAesGcm.encrypt(
            plainText = jsonl.toByteArray(),
            passphrase = passphrase,
            salt = ByteArray(16) { it.toByte() },
            iv = ByteArray(12) { (it + 16).toByte() }
        )
        val export = DIAGNOSTICS_EXPORT_PREFIX + Base64.getEncoder().encodeToString(encrypted)

        assertEquals(jsonl, decryptDiagnosticExport(export, passphrase))
    }

    @Test(expected = AEADBadTagException::class)
    fun wrongPassphraseProducesNoPlaintext() {
        val encrypted = PassphraseAesGcm.encrypt(
            "sensitive".toByteArray(),
            "correct-passphrase".toCharArray()
        )
        val export = DIAGNOSTICS_EXPORT_PREFIX + Base64.getEncoder().encodeToString(encrypted)

        decryptDiagnosticExport(export, "wrong-passphrase".toCharArray())
    }
}
