package com.autoaccounting.feature.capture

import android.app.Notification
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PaymentNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val database by lazy { AutoAccountingDatabaseProvider.get(this) }
    private val preferencesRepository by lazy { LocalPreferencesRepository(database) }
    private val processor by lazy {
        PaymentNotificationCaptureProcessor(
            pipeline = NotificationCapturePipeline(
                captureTimeFormatter = ::formatCaptureTime
            ),
            reviewQueuePersistence = ReviewQueuePersistence(
                LocalLedgerRepository(database)
            ),
            preferencesRepository = preferencesRepository,
            diagnosticRecorder = diagnostics
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
                diagnostics.record(
                    notificationServiceEvent(event, traceId, isReplay, "disabled", "blocked")
                )
                return@launch
            }
            diagnostics.record(notificationServiceEvent(event, traceId, isReplay, "accepted", "started"))
            runCatching { processor.processWithResult(event, traceId) }
                .onSuccess { result ->
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
    val text = (
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
        )?.toString().orEmpty()
    return PaymentNotificationEvent(
        packageName = packageName,
        title = title,
        text = text,
        postedAtEpochMillis = postTime
    )
}

private fun formatCaptureTime(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
