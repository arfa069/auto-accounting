package com.bks.feature.billsync

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

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

internal fun isScreenReadyForWechatOcr(
    screenInteractive: Boolean,
    keyguardLocked: Boolean
): Boolean = screenInteractive && !keyguardLocked

internal data class WechatWindowEvidence(
    val activityClassName: String?,
    val isApplicationWindow: Boolean
)

internal data class WechatWindowIdentity(
    val windowId: Int,
    val activityClassName: String
)

internal fun AccessibilityNodeInfo.collectVisibleText(): String {
    val lines = linkedSetOf<String>()
    var visitedNodeCount = 0
    var collectedCharacterCount = 0

    fun addLine(value: CharSequence?) {
        val line = value?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (collectedCharacterCount + line.length > MAX_VISIBLE_TEXT_CHARACTERS) return
        if (lines.add(line)) collectedCharacterCount += line.length
    }

    fun collect(node: AccessibilityNodeInfo, depth: Int) {
        if (
            visitedNodeCount >= MAX_VISIBLE_TEXT_NODES ||
            depth > MAX_VISIBLE_TEXT_DEPTH ||
            collectedCharacterCount >= MAX_VISIBLE_TEXT_CHARACTERS
        ) return
        visitedNodeCount += 1
        addLine(node.text)
        addLine(node.contentDescription)
        if (depth == MAX_VISIBLE_TEXT_DEPTH) return
        repeat(node.childCount) { index ->
            node.getChild(index)?.let { child -> collect(child, depth + 1) }
        }
    }

    collect(this, depth = 0)
    return lines.joinToString("\n")
}

private fun hasOnlyGenericWechatAccessibilityText(screenText: String): Boolean =
    screenText.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .all { line -> line in WECHAT_GENERIC_ACCESSIBILITY_LABELS }

private const val MANUAL_WECHAT_BILL_SERVICE_KEYWORD = "账单服务"
private const val MAX_VISIBLE_TEXT_NODES = 512
private const val MAX_VISIBLE_TEXT_DEPTH = 24
private const val MAX_VISIBLE_TEXT_CHARACTERS = 16 * 1024

private val WECHAT_GENERIC_ACCESSIBILITY_LABELS = setOf(
    "返回",
    "返回上一页",
    "关闭",
    "关闭页面",
    "更多",
    "更多操作"
)
