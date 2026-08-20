package com.bks.feature.billsync

internal fun extractP2pTitle(windowText: String): String? {
    val p2pPatterns = listOf(
        Regex("""待(.+?)确认收款"""),
        Regex("""收到(.+?)的红包"""),
        Regex("""(?:^|\n)([^\n]+?)的红包(?:\n|$)"""),
        Regex("""收到(.+?)的转账"""),
        Regex("""([^\s]+?)向你转账"""),
        Regex("""(?:^|\n)转账[-－—]?转给([^\n]+)(?:\n|$)"""),
        Regex("""转账给(.+?)(?:\s|$)"""),
        Regex("""向([^\s]+?)转账"""),
    )
    return p2pPatterns
        .asSequence()
        .mapNotNull { regex -> regex.find(windowText)?.groupValues?.getOrNull(1)?.trim() }
        .firstOrNull { it.isMeaningfulPaymentRecordValue() && it != "你" }
}
