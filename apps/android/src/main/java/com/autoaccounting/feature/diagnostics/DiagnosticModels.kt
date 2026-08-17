package com.autoaccounting.feature.diagnostics

import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

enum class DiagnosticLevel { Debug, Info, Warning, Error }

enum class DiagnosticComponent {
    Monitoring,
    NotificationService,
    NotificationParser,
    NotificationProcessor,
    AccessibilityService,
    Ocr,
    BillSyncParser,
    BillSyncProcessor,
    ManualImport,
    Persistence,
    Application
}

enum class DiagnosticSource { WeChat, Alipay, System, Unknown }

enum class DiagnosticSensitiveField {
    NotificationText,
    PageText,
    OcrText,
    Amount,
    Merchant,
    Note,
    PaymentAccount,
    PaymentMethod,
    OrderNumber,
    MerchantOrderNumber,
    CaptureEvidence,
    WindowContext,
    ExceptionDetails
}

data class DiagnosticEventMetadata(
    val timestampEpochMillis: Long = System.currentTimeMillis(),
    val level: DiagnosticLevel,
    val component: DiagnosticComponent,
    val event: String,
    val traceId: String = newDiagnosticTraceId(),
    val sessionId: String? = null,
    val source: DiagnosticSource = DiagnosticSource.Unknown,
    val outcome: String? = null,
    val reason: String? = null,
    val suppressedCount: Int = 0,
    val count: Int? = null,
    val durationMillis: Long? = null
)

data class DiagnosticSensitivePayload(
    val fields: Map<DiagnosticSensitiveField, String> = emptyMap()
)

data class DiagnosticEvent(
    val metadata: DiagnosticEventMetadata,
    val sensitivePayload: DiagnosticSensitivePayload = DiagnosticSensitivePayload(),
    val truncatedFields: Set<DiagnosticSensitiveField> = emptySet()
)

data class DiagnosticLogStats(
    val eventCount: Int = 0,
    val encryptedBytes: Long = 0,
    val segmentCount: Int = 0
)

interface DiagnosticRecorder {
    fun record(event: DiagnosticEvent)
}

interface DiagnosticLogRepository : DiagnosticRecorder {
    val enabled: StateFlow<Boolean>
    val events: StateFlow<List<DiagnosticEvent>>
    val stats: StateFlow<DiagnosticLogStats>

    fun setEnabled(enabled: Boolean, userConfirmed: Boolean = false): Boolean
    suspend fun refresh(limit: Int = 1_000)
    suspend fun clear(keepEnabledPreference: Boolean = true)
    suspend fun exportEncrypted(passphrase: CharArray): String
}

object NoOpDiagnosticRecorder : DiagnosticRecorder {
    override fun record(event: DiagnosticEvent) = Unit
}

class InMemoryDiagnosticRecorder(
    var isEnabled: Boolean = true
) : DiagnosticRecorder {
    private val mutableEvents = mutableListOf<DiagnosticEvent>()
    val events: List<DiagnosticEvent> get() = synchronized(mutableEvents) { mutableEvents.toList() }

    override fun record(event: DiagnosticEvent) {
        if (isEnabled) synchronized(mutableEvents) { mutableEvents += sanitizeDiagnosticEvent(event) }
    }
}

fun newDiagnosticTraceId(): String = UUID.randomUUID().toString()

fun Throwable.toDiagnosticExceptionDetails(): String = stackTraceToString()
