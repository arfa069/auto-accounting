package com.autoaccounting.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    private lateinit var app: BenchmarkApp

    @Before
    fun prepareBenchmarkData() {
        app = BenchmarkApp(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))
        app.resetAndSeed()
    }

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE
    ) {
        app.forceStop()
        pressHome()
        startActivityAndWait()
        app.waitForHome()
        app.openLedger()
        app.scrollLedgerAndOpenDetail()
        app.returnHomeFromLedgerDetail()
        app.openAutomaticBookkeeping()
    }
}
