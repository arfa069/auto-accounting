package com.bks.feature.billsync

import com.bks.feature.diagnostics.DiagnosticComponent
import com.bks.feature.diagnostics.DiagnosticEvent
import com.bks.feature.diagnostics.DiagnosticEventMetadata
import com.bks.feature.diagnostics.DiagnosticLevel
import com.bks.feature.diagnostics.DiagnosticRecorder
import com.bks.feature.diagnostics.DiagnosticSensitiveField
import com.bks.feature.diagnostics.DiagnosticSensitivePayload
import com.bks.feature.diagnostics.DiagnosticSource
import com.bks.feature.diagnostics.newDiagnosticTraceId
import com.bks.feature.diagnostics.toDiagnosticExceptionDetails

internal data class BillSyncDiagnosticRecord(
    val event: String,
    val outcome: String,
    val reason: String,
    val traceId: String = newDiagnosticTraceId(),
    val sessionId: Long? = null,
    val source: DiagnosticSource = DiagnosticSource.System,
    val component: DiagnosticComponent = DiagnosticComponent.AccessibilityService,
    val level: DiagnosticLevel = DiagnosticLevel.Info,
    val sensitivePayload: DiagnosticSensitivePayload = DiagnosticSensitivePayload()
)

internal class BillSyncDiagnosticRecorder(
    private val logs: DiagnosticRecorder
) {
    fun record(record: BillSyncDiagnosticRecord) {
        logs.record(
            DiagnosticEvent(
                metadata = DiagnosticEventMetadata(
                    level = record.level,
                    component = record.component,
                    event = record.event,
                    traceId = record.traceId,
                    sessionId = record.sessionId?.toString(),
                    source = record.source,
                    outcome = record.outcome,
                    reason = record.reason
                ),
                sensitivePayload = record.sensitivePayload
            )
        )
    }

    @Suppress("LongParameterList")
    fun recordMetadata(
        event: String,
        outcome: String,
        reason: String,
        traceId: String = newDiagnosticTraceId(),
        sessionId: Long? = null,
        source: DiagnosticSource = DiagnosticSource.System,
        component: DiagnosticComponent = DiagnosticComponent.AccessibilityService,
        sensitivePayload: DiagnosticSensitivePayload = DiagnosticSensitivePayload()
    ) {
        record(
            BillSyncDiagnosticRecord(
                event,
                outcome,
                reason,
                traceId,
                sessionId,
                source,
                component,
                sensitivePayload = sensitivePayload
            )
        )
    }

    fun recordFailure(
        event: String,
        traceId: String,
        source: BillSyncSource,
        sessionId: Long?,
        error: Throwable
    ) {
        record(
            BillSyncDiagnosticRecord(
                event = event,
                outcome = "failed",
                reason = event,
                traceId = traceId,
                sessionId = sessionId,
                source = source.accessibilityDiagnosticSource(),
                level = DiagnosticLevel.Error,
                sensitivePayload = DiagnosticSensitivePayload(
                    mapOf(
                        DiagnosticSensitiveField.ExceptionDetails to
                            error.toDiagnosticExceptionDetails()
                    )
                )
            )
        )
    }

    fun recordPageDecision(
        event: String,
        traceId: String,
        packageName: String,
        reason: String,
        pageText: String?
    ) {
        record(
            BillSyncDiagnosticRecord(
                event = event,
                outcome = "rejected",
                reason = reason,
                traceId = traceId,
                source = packageName.accessibilityDiagnosticSource(),
                sensitivePayload = pageText?.let {
                    DiagnosticSensitivePayload(mapOf(DiagnosticSensitiveField.PageText to it))
                } ?: DiagnosticSensitivePayload()
            )
        )
    }
}
