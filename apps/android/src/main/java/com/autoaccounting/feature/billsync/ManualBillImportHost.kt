package com.autoaccounting.feature.billsync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.autoaccounting.feature.diagnostics.DiagnosticComponent
import com.autoaccounting.feature.diagnostics.DiagnosticEvent
import com.autoaccounting.feature.diagnostics.DiagnosticEventMetadata
import com.autoaccounting.feature.diagnostics.DiagnosticLevel
import com.autoaccounting.feature.diagnostics.DiagnosticRecorder
import com.autoaccounting.feature.diagnostics.DiagnosticSource
import com.autoaccounting.feature.diagnostics.NoOpDiagnosticRecorder
import com.autoaccounting.feature.diagnostics.newDiagnosticTraceId
import kotlinx.coroutines.delay

const val MANUAL_BILL_IMPORT_TIMEOUT_MILLIS = 90_000L

@Composable
@Suppress("LongParameterList")
fun ManualBillImportHost(
    openRequestId: Long,
    accessibilityAccessGranted: Boolean,
    accessibilityServiceConnected: Boolean,
    onOpenAccessibilitySettings: () -> Unit = {},
    onLaunchSource: (BillSyncSource) -> Boolean = { false },
    onNavigateToReview: () -> Unit = {},
    sessionController: BillSyncSessionController = BillSyncSessions.controller,
    waitingTimeoutMillis: Long = MANUAL_BILL_IMPORT_TIMEOUT_MILLIS,
    diagnosticRecorder: DiagnosticRecorder = NoOpDiagnosticRecorder
) {
    var dialogOpen by remember { mutableStateOf(false) }
    var handledOpenRequestId by remember { mutableLongStateOf(0L) }
    var precheckFailure by remember {
        mutableStateOf<ManualBillImportPrecheckFailure?>(null)
    }
    val sessionState by sessionController.state.collectAsState()

    fun currentPrecheckFailure(): ManualBillImportPrecheckFailure? =
        precheckFailureFor(accessibilityAccessGranted, accessibilityServiceConnected)

    fun retry() {
        sessionController.reset()
        precheckFailure = currentPrecheckFailure()
    }

    LaunchedEffect(openRequestId) {
        if (openRequestId <= 0L || openRequestId == handledOpenRequestId) {
            return@LaunchedEffect
        }
        handledOpenRequestId = openRequestId
        retry()
        dialogOpen = true
        diagnosticRecorder.recordPrecheck(precheckFailure)
    }

    LaunchedEffect(sessionState.sessionId, sessionState.phase) {
        val mapping = sessionDiagnosticMapping(sessionState.phase, sessionState)
            ?: return@LaunchedEffect
        diagnosticRecorder.recordSessionEvent(mapping, sessionState)
    }

    LaunchedEffect(sessionState.sessionId, sessionState.phase, waitingTimeoutMillis) {
        if (sessionState.phase != BillSyncSessionPhase.AwaitingBillPage) {
            return@LaunchedEffect
        }
        val waitingSessionId = sessionState.sessionId
        delay(waitingTimeoutMillis)
        sessionController.timeoutAwaitingBillPage(waitingSessionId)
    }

    if (!dialogOpen) return

    ManualBillImportDialog(
        precheckFailure = precheckFailure,
        sessionState = sessionState,
        actions = ManualBillImportDialogActions(
            onSourceSelected = { source ->
                val failure = currentPrecheckFailure()
                if (failure != null) {
                    precheckFailure = failure
                } else {
                    startManualBillSync(
                        source = source,
                        manualOcrAllowed = true,
                        launchSource = onLaunchSource,
                        controller = sessionController
                    )
                }
            },
            onDismiss = {
                if (sessionState.isActive) sessionController.cancel() else dialogOpen = false
            },
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            onNavigateToReview = {
                dialogOpen = false
                onNavigateToReview()
            },
            onRetry = ::retry,
            onRecheck = { precheckFailure = currentPrecheckFailure() }
        ),
        secondaryActions = ManualBillImportDialogSecondaryActions(
            onClose = { dialogOpen = false }
        )
    )
}

private fun precheckFailureFor(
    accessibilityAccessGranted: Boolean,
    accessibilityServiceConnected: Boolean
): ManualBillImportPrecheckFailure? = when {
    !accessibilityAccessGranted -> ManualBillImportPrecheckFailure.PermissionMissing
    !accessibilityServiceConnected -> ManualBillImportPrecheckFailure.ServiceDisconnected
    else -> null
}

private fun DiagnosticRecorder.recordPrecheck(precheckFailure: ManualBillImportPrecheckFailure?) {
    record(
        DiagnosticEvent(
            metadata = DiagnosticEventMetadata(
                level = if (precheckFailure == null) DiagnosticLevel.Info else DiagnosticLevel.Warning,
                component = DiagnosticComponent.ManualImport,
                event = "manual_import_precheck",
                traceId = newDiagnosticTraceId(),
                source = DiagnosticSource.System,
                outcome = if (precheckFailure == null) "success" else "blocked",
                reason = precheckFailure?.name ?: "ready"
            )
        )
    )
}

private fun DiagnosticRecorder.recordSessionEvent(
    mapping: Triple<String, String, String>,
    sessionState: BillSyncSessionState
) {
    val source = when (sessionState.source) {
        BillSyncSource.WeChat -> DiagnosticSource.WeChat
        BillSyncSource.Alipay -> DiagnosticSource.Alipay
        null -> DiagnosticSource.Unknown
    }
    record(
        DiagnosticEvent(
            metadata = DiagnosticEventMetadata(
                level = if (sessionState.phase == BillSyncSessionPhase.Failed) {
                    DiagnosticLevel.Warning
                } else {
                    DiagnosticLevel.Info
                },
                component = DiagnosticComponent.ManualImport,
                event = mapping.first,
                traceId = newDiagnosticTraceId(),
                sessionId = sessionState.sessionId.toString(),
                source = source,
                outcome = mapping.second,
                reason = mapping.third,
                count = sessionState.result?.createdEntries?.size
            )
        )
    )
}

private fun sessionDiagnosticMapping(
    phase: BillSyncSessionPhase,
    sessionState: BillSyncSessionState
): Triple<String, String, String>? = when (phase) {
    BillSyncSessionPhase.AwaitingBillPage -> Triple(
        "manual_import_session_started",
        "started",
        "source_launched"
    )
    BillSyncSessionPhase.Processing -> Triple(
        "manual_import_processing",
        "started",
        "bill_page_submitted"
    )
    BillSyncSessionPhase.Completed -> Triple(
        "manual_import_completed",
        "success",
        "completed"
    )
    BillSyncSessionPhase.Cancelled -> Triple(
        "manual_import_cancelled",
        "cancelled",
        "user_cancelled"
    )
    BillSyncSessionPhase.Failed -> Triple(
        "manual_import_failed",
        "failed",
        sessionState.result?.failureReason?.name ?: "timeout_or_launch_failure"
    )
    BillSyncSessionPhase.Idle -> null
}
