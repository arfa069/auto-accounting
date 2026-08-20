package com.bks.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class LargeDatasetReportsBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private lateinit var app: BenchmarkApp

    @Before
    fun prepareDevice() {
        app = BenchmarkApp(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))
        app.lockNaturalOrientation()
    }

    @After
    fun restoreDeviceOrientation() {
        app.unlockOrientation()
    }

    @Test
    fun reportsWithOneThousandEntries() = measureReports(entryCount = 1_000)

    @Test
    fun reportsWithTenThousandEntries() = measureReports(entryCount = 10_000)

    private fun measureReports(entryCount: Int) {
        app.resetAndSeed(entryCount)
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            startupMode = StartupMode.WARM,
            iterations = LARGE_DATASET_ITERATIONS,
            setupBlock = {
                app.forceStop()
                pressHome()
                startActivityAndWait()
                app.waitForHome()
            }
        ) {
            app.openReports()
        }
    }

    private companion object {
        const val LARGE_DATASET_ITERATIONS = 5
    }
}
