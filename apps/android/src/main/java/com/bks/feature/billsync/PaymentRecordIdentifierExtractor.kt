package com.bks.feature.billsync

internal fun extractIdentifierAfterLabels(
    lines: List<String>,
    labels: List<String>
): String? {
    for ((index, line) in lines.withIndex()) {
        for (label in labels) {
            val inlineValue = line.valueAfterLabel(label) ?: continue

            val identifierParts = buildList {
                inlineValue.filterNot(Char::isWhitespace)
                    .takeIf(String::isNotBlank)
                    ?.let(::add)
                lines.drop(index + 1)
                    .takeWhile { nextLine ->
                        !nextLine.isKnownFieldLine() &&
                            nextLine.filterNot(Char::isWhitespace).matches(IDENTIFIER_PART_REGEX)
                    }
                    .map { it.filterNot(Char::isWhitespace) }
                    .forEach(::add)
            }
            return identifierParts.joinToString("").takeIf(String::isNotBlank)
        }
    }
    return null
}

private val IDENTIFIER_PART_REGEX = Regex("[A-Za-z0-9]+")
