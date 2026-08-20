package com.autoaccounting.feature.billsync

/**
 * 支付成功页常在交易信息下方推送领券位（如「车主用车红包 1元 免费领」）。
 * 券面额本身不带干扰词，只能靠邻近行的营销语境识别，否则会把交易金额判成歧义。
 * 营销文案与 CTA 总在券面额的同行或上方，而交易金额在领券位上方，
 * 所以向上放宽、向下收紧，避免把交易金额误判成券面额。
 */
internal fun isPromotionalAmountLine(lines: List<String>, amountLineIndex: Int): Boolean {
    val line = lines[amountLineIndex].trim()
    if (TRANSACTION_AMOUNT_OVERRIDE_KEYWORDS.any { line.contains(it) }) return false
    if (PROMOTIONAL_AMOUNT_KEYWORDS.any { line.contains(it) }) return true

    val neighbourhood = lines.subList(
        (amountLineIndex - PROMOTION_CONTEXT_LINES_ABOVE).coerceAtLeast(0),
        (amountLineIndex + PROMOTION_CONTEXT_LINES_BELOW + 1).coerceAtMost(lines.size)
    )
    return neighbourhood.any { neighbour ->
        PROMOTIONAL_AMOUNT_KEYWORDS.any { neighbour.contains(it) }
    }
}

/** 领券位的标题行（如「车主用车红包」）不能当作商户，需要在向下找商户时作为边界。 */
internal fun String.isPromotionalContentLine(): Boolean {
    val value = trim()
    if (TRANSACTION_AMOUNT_OVERRIDE_KEYWORDS.any { value.contains(it) }) return false
    return PROMOTIONAL_AMOUNT_KEYWORDS.any { value.contains(it) }
}

private const val PROMOTION_CONTEXT_LINES_ABOVE = 2
private const val PROMOTION_CONTEXT_LINES_BELOW = 1

private val PROMOTIONAL_AMOUNT_KEYWORDS = listOf(
    "免费领",
    "一键领",
    "立即领",
    "去领取",
    "领取",
    "无门槛",
    "立减",
    "支付券",
    "优惠券",
    "代金券",
    "体验金",
    "充值金",
    "看广告",
    "广告"
)
