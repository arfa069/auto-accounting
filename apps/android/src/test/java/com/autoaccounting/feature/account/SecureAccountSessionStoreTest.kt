package com.autoaccounting.feature.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecureAccountSessionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences by lazy {
        context.getSharedPreferences("account_session_secure", Context.MODE_PRIVATE)
    }

    @Before
    fun setUp() {
        preferences.edit().clear().commit()
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun encryptedSessionRestoresWithoutPlaintextPreferences() {
        val credentials = AccountCredentials("13800138000", "sensitive-token")
        val store = SecureAccountSessionStore(context, ReversibleTestCipher())

        assertTrue(store.save(credentials))
        val persistedText = preferences.all.values.joinToString()
        assertFalse(persistedText.contains(requireNotNull(credentials.phone)))
        assertFalse(persistedText.contains(credentials.token))

        assertEquals(
            AccountSessionRestoreResult.Restored(credentials),
            SecureAccountSessionStore(context, ReversibleTestCipher()).restore()
        )
    }

    @Test
    fun versionTwoRestoresWechatOnlyProfileWithoutPlaintextFields() {
        val credentials = AccountCredentials(
            phone = null,
            token = "wechat-sensitive-token",
            wechatLinked = true,
            nickname = "微信小张",
            avatarUrl = "https://example.com/avatar.jpg"
        )
        val store = SecureAccountSessionStore(context, ReversibleTestCipher())

        assertTrue(store.save(credentials))
        val persistedText = preferences.all.values.joinToString()
        assertFalse(persistedText.contains(credentials.token))
        assertFalse(persistedText.contains(requireNotNull(credentials.nickname)))
        assertFalse(persistedText.contains(requireNotNull(credentials.avatarUrl)))
        assertEquals(
            AccountSessionRestoreResult.Restored(credentials),
            SecureAccountSessionStore(context, ReversibleTestCipher()).restore()
        )
    }

    @Test
    fun legacyVersionOnePhoneSessionRestoresAndNextSaveUpgradesIt() {
        val legacyPhone = "13800138000".toByteArray()
        val legacyToken = "legacy-token".toByteArray()
        val legacyPlaintext = ByteBuffer.allocate(
            1 + Int.SIZE_BYTES + legacyPhone.size + Int.SIZE_BYTES + legacyToken.size
        )
            .put(1.toByte())
            .putInt(legacyPhone.size)
            .put(legacyPhone)
            .putInt(legacyToken.size)
            .put(legacyToken)
            .array()
        val encrypted = ReversibleTestCipher().encrypt(legacyPlaintext)
        preferences.edit()
            .putString(
                "encrypted_session",
                android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
            )
            .commit()
        val store = SecureAccountSessionStore(context, ReversibleTestCipher())

        val restored = store.restore() as AccountSessionRestoreResult.Restored
        assertEquals(AccountCredentials("13800138000", "legacy-token"), restored.credentials)
        assertTrue(store.save(restored.credentials.copy(nickname = "ignored-without-wechat")))
        val upgradedCiphertext = android.util.Base64.decode(
            preferences.getString("encrypted_session", null),
            android.util.Base64.NO_WRAP
        )
        assertEquals(2.toByte(), ReversibleTestCipher().decrypt(upgradedCiphertext).first())
        assertEquals(
            restored.credentials.copy(nickname = "ignored-without-wechat"),
            (store.restore() as AccountSessionRestoreResult.Restored).credentials
        )
    }

    @Test
    fun corruptedCiphertextIsClearedAndDowngradesSafely() {
        preferences.edit().putString("encrypted_session", "broken-base64***").commit()
        val store = SecureAccountSessionStore(context, ReversibleTestCipher())

        assertEquals(AccountSessionRestoreResult.Corrupted, store.restore())
        assertEquals(AccountSessionRestoreResult.Empty, store.restore())
    }

    @Test
    fun encryptionFailureDoesNotPersistSession() {
        val workingStore = SecureAccountSessionStore(context, ReversibleTestCipher())
        assertTrue(workingStore.save(AccountCredentials("13800138000", "old-token")))
        val store = SecureAccountSessionStore(context, FailingCipher())

        assertFalse(store.save(AccountCredentials("13800138000", "token")))
        assertTrue(preferences.all.isEmpty())
    }

    private class ReversibleTestCipher : AccountSessionCipher {
        override fun encrypt(plainText: ByteArray): ByteArray = plainText.reversedArray()
        override fun decrypt(cipherText: ByteArray): ByteArray = cipherText.reversedArray()
    }

    private class FailingCipher : AccountSessionCipher {
        override fun encrypt(plainText: ByteArray): ByteArray = error("keystore unavailable")
        override fun decrypt(cipherText: ByteArray): ByteArray = error("keystore unavailable")
    }
}
