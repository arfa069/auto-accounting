package com.autoaccounting.feature.billsync

import com.autoaccounting.feature.capture.PaymentNotificationCaptureTrigger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

internal fun fusePaymentEvidenceText(
    source: BillSyncSource,
    accessibilityEvidence: PaymentTextEvidence?,
    ocrEvidence: PaymentTextEvidence?,
    notificationEvidence: PaymentTextEvidence? = null
): String {
    val evidence = listOfNotNull(accessibilityEvidence, ocrEvidence, notificationEvidence)
        .filter { it.text.isNotBlank() }
    val combinedText = evidence.map(PaymentTextEvidence::text).distinct().joinToString("\n")
    if (combinedText.isBlank()) return ""

    val amount = selectFusedAmount(evidence)
    val fundingAccount = accessibilityEvidence.explicitFundingAccount()
        ?: ocrEvidence.explicitFundingAccount()
        ?: notificationEvidence.explicitFundingAccount()
    val merchant = listOfNotNull(accessibilityEvidence, ocrEvidence, notificationEvidence)
        .firstNotNullOfOrNull(PaymentTextEvidence::explicitMerchant)
        ?: accessibilityEvidence?.visualMerchantNearPrimaryAmount(source, fundingAccount)
        ?: ocrEvidence?.visualMerchantNearPrimaryAmount(source, fundingAccount)
        ?: notificationEvidence?.visualMerchantNearPrimaryAmount(source, fundingAccount)
    val transactionTime = accessibilityEvidence.explicitTransactionTime()
        ?: ocrEvidence.explicitTransactionTime()
        ?: notificationEvidence.explicitTransactionTime()

    return buildList {
        amount?.let { add("金额 $it") }
        merchant?.let { add("商户：$it") }
        fundingAccount?.let { add("交易方式：$it") }
        transactionTime?.let { add("交易时间 $it") }
        add(combinedText)
    }.distinct().joinToString("\n")
}

internal fun PaymentNotificationCaptureTrigger.toPaymentTextEvidence(): PaymentTextEvidence =
    PaymentTextEvidence(
        text = buildString {
            append(rawNotificationEvidence)
            append("\n金额 ¥")
            append(amountMinorToText(amountMinor))
            append("\n交易时间 ")
            append(notificationTimeFormatter.format(Instant.ofEpochMilli(notificationTimeEpochMillis)))
        }
    )

private fun PaymentTextEvidence.explicitMerchant(): String? = text.normalizedLines()
    .let { lines -> extractMerchantOrPayee(lines.joinToString("\n"), lines) }

private fun PaymentTextEvidence?.explicitFundingAccount(): String? = this?.text
    ?.normalizedLines()
    ?.let { lines -> extractFundingAccountLabel(lines.joinToString("\n"), lines) }

private fun PaymentTextEvidence?.explicitTransactionTime(): String? = this?.text
    ?.normalizedLines()
    ?.firstNotNullOfOrNull(String::extractTransactionTimeText)

private fun selectFusedAmount(evidence: List<PaymentTextEvidence>): String? {
    val prominentAmounts = evidence.mapNotNull { item ->
        item.primaryAmountObservation()?.text?.let(::normalizeOcrAmountLine)
            ?: item.text.takeIf(::hasUnambiguousTransactionAmount)
                ?.let(explicitPaymentAmountRegex::find)
                ?.amountText()
                ?.let(::parseAmountMinor)
                ?.let { "¥${amountMinorToText(it)}" }
    }.distinct()
    if (prominentAmounts.size == 1) return prominentAmounts.single()
    return null
}

private fun PaymentTextEvidence.primaryAmountObservation(): PaymentTextObservation? {
    if (observations.isEmpty()) return null
    val standaloneCandidates = observations.filter { it.text.isStandaloneAmountText() }
    val candidates = standaloneCandidates.ifEmpty { observations }
    val index = selectProminentPaymentAmountLine(candidates, imageHeight)
    return index?.let(candidates::get)
}

private fun PaymentTextEvidence.visualMerchantNearPrimaryAmount(
    source: BillSyncSource,
    fundingAccount: String?
): String? {
    val amount = primaryAmountObservation() ?: return null
    val amountCenter = amount.verticalCenter ?: return null
    val maximumDistance = maxOf(amount.height * MAX_MERCHANT_DISTANCE_IN_AMOUNT_HEIGHTS, imageHeight / 6)
    return observations.asSequence()
        .filterNot { it === amount }
        .mapNotNull { observation ->
            val center = observation.verticalCenter ?: return@mapNotNull null
            val value = observation.text.trim()
            val distance = abs(center - amountCenter)
            value.takeIf {
                distance <= maximumDistance &&
                    it.isMeaningfulPaymentRecordTitle() &&
                    normalizeOcrAmountLine(it) == null &&
                    it != source.label &&
                    it != source.genericPaymentTitle &&
                    it != fundingAccount &&
                    PAYMENT_RESULT_ACTIONS.none(value::contains)
            }?.let { distance to it }
        }
        .minByOrNull { it.first }
        ?.second
}

private val PaymentTextObservation.verticalCenter: Int?
    get() = if (top != null && bottom != null) (top + bottom) / 2 else null

private fun String.isStandaloneAmountText(): Boolean = STANDALONE_AMOUNT_REGEX.matches(trim())

private const val MAX_MERCHANT_DISTANCE_IN_AMOUNT_HEIGHTS = 5
private val PAYMENT_RESULT_ACTIONS = listOf("首页", "返回", "完成", "查看账单", "继续")
private val STANDALONE_AMOUNT_REGEX = Regex(
    pattern = """(?:[¥￥Yy]\s*)?[+-]?[0-9Oo]+(?:\s*[.．,，]\s*[0-9Oo]{1,2})?(?:\s*元)?"""
)

private val notificationTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())
