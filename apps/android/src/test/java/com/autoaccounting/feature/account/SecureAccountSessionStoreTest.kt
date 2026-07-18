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
        assertFalse(persistedText.contains(credentials.phone))
        assertFalse(persistedText.contains(credentials.token))

        assertEquals(
            AccountSessionRestoreResult.Restored(credentials),
            SecureAccountSessionStore(context, ReversibleTestCipher()).restore()
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
