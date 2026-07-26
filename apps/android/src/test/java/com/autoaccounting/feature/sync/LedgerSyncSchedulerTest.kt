package com.autoaccounting.feature.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LedgerSyncSchedulerTest {
    @Test
    fun repeatedSchedulingKeepsOneImmediateAndOnePeriodicWorker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)

        LedgerSyncScheduler.enqueueNow(context)
        LedgerSyncScheduler.enqueueNow(context)
        LedgerSyncScheduler.ensurePeriodic(context)
        LedgerSyncScheduler.ensurePeriodic(context)

        assertEquals(
            1,
            workManager.getWorkInfosForUniqueWork(LedgerSyncScheduler.LEDGER_SYNC_NOW).get().size
        )
        assertEquals(
            1,
            workManager.getWorkInfosForUniqueWork(LedgerSyncScheduler.LEDGER_SYNC_PERIODIC).get().size
        )
    }
}
