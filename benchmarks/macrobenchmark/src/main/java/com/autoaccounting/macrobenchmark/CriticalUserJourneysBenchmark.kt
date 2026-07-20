package com.autoaccounting.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
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
    fun coldStartup() = measureColdStartup(CompilationMode.None())

    @Test
    fun coldStartupBaselineProfile() = measureColdStartup(
        CompilationMode.Partial(BaselineProfileMode.Require)
    )

    private fun measureColdStartup(
        compilationMode: CompilationMode
    ) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() }
    ) {
        startActivityAndWait()
        app.waitForHome()
    }

    @Test
    fun ledgerScrollAndDetail() = measureLedgerScrollAndDetail(CompilationMode.None())

    @Test
    fun ledgerScrollAndDetailBaselineProfile() = measureLedgerScrollAndDetail(
        CompilationMode.Partial(BaselineProfileMode.Require)
    )

    private fun measureLedgerScrollAndDetail(
        compilationMode: CompilationMode
    ) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilationMode,
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
    fun automaticBookkeepingSettings() = measureAutomaticBookkeepingSettings(
        CompilationMode.None()
    )

    @Test
    fun automaticBookkeepingSettingsBaselineProfile() = measureAutomaticBookkeepingSettings(
        CompilationMode.Partial(BaselineProfileMode.Require)
    )

    private fun measureAutomaticBookkeepingSettings(
        compilationMode: CompilationMode
    ) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilationMode,
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
