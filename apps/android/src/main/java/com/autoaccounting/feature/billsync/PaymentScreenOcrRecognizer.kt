package com.autoaccounting.feature.billsync

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

internal class PaymentScreenOcrRecognizer : Closeable {
    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    suspend fun recognize(bitmap: Bitmap): String = suspendCoroutine { continuation ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val lines = result.textBlocks.flatMap { block -> block.lines }
                val observations = lines.map { line ->
                    OcrLineObservation(
                        text = line.text,
                        height = line.boundingBox?.height().orZero()
                    )
                }
                val amountLineIndex = selectProminentPaymentAmountLine(
                    lines = observations,
                    imageHeight = bitmap.height
                )
                val normalizedText = lines.mapIndexed { index, line ->
                    if (index == amountLineIndex) {
                        normalizeOcrAmountLine(line.text) ?: line.text
                    } else {
                        line.text
                    }
                }.joinToString("\n")
                continuation.resume(normalizedText)
            }
            .addOnFailureListener(continuation::resumeWithException)
    }

    override fun close() {
        recognizer.close()
    }
}

internal data class OcrLineObservation(
    val text: String,
    val height: Int
)

internal fun selectProminentPaymentAmountLine(
    lines: List<OcrLineObservation>,
    imageHeight: Int
): Int? {
    val candidates = lines.mapIndexedNotNull { index, line ->
        if (normalizeOcrAmountLine(line.text) == null) null else index to line.height
    }.sortedByDescending { (_, height) -> height }
    val largest = candidates.firstOrNull() ?: return null
    val minimumHeight = (imageHeight * MINIMUM_AMOUNT_HEIGHT_RATIO).toInt().coerceAtLeast(1)
    if (largest.second < minimumHeight) return null

    val secondLargestHeight = candidates.getOrNull(1)?.second ?: return largest.first
    return largest.first.takeIf {
        largest.second >= secondLargestHeight * MINIMUM_PROMINENCE_RATIO
    }
}

internal fun normalizeOcrAmountLine(text: String): String? {
    val normalizedText = text.trim()
    if (OCR_NON_PAYMENT_AMOUNT_LINE_REGEX.containsMatchIn(normalizedText)) return null
    val match = OCR_AMOUNT_LINE_REGEX.matchEntire(normalizedText)
        ?: OCR_DECIMAL_TOKEN_REGEX.find(normalizedText)
        ?: return null
    val yuan = match.groupValues[1].normalizeOcrDigits()
    val cents = match.groupValues[2].normalizeOcrDigits()
    return if (cents.isBlank()) "¥$yuan" else "¥$yuan.$cents"
}

private fun String.normalizeOcrDigits(): String = replace('O', '0').replace('o', '0')

private fun Int?.orZero(): Int = this ?: 0

private const val MINIMUM_AMOUNT_HEIGHT_RATIO = 0.018
private const val MINIMUM_PROMINENCE_RATIO = 1.5
private val OCR_AMOUNT_LINE_REGEX = Regex(
    pattern = """(?:[¥￥Yy]\s*)?([0-9Oo]+)(?:\s*[.．,，]\s*([0-9Oo]{1,2}))?"""
)
private val OCR_DECIMAL_TOKEN_REGEX = Regex(
    pattern = """([0-9Oo]+)\s*[.．,，]\s*([0-9Oo]{1,2})"""
)
private val OCR_NON_PAYMENT_AMOUNT_LINE_REGEX = Regex(
    pattern = """(?:KB/s|MB/s|GB/s|Mbps|%|网速|电量)""",
    option = RegexOption.IGNORE_CASE
)
