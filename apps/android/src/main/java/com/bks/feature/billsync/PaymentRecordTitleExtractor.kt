package com.bks.feature.billsync

internal fun extractMerchantTitle(
    source: BillSyncSource,
    windowText: String,
    lines: List<String>,
    linesBeforeAmount: List<String>,
    linesAfterAmount: List<String> = emptyList()
): String? {
    if (hasWechatSentRedPacketSuccessSignature(windowText)) return "红包"

    if (source == BillSyncSource.Alipay) {
        extractMerchantOrPayee(windowText, lines)?.let { return it }
    }

    extractMultilineValueAfterLabels(lines, PRODUCT_LABELS)?.let { return it }

    extractP2pTitle(windowText)?.let { return it }

    val fundingAccountValue = extractFundingAccountLabel(windowText, lines)
    titleAroundAmount(linesBeforeAmount, linesAfterAmount, fundingAccountValue)
        ?.let { return it }

    extractMerchantOrPayee(windowText, lines)?.let { return it }

    if (windowText.contains("发出红包") || windowText.contains("红包已发出")) {
        return "红包"
    }

    return null
}

/**
 * 支付成功页的商户可能在大号金额上方（账单详情）或下方（支付结果页）。
 * 先向上就近取，再向下取，并以领券位标题行为边界，避免把营销文案当成商户。
 */
private fun titleAroundAmount(
    linesBeforeAmount: List<String>,
    linesAfterAmount: List<String>,
    fundingAccountValue: String?
): String? {
    linesBeforeAmount
        .asReversed()
        .firstOrNull { it.isMeaningfulPaymentRecordTitle() && it != fundingAccountValue }
        ?.let { return it }

    return linesAfterAmount
        .takeWhile { !it.isPromotionalContentLine() }
        .firstOrNull {
            it.isMeaningfulPaymentRecordTitle() &&
                it != fundingAccountValue &&
                !it.isKnownFieldLine() &&
                !explicitPaymentAmountRegex.containsMatchIn(it)
        }
}
