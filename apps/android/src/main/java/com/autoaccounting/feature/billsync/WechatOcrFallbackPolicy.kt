package com.autoaccounting.feature.billsync

import android.os.Build
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.autoaccounting.feature.monitoring.hasOnlyGenericWechatAccessibilityText
import com.autoaccounting.feature.monitoring.hasWechatMerchantPaymentSuccessSignature
import com.autoaccounting.feature.monitoring.hasWechatTransferCompletionContext

internal fun shouldAttemptWechatOcrFallback(
    packageName: String,
    pageText: String,
    sdkInt: Int,
    windowEvidence: WechatWindowEvidence,
    hasRecentPaymentNotification: Boolean = false
): Boolean = isWechatOcrFallbackCandidate(
    packageName = packageName,
    pageText = pageText,
    sdkInt = sdkInt,
    windowEvidence = windowEvidence
) && (windowEvidence.isApplicationWindow || hasRecentPaymentNotification)

internal fun shouldAttemptManualWechatOcrFallback(
    packageName: String,
    pageText: String,
    sdkInt: Int,
    windowEvidence: WechatWindowEvidence
): Boolean = packageName == BillSyncSource.WeChat.packageName &&
    sdkInt >= Build.VERSION_CODES.R &&
    windowEvidence.isApplicationWindow &&
    (
        hasOnlyGenericWechatAccessibilityText(pageText) ||
            pageText.contains(MANUAL_WECHAT_BILL_SERVICE_KEYWORD)
        )

private const val MANUAL_WECHAT_BILL_SERVICE_KEYWORD = "账单服务"

internal fun isWechatOcrFallbackCandidate(
    packageName: String,
    pageText: String,
    sdkInt: Int,
    windowEvidence: WechatWindowEvidence
): Boolean = packageName == BillSyncSource.WeChat.packageName &&
    sdkInt >= Build.VERSION_CODES.R &&
    isVerifiedWechatOcrResultActivity(windowEvidence.activityClassName) &&
    hasOnlyGenericWechatAccessibilityText(pageText) &&
    !hasWechatMerchantPaymentSuccessSignature(pageText) &&
    !hasWechatTransferCompletionContext(pageText)

internal fun isScreenReadyForWechatOcr(
    screenInteractive: Boolean,
    keyguardLocked: Boolean
): Boolean = screenInteractive && !keyguardLocked

internal data class WechatWindowIdentity(
    val windowId: Int,
    val activityClassName: String
)

internal class PaymentScreenOcrSessionGuard {
    private var processedFingerprint: WechatOcrPaymentFingerprint? = null
    private val processedRedPacketFingerprints =
        linkedSetOf<WechatOcrPaymentFingerprint>()

    @Synchronized
    fun shouldProcess(
        fingerprint: WechatOcrPaymentFingerprint,
        hasNewMatchingNotification: Boolean = false
    ): Boolean =
        (fingerprint.isRedPacket && hasNewMatchingNotification) ||
            (
                fingerprint != processedFingerprint &&
                    fingerprint !in processedRedPacketFingerprints
                )

    @Synchronized
    fun markProcessed(fingerprint: WechatOcrPaymentFingerprint) {
        processedFingerprint = fingerprint
        if (fingerprint.isRedPacket) {
            processedRedPacketFingerprints += fingerprint
            while (processedRedPacketFingerprints.size > MAX_RED_PACKET_FINGERPRINTS) {
                val oldest = processedRedPacketFingerprints.iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                }
            }
        }
    }

    @Synchronized
    fun resetCurrentFingerprint() {
        processedFingerprint = null
    }

    private companion object {
        const val MAX_RED_PACKET_FINGERPRINTS = 64
    }
}

internal fun AccessibilityNodeInfo.collectVisibleText(): String = collectVisibleTextEvidence().text

internal fun AccessibilityNodeInfo.collectVisibleTextEvidence(): PaymentTextEvidence {
    val observations = linkedMapOf<String, PaymentTextObservation>()
    var visitedNodeCount = 0
    var collectedCharacterCount = 0

    fun addLine(value: CharSequence?, bounds: Rect) {
        val line = value?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (collectedCharacterCount + line.length > MAX_VISIBLE_TEXT_CHARACTERS) return
        if (line !in observations) {
            observations[line] = PaymentTextObservation(
                text = line,
                height = bounds.height(),
                left = bounds.left,
                top = bounds.top,
                bottom = bounds.bottom,
                right = bounds.right
            )
            collectedCharacterCount += line.length
        }
    }

    fun collect(node: AccessibilityNodeInfo, depth: Int) {
        if (
            visitedNodeCount >= MAX_VISIBLE_TEXT_NODES ||
            depth > MAX_VISIBLE_TEXT_DEPTH ||
            collectedCharacterCount >= MAX_VISIBLE_TEXT_CHARACTERS
        ) {
            return
        }
        visitedNodeCount += 1
        val bounds = Rect().also(node::getBoundsInScreen)
        addLine(node.text, bounds)
        addLine(node.contentDescription, bounds)
        if (
            depth == MAX_VISIBLE_TEXT_DEPTH ||
            visitedNodeCount >= MAX_VISIBLE_TEXT_NODES ||
            collectedCharacterCount >= MAX_VISIBLE_TEXT_CHARACTERS
        ) {
            return
        }
        repeat(node.childCount) { index ->
            node.getChild(index)?.let { child ->
                collect(child, depth + 1)
            }
        }
    }

    val rootBounds = Rect().also(::getBoundsInScreen)
    collect(this, depth = 0)
    return PaymentTextEvidence(
        text = observations.keys.joinToString("\n"),
        observations = observations.values.toList(),
        imageHeight = rootBounds.height()
    )
}

private const val MAX_VISIBLE_TEXT_NODES = 512
private const val MAX_VISIBLE_TEXT_DEPTH = 24
private const val MAX_VISIBLE_TEXT_CHARACTERS = 16 * 1024
