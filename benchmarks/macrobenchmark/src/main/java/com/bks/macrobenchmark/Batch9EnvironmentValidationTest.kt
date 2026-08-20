package com.bks.macrobenchmark

import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Batch9EnvironmentValidationTest {
    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @Test
    fun signedInSessionRestoresToHome() {
        resetTarget()
        val seeded = call(METHOD_SEED_SIGNED_IN_SESSION)
        assertTrue(seeded.getBoolean(RESULT_SESSION_SAVED))

        device.executeShellCommand("am force-stop $TARGET_PACKAGE")
        val launchResult = device.executeShellCommand(
            "am start -W -n $TARGET_PACKAGE/com.bks.MainActivity"
        )
        assertTrue(launchResult.contains("Status: ok"))
        assertTrue(device.wait(Until.hasObject(By.text("账本")), TIMEOUT_MILLIS))
    }

    @Test
    fun controlledNetworkReportsStagesAndCancellation() {
        resetTarget()
        val result = call(
            METHOD_NETWORK,
            Bundle().apply { putString(ARG_ENDPOINT, LOOPBACK_ENDPOINT) }
        )

        println(
            "BATCH9_NETWORK headersMs=${result.getLong(RESULT_RESPONSE_HEADERS_MILLIS)} " +
                "bodyMs=${result.getLong(RESULT_RESPONSE_BODY_MILLIS)} " +
                "completeMs=${result.getLong(RESULT_COMPLETE_MILLIS)} " +
                "cancelMs=${result.getLong(RESULT_CANCELLATION_MILLIS)}"
        )
        assertTrue(result.getLong(RESULT_RESPONSE_BODY_MILLIS) >= 0L)
        assertTrue(result.getBoolean(RESULT_CANCELLED))
        assertTrue(result.getLong(RESULT_CANCELLATION_MILLIS) < MAX_CANCELLATION_MILLIS)
    }

    @Test
    fun exportsOneThousandEntries() = assertExport(entryCount = 1_000)

    @Test
    fun exportsTenThousandEntries() = assertExport(entryCount = 10_000)

    private fun assertExport(entryCount: Int) {
        resetTarget()
        val result = call(
            METHOD_EXPORT,
            Bundle().apply { putInt(ARG_ENTRY_COUNT, entryCount) }
        )

        assertEquals(entryCount, result.getInt(RESULT_ENTRY_COUNT))
        assertTrue(result.getInt(RESULT_CSV_BYTES) > 0)
        assertTrue(result.getInt(RESULT_BACKUP_BYTES) > 0)
        assertTrue(result.getLong(RESULT_CSV_MILLIS) >= 0L)
        assertTrue(result.getLong(RESULT_BACKUP_MILLIS) >= 0L)
        println(
            "BATCH9_EXPORT entries=$entryCount csvMs=${result.getLong(RESULT_CSV_MILLIS)} " +
                "backupMs=${result.getLong(RESULT_BACKUP_MILLIS)} " +
                "csvBytes=${result.getInt(RESULT_CSV_BYTES)} " +
                "backupBytes=${result.getInt(RESULT_BACKUP_BYTES)}"
        )
    }

    private fun resetTarget() {
        check(device.executeShellCommand("pm clear $TARGET_PACKAGE").contains("Success"))
        val launchResult = device.executeShellCommand(
            "am start -W -n $TARGET_PACKAGE/com.bks.MainActivity"
        )
        check(launchResult.contains("Status: ok")) { launchResult }
    }

    private fun call(method: String, extras: Bundle = Bundle()): Bundle =
        requireNotNull(
            InstrumentationRegistry.getInstrumentation().context.contentResolver.call(
                DATA_PROVIDER_URI,
                method,
                null,
                extras
            )
        )

    private companion object {
        val DATA_PROVIDER_URI: Uri = Uri.parse("content://$TARGET_PACKAGE.benchmark-data")
        const val METHOD_SEED_SIGNED_IN_SESSION = "seed_signed_in_session"
        const val METHOD_EXPORT = "export"
        const val METHOD_NETWORK = "network"
        const val RESULT_SESSION_SAVED = "session_saved"
        const val RESULT_ENTRY_COUNT = "entry_count"
        const val RESULT_CSV_BYTES = "csv_bytes"
        const val RESULT_BACKUP_BYTES = "backup_bytes"
        const val RESULT_CSV_MILLIS = "csv_millis"
        const val RESULT_BACKUP_MILLIS = "backup_millis"
        const val RESULT_RESPONSE_HEADERS_MILLIS = "response_headers_millis"
        const val RESULT_RESPONSE_BODY_MILLIS = "response_body_millis"
        const val RESULT_COMPLETE_MILLIS = "complete_millis"
        const val RESULT_CANCELLATION_MILLIS = "cancellation_millis"
        const val RESULT_CANCELLED = "cancelled"
        const val ARG_ENTRY_COUNT = "entry_count"
        const val ARG_ENDPOINT = "endpoint"
        const val LOOPBACK_ENDPOINT = "http://127.0.0.1:8091"
        const val MAX_CANCELLATION_MILLIS = 2_000L
        const val TIMEOUT_MILLIS = 10_000L
    }
}
