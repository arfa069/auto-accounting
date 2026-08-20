package com.bks.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BksDatabaseInstrumentedTest {
    private lateinit var database: BksDatabase
    private lateinit var repository: LocalLedgerRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(
            context,
            BksDatabase::class.java
        ).build()
        repository = LocalLedgerRepository(
            database = database,
            clock = { NOW },
            idGenerator = { "device-entry" }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun ledgerEntryRoundTripsAndForeignKeysUseDeviceSqlite() = runBlocking {
        repository.seedSystemCategories()
        val ledgerBook = repository.ensureDefaultLedgerBook()
        val fundingAccount = repository.ensureFundingAccount(
            source = PaymentSource.ALIPAY,
            label = "余额"
        )

        repository.createManualEntry(
            ledgerBookId = ledgerBook.id,
            input = LedgerEntryInput(
                flowDirection = FlowDirection.OUTFLOW,
                transactionKind = TransactionKind.EXPENSE,
                amountMinor = 1_234,
                transactionTimeEpochMillis = NOW - 1_000,
                merchantTitle = "设备 SQLite 测试",
                categoryId = "food",
                fundingAccountId = fundingAccount.id,
                newFundingAccountLabel = null,
                note = "保留中文",
                paymentSource = PaymentSource.ALIPAY
            )
        )

        val stored = database.ledgerEntryDao().getById("device-entry")
        assertEquals("设备 SQLite 测试", stored?.merchantTitle)
        assertEquals("保留中文", stored?.note)
        assertEquals("food", stored?.categoryId)
        assertEquals(fundingAccount.id, stored?.fundingAccountId)

        database.categoryDao().deleteAll()
        database.fundingAccountDao().deleteById(fundingAccount.id)

        val afterForeignKeyDeletes = database.ledgerEntryDao().getById("device-entry")
        assertNull(afterForeignKeyDeletes?.categoryId)
        assertNull(afterForeignKeyDeletes?.fundingAccountId)
    }

    private companion object {
        const val NOW = 1_750_000_000_000L
    }
}
