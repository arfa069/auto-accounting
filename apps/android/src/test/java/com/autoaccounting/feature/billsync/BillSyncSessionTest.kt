package com.autoaccounting.feature.billsync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillSyncSessionTest {
    @Test
    fun userStartedSyncWaitsForTheSelectedPaymentSourceAfterLaunch() {
        val controller = BillSyncSessionController()
        var launchedSource: BillSyncSource? = null

        startManualBillSync(
            source = BillSyncSource.Alipay,
            controller = controller,
            launchSource = { source ->
                launchedSource = source
                true
            }
        )

        assertEquals(BillSyncSource.Alipay, launchedSource)
        assertEquals(BillSyncSessionPhase.AwaitingBillPage, controller.state.value.phase)
    }

    @Test
    fun launchFailureIsReportedToTheUserStartedSyncSession() {
        val controller = BillSyncSessionController()

        startManualBillSync(
            source = BillSyncSource.WeChat,
            controller = controller,
            launchSource = { false }
        )

        assertEquals(BillSyncSessionPhase.Failed, controller.state.value.phase)
        assertEquals("未找到微信，无法打开账单页面", controller.state.value.message)
    }

    @Test
    fun userStartedSessionMovesThroughProgressToCompletion() = runBlocking {
        val controller = BillSyncSessionController()
        controller.start(BillSyncSource.WeChat)

        assertEquals(BillSyncSessionPhase.AwaitingBillPage, controller.state.value.phase)
        assertEquals(listOf(BillSyncStep.OpenSource), controller.state.value.steps)

        val accepted = controller.submitBillPage(
            packageName = BillSyncSource.WeChat.packageName,
            pageText = "bill page"
        ) { _, _ -> successfulResult() }

        assertTrue(accepted)
        assertEquals(BillSyncSessionPhase.Completed, controller.state.value.phase)
        assertEquals(BillSyncStep.Completed, controller.state.value.steps.last())
    }

    @Test
    fun unrelatedPackageAndCancelledSessionCannotSubmitPage() = runBlocking {
        val controller = BillSyncSessionController()
        controller.start(BillSyncSource.Alipay)

        assertFalse(
            controller.submitBillPage(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "bill page"
            ) { _, _ -> successfulResult() }
        )

        controller.cancel()

        assertEquals(BillSyncSessionPhase.Cancelled, controller.state.value.phase)
        assertFalse(controller.acceptsPackage(BillSyncSource.Alipay.packageName))
    }

    @Test
    fun parsingFailureIsRenderedAsFailedSession() = runBlocking {
        val controller = BillSyncSessionController()
        controller.start(BillSyncSource.WeChat)

        val accepted = controller.submitBillPage(
            packageName = BillSyncSource.WeChat.packageName,
            pageText = "not a bill"
        ) { _, _ ->
            BillSyncResult(
                steps = listOf(
                    BillSyncStep.OpenSource,
                    BillSyncStep.ReadBills,
                    BillSyncStep.Parse,
                    BillSyncStep.Failed
                ),
                createdEntries = emptyList(),
                duplicateSkippedCount = 0,
                summary = "nothing",
                errorMessage = "parse failed"
            )
        }

        assertFalse(accepted)
        assertEquals(BillSyncSessionPhase.Failed, controller.state.value.phase)
        assertEquals("parse failed", controller.state.value.message)
    }

    private fun successfulResult(): BillSyncResult = BillSyncResult(
        steps = listOf(
            BillSyncStep.OpenSource,
            BillSyncStep.ReadBills,
            BillSyncStep.Parse,
            BillSyncStep.Deduplicate,
            BillSyncStep.CreatePendingEntries,
            BillSyncStep.Completed
        ),
        createdEntries = emptyList(),
        duplicateSkippedCount = 0,
        summary = "done"
    )
}
