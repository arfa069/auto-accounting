package com.autoaccounting.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class CriticalUserJourneysBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private lateinit var app: BenchmarkApp

    @Before
    fun prepareBenchmarkData() {
        app = BenchmarkApp(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))
        app.lockNaturalOrientation()
        app.resetAndSeed()
    }

    @After
    fun restoreDeviceOrientation() {
        app.unlockOrientation()
    }

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() }
    ) {
        startActivityAndWait()
        app.waitForHome()
    }

    @Test
    fun ledgerScrollAndDetail() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = {
            app.forceStop()
            pressHome()
            startActivityAndWait()
            app.waitForHome()
            app.openLedger()
        }
    ) {
        app.scrollLedgerAndOpenDetail()
    }

    @Test
    fun automaticBookkeepingSettings() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = {
            app.forceStop()
            pressHome()
            startActivityAndWait()
            app.waitForHome()
        }
    ) {
        app.openAutomaticBookkeeping()
    }
}
