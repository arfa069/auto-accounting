package com.autoaccounting.feature.capture

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.autoaccounting.MainActivity
import com.autoaccounting.feature.billsync.BillSyncResult

private const val RESULT_CHANNEL_ID = "bookkeeping-results"

object BookkeepingResultNotificationPermission {
    const val permission: String = Manifest.permission.POST_NOTIFICATIONS

    fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

fun shouldRequestBookkeepingResultNotificationPermission(
    sdkInt: Int,
    isGranted: Boolean
): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU && !isGranted

sealed interface BookkeepingResultNotification {
    val key: String

    data class PendingCreated(
        override val key: String,
        val count: Int,
        val category: String? = null
    ) : BookkeepingResultNotification

    data class DuplicateMerged(
        override val key: String
    ) : BookkeepingResultNotification

    data class RecognitionFailed(
        override val key: String
    ) : BookkeepingResultNotification
}

fun BillSyncResult.toBookkeepingResultNotification(
    sourceLabel: String
): BookkeepingResultNotification? {
    if (errorMessage != null) {
        return BookkeepingResultNotification.RecognitionFailed("failure-$sourceLabel")
    }
    if (createdEntries.isNotEmpty()) {
        val singleEntry = createdEntries.singleOrNull()
        return BookkeepingResultNotification.PendingCreated(
            key = singleEntry?.id ?: "pending-$sourceLabel",
            count = createdEntries.size,
            category = singleEntry?.category?.takeIf { it.isNotBlank() }
        )
    }
    if (mergedEntries.isNotEmpty() || duplicateSkippedCount > 0) {
        return BookkeepingResultNotification.DuplicateMerged(
            key = mergedEntries.singleOrNull()?.id ?: "duplicate-$sourceLabel"
        )
    }
    return null
}

internal data class BookkeepingNotificationContent(
    val title: String,
    val text: String,
    val publicText: String
)

internal fun BookkeepingResultNotification.content(): BookkeepingNotificationContent = when (this) {
    is BookkeepingResultNotification.PendingCreated -> {
        val privateText = when {
            count > 1 -> "识别到 $count 笔账目，待确认"
            category != null -> "已归类为$category，待确认"
            else -> "识别到 1 笔账目，待确认"
        }
        BookkeepingNotificationContent(
            title = "自动记账",
            text = privateText,
            publicText = "识别到待确认账目"
        )
    }

    is BookkeepingResultNotification.DuplicateMerged -> BookkeepingNotificationContent(
        title = "自动记账",
        text = "已合并重复账目，不会重复入账",
        publicText = "已处理一笔账目"
    )

    is BookkeepingResultNotification.RecognitionFailed -> BookkeepingNotificationContent(
        title = "自动记账",
        text = "支付信息不完整，未创建待确认记录",
        publicText = "有一笔支付信息需要检查"
    )
}

class BookkeepingResultNotifier(
    context: Context
) {
    private val appContext = context.applicationContext
    private val notificationManager =
        appContext.getSystemService(NotificationManager::class.java)

    fun notify(result: BookkeepingResultNotification) {
        if (!BookkeepingResultNotificationPermission.isGranted(appContext)) return
        createChannel()

        val content = result.content()
        val openAppIntent = PendingIntent.getActivity(
            appContext,
            result.key.hashCode(),
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_REVIEW, true)
                if (result is BookkeepingResultNotification.PendingCreated && result.count == 1) {
                    putExtra(MainActivity.EXTRA_PENDING_ENTRY_ID, result.key)
                }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val publicNotification = NotificationCompat.Builder(appContext, RESULT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("自动记账")
            .setContentText(content.publicText)
            .build()
        val notification = NotificationCompat.Builder(appContext, RESULT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .build()

        notificationManager.notify(result.key.hashCode(), notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                RESULT_CHANNEL_ID,
                "记账结果",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示自动识别后的待确认、去重或失败结果"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
        )
    }
}
