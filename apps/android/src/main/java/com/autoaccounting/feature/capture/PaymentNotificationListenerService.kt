package com.autoaccounting.feature.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.autoaccounting.data.local.AutoAccountingDatabaseProvider
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.review.ReviewQueuePersistence
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PaymentNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val processor by lazy {
        val database = AutoAccountingDatabaseProvider.get(this)
        PaymentNotificationCaptureProcessor(
            pipeline = NotificationCapturePipeline(
                captureTimeFormatter = ::formatCaptureTime
            ),
            reviewQueuePersistence = ReviewQueuePersistence(
                LocalLedgerRepository(database)
            ),
            preferencesRepository = LocalPreferencesRepository(database)
        )
    }
    private val resultNotifier by lazy { BookkeepingResultNotifier(this) }

    override fun onListenerConnected() {
        super.onListenerConnected()
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
        serviceScope.launch {
            runCatching { processor.processWithResult(event) }
                .onSuccess { result ->
                    result?.notification
                        ?.takeIf { notification ->
                            !isReplay || notification is BookkeepingResultNotification.PendingCreated
                        }
                        ?.let(resultNotifier::notify)
                }
                .onFailure { error ->
                    Log.w(TAG, "Payment notification capture failed", error)
                }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PaymentNotification"
    }
}

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
