package com.autoaccounting.feature.billsync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class BillSyncSessionPhase {
    Idle,
    AwaitingBillPage,
    Processing,
    Completed,
    Failed,
    Cancelled
}

data class BillSyncSessionState(
    val sessionId: Long = 0,
    val phase: BillSyncSessionPhase = BillSyncSessionPhase.Idle,
    val source: BillSyncSource? = null,
    val manualOcrAllowed: Boolean = false,
    val steps: List<BillSyncStep> = emptyList(),
    val result: BillSyncResult? = null,
    val message: String? = null
) {
    val isActive: Boolean
        get() = phase == BillSyncSessionPhase.AwaitingBillPage ||
            phase == BillSyncSessionPhase.Processing
}

class BillSyncSessionController {
    private val transitionMutex = Mutex()
    private val mutableState = MutableStateFlow(BillSyncSessionState())
    val state: StateFlow<BillSyncSessionState> = mutableState.asStateFlow()

    fun reset() {
        mutableState.value = BillSyncSessionState(sessionId = mutableState.value.sessionId)
    }

    fun start(
        source: BillSyncSource,
        manualOcrAllowed: Boolean = false
    ): BillSyncSessionState {
        val next = BillSyncSessionState(
            sessionId = mutableState.value.sessionId + 1,
            phase = BillSyncSessionPhase.AwaitingBillPage,
            source = source,
            manualOcrAllowed = manualOcrAllowed,
            steps = listOf(BillSyncStep.OpenSource)
        )
        mutableState.value = next
        return next
    }

    fun cancel() {
        val current = mutableState.value
        if (!current.isActive) return
        mutableState.value = current.copy(
            sessionId = current.sessionId + 1,
            phase = BillSyncSessionPhase.Cancelled,
            steps = current.steps + BillSyncStep.Cancelled,
            message = "补录已取消"
        )
    }

    fun fail(message: String) {
        val current = mutableState.value
        if (!current.isActive) return
        mutableState.value = current.copy(
            phase = BillSyncSessionPhase.Failed,
            steps = current.steps + BillSyncStep.Failed,
            message = message
        )
    }

    suspend fun timeoutAwaitingBillPage(
        sessionId: Long,
        message: String = "未识别到账单页，请重新补录"
    ): Boolean = transitionMutex.withLock {
        val current = mutableState.value
        if (
            current.sessionId != sessionId ||
            current.phase != BillSyncSessionPhase.AwaitingBillPage
        ) {
            return@withLock false
        }
        mutableState.value = current.copy(
            phase = BillSyncSessionPhase.Failed,
            steps = current.steps + BillSyncStep.Failed,
            message = message
        )
        true
    }

    fun acceptsPackage(packageName: String): Boolean {
        val current = mutableState.value
        return current.phase == BillSyncSessionPhase.AwaitingBillPage &&
            current.source?.packageName == packageName
    }

    fun acceptsManualOcr(packageName: String): Boolean {
        val current = mutableState.value
        return current.manualOcrAllowed && acceptsPackage(packageName)
    }

    suspend fun submitBillPage(
        packageName: String,
        pageText: String,
        process: suspend (BillSyncSource, String) -> BillSyncResult
    ): Boolean {
        val processingState = transitionMutex.withLock {
            val current = mutableState.value
            if (
                current.phase != BillSyncSessionPhase.AwaitingBillPage ||
                current.source?.packageName != packageName ||
                pageText.isBlank()
            ) {
                return false
            }
            current.copy(
                phase = BillSyncSessionPhase.Processing,
                steps = listOf(BillSyncStep.OpenSource, BillSyncStep.ReadBills)
            ).also { mutableState.value = it }
        }

        val result = runCatching {
            process(requireNotNull(processingState.source), pageText)
        }
        transitionMutex.withLock {
            if (mutableState.value.sessionId != processingState.sessionId) {
                return false
            }
            result.onSuccess { syncResult ->
                mutableState.value = if (syncResult.errorMessage == null) {
                    processingState.copy(
                        phase = BillSyncSessionPhase.Completed,
                        steps = syncResult.steps,
                        result = syncResult
                    )
                } else {
                    processingState.copy(
                        phase = BillSyncSessionPhase.Failed,
                        steps = syncResult.steps,
                        result = syncResult,
                        message = syncResult.errorMessage
                    )
                }
            }.onFailure { error ->
                mutableState.value = processingState.copy(
                    phase = BillSyncSessionPhase.Failed,
                    steps = processingState.steps + BillSyncStep.Failed,
                    message = error.message ?: "补录失败"
                )
            }
        }
        return result.getOrNull()?.errorMessage == null
    }
}

fun startManualBillSync(
    source: BillSyncSource,
    manualOcrAllowed: Boolean = false,
    launchSource: (BillSyncSource) -> Boolean,
    controller: BillSyncSessionController = BillSyncSessions.controller
) {
    controller.start(source, manualOcrAllowed)
    if (!launchSource(source)) {
        controller.fail("未找到${source.label}，无法打开账单页面")
    }
}

object BillSyncSessions {
    val controller = BillSyncSessionController()
}
