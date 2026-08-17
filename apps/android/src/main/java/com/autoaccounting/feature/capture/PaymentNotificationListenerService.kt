package com.autoaccounting.feature.capture

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.autoaccounting.data.local.AutoAccountingDatabaseProvider
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.diagnostics.DiagnosticComponent
import com.autoaccounting.feature.diagnostics.DiagnosticEvent
import com.autoaccounting.feature.diagnostics.DiagnosticEventMetadata
import com.autoaccounting.feature.diagnostics.DiagnosticLevel
import com.autoaccounting.feature.diagnostics.DiagnosticLogs
import com.autoaccounting.feature.diagnostics.DiagnosticSensitiveField
import com.autoaccounting.feature.diagnostics.DiagnosticSensitivePayload
import com.autoaccounting.feature.diagnostics.DiagnosticSource
import com.autoaccounting.feature.diagnostics.newDiagnosticTraceId
import com.autoaccounting.feature.diagnostics.toDiagnosticExceptionDetails
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import com.autoaccounting.feature.review.ReviewQueuePersistence
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class PaymentNotificationCaptureTrigger(
    val packageName: String,
    val captureId: String,
    val amountMinor: Long,
    val notificationTimeEpochMillis: Long,
    val rawNotificationEvidence: String,
    val publishedAtEpochMillis: Long = System.currentTimeMillis()
)

internal object PaymentNotificationCaptureTriggers {
    private val mutableEvents = MutableSharedFlow<PaymentNotificationCaptureTrigger>(
        replay = 1,
        extraBufferCapacity = 8
    )
    val events = mutableEvents.asSharedFlow()
    private val captures = linkedMapOf<String, PendingNotificationCapture>()

    @Synchronized
    fun publish(trigger: PaymentNotificationCaptureTrigger) {
        captures[trigger.captureId] = PendingNotificationCapture(trigger)
        mutableEvents.tryEmit(trigger)
    }

    @Synchronized
    fun pendingFor(
        packageName: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): PaymentNotificationCaptureTrigger? {
        discardExpired(nowEpochMillis)
        return captures.values.lastOrNull { capture ->
            capture.state == NotificationCaptureState.Pending &&
                capture.trigger.packageName == packageName
        }?.trigger
    }

    @Synchronized
    fun tryClaimFusion(captureId: String): Boolean = (captures[captureId]
        ?.takeIf { it.state == NotificationCaptureState.Pending }
        ?.also { it.state = NotificationCaptureState.Fusion } != null)

    @Synchronized
    fun releaseFusion(captureId: String) {
        captures[captureId]
            ?.takeIf { it.state == NotificationCaptureState.Fusion }
            ?.state = NotificationCaptureState.Pending
    }

    @Synchronized
    fun tryClaimFallback(captureId: String): Boolean = (captures[captureId]
        ?.takeIf { it.state == NotificationCaptureState.Pending }
        ?.also { it.state = NotificationCaptureState.Fallback } != null)

    suspend fun awaitFallbackClaim(captureId: String): Boolean {
        repeat(FUSION_SETTLEMENT_GRACE_STEPS) {
            if (tryClaimFallback(captureId)) return true
            if (!isTracked(captureId)) return false
            delay(FUSION_SETTLEMENT_GRACE_STEP_MILLIS)
        }
        return false
    }

    @Synchronized
    fun complete(captureId: String) {
        captures.remove(captureId)
    }

    @Synchronized
    private fun isTracked(captureId: String): Boolean = captures.containsKey(captureId)

    suspend fun awaitPendingFor(
        packageName: String,
        waitMillis: Long = PAYMENT_EVIDENCE_COLLECTION_WINDOW_MILLIS
    ): PaymentNotificationCaptureTrigger? = pendingFor(packageName) ?: withTimeoutOrNull(waitMillis) {
        events.filter { trigger ->
            trigger.packageName == packageName &&
                pendingFor(packageName)?.captureId == trigger.captureId
        }.first()
    }?.let { pendingFor(packageName) }

    @Synchronized
    private fun discardExpired(nowEpochMillis: Long) {
        captures.entries.removeAll { (_, capture) ->
            nowEpochMillis - capture.trigger.publishedAtEpochMillis !in
                0..PAYMENT_NOTIFICATION_TRIGGER_TTL_MILLIS
        }
    }
}

private data class PendingNotificationCapture(
    val trigger: PaymentNotificationCaptureTrigger,
    var state: NotificationCaptureState = NotificationCaptureState.Pending
)

private enum class NotificationCaptureState { Pending, Fusion, Fallback }

internal const val PAYMENT_EVIDENCE_COLLECTION_WINDOW_MILLIS = 10_000L
internal const val PAYMENT_NOTIFICATION_TRIGGER_TTL_MILLIS = 20_000L
private const val FUSION_SETTLEMENT_GRACE_STEP_MILLIS = 250L
private const val FUSION_SETTLEMENT_GRACE_STEPS = 20

class PaymentNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val database by lazy { AutoAccountingDatabaseProvider.get(this) }
    private val preferencesRepository by lazy { LocalPreferencesRepository(database) }
    private val alipayTransitContextStore by lazy {
        SharedPreferencesAlipayTransitContextStore(this)
    }
    private val notificationPipeline by lazy {
        NotificationCapturePipeline(captureTimeFormatter = ::formatCaptureTime)
    }
    private val processor by lazy {
        PaymentNotificationCaptureProcessor(
            pipeline = notificationPipeline,
            reviewQueuePersistence = ReviewQueuePersistence(
                LocalLedgerRepository(database)
            ),
            preferencesRepository = preferencesRepository,
            diagnosticRecorder = diagnostics,
            alipayTransitContextStore = alipayTransitContextStore
        )
    }
    private val resultNotifier by lazy { BookkeepingResultNotifier(this) }
    private val diagnostics by lazy { DiagnosticLogs.get(this) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        diagnostics.record(notificationListenerLifecycleEvent("listener_connected", "connected"))
        val nowEpochMillis = System.currentTimeMillis()
        activeNotifications
            .orEmpty()
            .filter { notification ->
                shouldReplayActivePaymentNotification(
                    packageName = notification.packageName,
                    postedAtEpochMillis = notification.postTime,
                    nowEpochMillis = nowEpochMillis
                )
            }
            .forEach { notification ->
                processNotification(notification, isReplay = true)
            }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        processNotification(sbn, isReplay = false)
    }

    private fun processNotification(
        sbn: StatusBarNotification,
        isReplay: Boolean
    ) {
        val event = sbn.toPaymentNotificationEvent()
        val traceId = newDiagnosticTraceId()
        serviceScope.launch {
            val automaticBookkeepingEnabled = preferencesRepository.userPreferences
                .first()
                .continuousMonitoringState
                .let(::isAutomaticBookkeepingNotificationCaptureEnabled)
            if (!automaticBookkeepingEnabled) {
                alipayTransitContextStore.clear()
                diagnostics.record(
                    notificationServiceEvent(event, traceId, isReplay, "disabled", "blocked")
                )
                return@launch
            }
            diagnostics.record(notificationServiceEvent(event, traceId, isReplay, "accepted", "started"))
            val trigger = notificationPipeline.capture(event)
                ?.takeUnless { isReplay }
                ?.let { pending ->
                    PaymentNotificationCaptureTrigger(
                        packageName = event.packageName,
                        captureId = pending.id,
                        amountMinor = pending.amountMinor,
                        notificationTimeEpochMillis = event.postedAtEpochMillis,
                        rawNotificationEvidence = pending.rawEvidenceText
                    )
                }
            if (trigger != null) {
                PaymentNotificationCaptureTriggers.publish(trigger)
                delay(PAYMENT_EVIDENCE_COLLECTION_WINDOW_MILLIS)
                if (!PaymentNotificationCaptureTriggers.awaitFallbackClaim(trigger.captureId)) {
                    return@launch
                }
            }
            runCatching { processor.processWithResult(event, traceId) }
                .onSuccess { result ->
                    trigger?.let { PaymentNotificationCaptureTriggers.complete(it.captureId) }
                    result?.notification
                        ?.takeIf { notification ->
                            !isReplay || notification is BookkeepingResultNotification.PendingCreated
                        }
                        ?.let { notification ->
                            diagnostics.record(
                                DiagnosticEvent(
                                    metadata = DiagnosticEventMetadata(
                                        level = DiagnosticLevel.Info,
                                        component = DiagnosticComponent.NotificationService,
                                        event = "result_notification_requested",
                                        traceId = traceId,
                                        source = when (event.packageName) {
                                            "com.tencent.mm" -> DiagnosticSource.WeChat
                                            "com.eg.android.AlipayGphone" -> DiagnosticSource.Alipay
                                            else -> DiagnosticSource.Unknown
                                        },
                                        outcome = "requested",
                                        reason = notification.javaClass.simpleName
                                            .ifBlank { "bookkeeping_result" }
                                    )
                                )
                            )
                            resultNotifier.notify(notification)
                        }
                }
                .onFailure { error ->
                    trigger?.let { PaymentNotificationCaptureTriggers.complete(it.captureId) }
                    val isPaymentRelated = runCatching {
                        PaymentNotificationParser().parseDetailed(event).isPaymentRelated
                    }.getOrDefault(false)
                    diagnostics.record(
                        notificationServiceEvent(
                            event = event,
                            traceId = traceId,
                            isReplay = isReplay,
                            reason = if (isPaymentRelated) {
                                "processor_exception"
                            } else {
                                "non_payment_processor_exception"
                            },
                            outcome = "failed",
                            exception = error.takeIf { isPaymentRelated }
                        )
                    )
                }
        }
    }

    override fun onDestroy() {
        diagnostics.record(notificationListenerLifecycleEvent("listener_destroyed", "stopped"))
        serviceScope.cancel()
        super.onDestroy()
    }

}

private fun notificationServiceEvent(
    event: PaymentNotificationEvent,
    traceId: String,
    isReplay: Boolean,
    reason: String,
    outcome: String,
    exception: Throwable? = null
): DiagnosticEvent = DiagnosticEvent(
    metadata = DiagnosticEventMetadata(
        level = if (exception == null) DiagnosticLevel.Info else DiagnosticLevel.Error,
        component = DiagnosticComponent.NotificationService,
        event = if (isReplay) "notification_replay" else "notification_received",
        traceId = traceId,
        source = when (event.packageName) {
            "com.tencent.mm" -> DiagnosticSource.WeChat
            "com.eg.android.AlipayGphone" -> DiagnosticSource.Alipay
            else -> DiagnosticSource.Unknown
        },
        outcome = outcome,
        reason = reason
    ),
    sensitivePayload = exception?.let {
        DiagnosticSensitivePayload(
            mapOf(DiagnosticSensitiveField.ExceptionDetails to it.toDiagnosticExceptionDetails())
        )
    } ?: DiagnosticSensitivePayload()
)

private fun notificationListenerLifecycleEvent(event: String, outcome: String): DiagnosticEvent =
    DiagnosticEvent(
        metadata = DiagnosticEventMetadata(
            level = DiagnosticLevel.Info,
            component = DiagnosticComponent.NotificationService,
            event = event,
            source = DiagnosticSource.System,
            outcome = outcome,
            reason = event
        )
    )

internal fun shouldReplayActivePaymentNotification(
    packageName: String,
    postedAtEpochMillis: Long,
    nowEpochMillis: Long,
    replayWindowMillis: Long = ACTIVE_NOTIFICATION_REPLAY_WINDOW_MILLIS
): Boolean {
    val ageMillis = nowEpochMillis - postedAtEpochMillis
    return packageName in PAYMENT_NOTIFICATION_PACKAGES && ageMillis in 0..replayWindowMillis
}

private val PAYMENT_NOTIFICATION_PACKAGES = setOf(
    "com.tencent.mm",
    "com.eg.android.AlipayGphone"
)

private const val ACTIVE_NOTIFICATION_REPLAY_WINDOW_MILLIS = 60L * 60L * 1000L

internal fun isAutomaticBookkeepingNotificationCaptureEnabled(
    state: ContinuousMonitoringState
): Boolean = state.enabled

private fun StatusBarNotification.toPaymentNotificationEvent(): PaymentNotificationEvent {
    val extras = notification.extras
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    return PaymentNotificationEvent(
        packageName = packageName,
        title = title,
        text = extractNotificationText(extras),
        postedAtEpochMillis = postTime
    )
}

internal fun extractNotificationText(extras: Bundle): String = buildList {
    extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.let(::add)
        ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let(::add)
    extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        .orEmpty()
        .map(CharSequence::toString)
        .forEach(::add)
}.filter(String::isNotBlank).distinct().joinToString("\n")

private val captureTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatCaptureTime(epochMillis: Long): String =
    captureTimeFormatter
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
