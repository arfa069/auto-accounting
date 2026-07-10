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

private fun normalizeOcrAmountLine(text: String): String? {
    val match = OCR_AMOUNT_LINE_REGEX.matchEntire(text.trim()) ?: return null
    return "¥${match.groupValues[1]}"
}

private fun Int?.orZero(): Int = this ?: 0

private const val MINIMUM_AMOUNT_HEIGHT_RATIO = 0.018
private const val MINIMUM_PROMINENCE_RATIO = 1.5
private val OCR_AMOUNT_LINE_REGEX = Regex(
    pattern = """(?:[¥￥Yy]\s*)?(\d+(?:\.\d{1,2})?)"""
)
