package com.autoaccounting.feature.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class PaymentNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        PaymentNotificationCaptureBus.publish(
            PaymentNotificationEvent(
                packageName = sbn.packageName,
                appName = sbn.packageName,
                title = title,
                text = text,
                postedAtEpochMillis = sbn.postTime
            )
        )
    }
}

object PaymentNotificationCaptureBus {
    private var handler: ((PaymentNotificationEvent) -> Unit)? = null

    fun setHandler(handler: (PaymentNotificationEvent) -> Unit) {
        this.handler = handler
    }

    fun clearHandler() {
        handler = null
    }

    fun publish(event: PaymentNotificationEvent) {
        handler?.invoke(event)
    }
}
