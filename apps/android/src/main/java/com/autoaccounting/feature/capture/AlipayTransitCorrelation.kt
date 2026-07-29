package com.autoaccounting.feature.capture

import android.content.Context
import com.autoaccounting.feature.review.ReviewQueueEntry
import kotlin.math.abs

const val ALIPAY_METRO_TITLE = "地铁乘车"
internal const val ALIPAY_TRANSIT_CORRELATION_WINDOW_MILLIS = 5 * 60_000L

interface AlipayTransitContextStore {
    fun record(detectedAtEpochMillis: Long)

    fun consumeForNotification(postedAtEpochMillis: Long): Boolean

    fun clear()

    companion object {
        val None: AlipayTransitContextStore = object : AlipayTransitContextStore {
            override fun record(detectedAtEpochMillis: Long) = Unit

            override fun consumeForNotification(postedAtEpochMillis: Long): Boolean = false

            override fun clear() = Unit
        }
    }
}

class SharedPreferencesAlipayTransitContextStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME
) : AlipayTransitContextStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE
    )

    @Synchronized
    override fun record(detectedAtEpochMillis: Long) {
        val existing = preferences.getLong(KEY_DETECTED_AT_EPOCH_MILLIS, MISSING_TIMESTAMP)
        if (
            existing != MISSING_TIMESTAMP &&
            detectedAtEpochMillis >= existing &&
            detectedAtEpochMillis - existing < ALIPAY_TRANSIT_CORRELATION_WINDOW_MILLIS
        ) {
            return
        }
        preferences.edit()
            .putLong(KEY_DETECTED_AT_EPOCH_MILLIS, detectedAtEpochMillis)
            .putBoolean(KEY_CONSUMED, false)
            .apply()
    }

    @Synchronized
    override fun consumeForNotification(postedAtEpochMillis: Long): Boolean {
        val detectedAtEpochMillis = preferences.getLong(
            KEY_DETECTED_AT_EPOCH_MILLIS,
            MISSING_TIMESTAMP
        )
        if (detectedAtEpochMillis == MISSING_TIMESTAMP) return false
        if (preferences.getBoolean(KEY_CONSUMED, false)) return false
        val ageMillis = postedAtEpochMillis - detectedAtEpochMillis
        if (ageMillis < 0) return false
        if (ageMillis >= ALIPAY_TRANSIT_CORRELATION_WINDOW_MILLIS) {
            clear()
            return false
        }
        preferences.edit().putBoolean(KEY_CONSUMED, true).apply()
        return true
    }

    @Synchronized
    override fun clear() {
        preferences.edit()
            .remove(KEY_DETECTED_AT_EPOCH_MILLIS)
            .remove(KEY_CONSUMED)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "alipay_transit_capture_context"
        const val KEY_DETECTED_AT_EPOCH_MILLIS = "detected_at_epoch_millis"
        const val KEY_CONSUMED = "consumed"
        const val MISSING_TIMESTAMP = Long.MIN_VALUE
    }
}

internal fun ReviewQueueEntry.isGenericAlipayExpenseNotification(): Boolean =
    sourceLabel == "支付宝" &&
        kindLabel == "支出" &&
        captureReasonLabel == "通知捕获" &&
        normalizedTransitCandidateTitle in GENERIC_ALIPAY_TITLES

internal fun ReviewQueueEntry.withAlipayMetroContext(): ReviewQueueEntry {
    val fieldsWithoutFallbackMerchant = parsedFields.filterNot { field ->
        field.startsWith("商户=")
    }
    return copy(
        title = ALIPAY_METRO_TITLE,
        category = "",
        parsedFields = (
            fieldsWithoutFallbackMerchant +
                listOf(
                    "商户=$ALIPAY_METRO_TITLE",
                    "场景证据=支付宝乘车已出站",
                    "证据来源=支付宝乘车出站页面"
                )
            ).distinct()
    )
}

internal fun isWithinAlipayTransitCorrelationWindow(
    firstEpochMillis: Long,
    secondEpochMillis: Long
): Boolean = abs(firstEpochMillis - secondEpochMillis) <
    ALIPAY_TRANSIT_CORRELATION_WINDOW_MILLIS

private val ReviewQueueEntry.normalizedTransitCandidateTitle: String
    get() = title.trim().lowercase()

private val GENERIC_ALIPAY_TITLES = setOf("未知来源", "支付宝支付")
