package com.bks.feature.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bks.data.local.BksDatabaseProvider
import com.bks.data.local.FlowDirection
import com.bks.data.local.LedgerEntryInput
import com.bks.data.local.LocalLedgerRepository
import com.bks.data.local.PaymentSource
import com.bks.data.local.TransactionKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountLedgerIsolationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ledgerRepository by lazy {
        LocalLedgerRepository(BksDatabaseProvider.get(context))
    }

    @Before
    fun setUp() {
        runBlocking { ledgerRepository.clearLocalData() }
        context.getSharedPreferences("account_session_secure", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        runBlocking { ledgerRepository.clearLocalData() }
        context.getSharedPreferences("account_session_secure", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun persistingAndSigningOutAccountSessionDoesNotChangeLocalLedger() = runBlocking {
        val ledger = ledgerRepository.ensureDefaultLedgerBook()
        ledgerRepository.createManualEntry(
            ledger.id,
            LedgerEntryInput(
                flowDirection = FlowDirection.OUTFLOW,
                transactionKind = TransactionKind.EXPENSE,
                amountMinor = 1_200,
                transactionTimeEpochMillis = 1_000,
                merchantTitle = "本机账目",
                categoryId = null,
                fundingAccountId = null,
                newFundingAccountLabel = null,
                note = null,
                paymentSource = PaymentSource.ALIPAY
            )
        )
        val before = ledgerRepository.listLedgerEntries()
        val sessionStore = SecureAccountSessionStore(context, ReversibleCipher())

        assert(sessionStore.save(AccountCredentials("13800138000", "token-1")))
        assert(sessionStore.clear())

        assertEquals(before, ledgerRepository.listLedgerEntries())
    }

    private class ReversibleCipher : AccountSessionCipher {
        override fun encrypt(plainText: ByteArray): ByteArray = plainText.reversedArray()
        override fun decrypt(cipherText: ByteArray): ByteArray = cipherText.reversedArray()
    }
}
