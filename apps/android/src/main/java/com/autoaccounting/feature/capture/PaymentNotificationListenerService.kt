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

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val event = sbn.toPaymentNotificationEvent()
        serviceScope.launch {
            runCatching { processor.process(event) }
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
