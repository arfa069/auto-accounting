package com.bks.feature.billsync

internal fun String.extractTransactionTimeText(): String? {
    numericDateTimeRegex.find(this)?.let { match ->
        return formatTransactionTime(
            year = match.groupValues[1],
            month = match.groupValues[2],
            day = match.groupValues[3],
            hour = match.groupValues[4],
            minute = match.groupValues[5]
        )
    }
    chineseDateTimeRegex.find(this)?.let { match ->
        return formatTransactionTime(
            year = match.groupValues[1],
            month = match.groupValues[2],
            day = match.groupValues[3],
            hour = match.groupValues[4],
            minute = match.groupValues[5]
        )
    }
    return null
}

private fun formatTransactionTime(
    year: String,
    month: String,
    day: String,
    hour: String,
    minute: String
): String = "${year.padStart(4, '0')}-${month.padStart(2, '0')}-${day.padStart(2, '0')} " +
    "${hour.padStart(2, '0')}:${minute.padStart(2, '0')}"

private val numericDateTimeRegex = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\s+(\d{1,2}):(\d{2})""")
private val chineseDateTimeRegex = Regex("""(\d{4})年(\d{1,2})月(\d{1,2})日?\s*(\d{1,2}):(\d{2})""")
