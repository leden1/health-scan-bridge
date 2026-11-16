package com.healthscan.app.parse

data class NumberHit(
    val text: String,
    val value: Double,
    val altValue: Double?,
    val negative: Boolean,
    val startInLine: Int,
) {
    fun candidates(): List<Double> = if (altValue == null) listOf(value) else listOf(value, altValue)
}

/**
 * Extracts numeric values from OCR text, tolerating both digit grouping styles (1,670.0 and
 * 1.670,0), thin spaces, unicode minus signs and common OCR digit confusions.
 */
object NumberParser {

    private val tokenPattern =
        Regex("[-+\u2212\u2013]?\\d[\\d \u00A0\u2009,.]*\\d|[-+\u2212\u2013]?\\d")

    fun hits(text: String): List<NumberHit> {
        val out = ArrayList<NumberHit>(4)
        for (match in tokenPattern.findAll(text)) {
            val interpreted = interpret(match.value) ?: continue
            out += NumberHit(
                text = match.value.trim(),
                value = interpreted.first,
                altValue = interpreted.second,
                negative = interpreted.third,
                startInLine = match.range.first,
            )
        }
        return out
    }

    /** Parses a single number typed by the user, e.g. "74,0" or "1 670". */
    fun parseSingle(raw: String): Double? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val interpreted = interpret(trimmed) ?: return null
        val body = if (trimmed.first() == '-' || trimmed.first() == '+') trimmed.substring(1) else trimmed
        val consumed = body.replace(" ", "").replace("\u00A0", "").replace("\u2009", "")
        val digitsAndSeparators = consumed.count { it.isDigit() || it == '.' || it == ',' }
        val total = consumed.count { it != ' ' }
        return if (digitsAndSeparators == total) interpreted.first else null
    }

    /** Triple of (primary value, ambiguous alternative, negative). */
    private fun interpret(rawToken: String): Triple<Double, Double?, Boolean>? {
        var token = rawToken.trim()
            .replace('\u00A0', ' ')
            .replace('\u2009', ' ')
            .replace('\u2212', '-')
            .replace('\u2013', '-')
        if (token.isEmpty()) return null

        var negative = false
        when (token.first()) {
            '-' -> {
                negative = true
                token = token.substring(1)
            }
            '+' -> token = token.substring(1)
        }
        val spacedGroups = token.split(" ").filter { it.isNotEmpty() }
        token = token.replace(" ", "")
        if (token.isEmpty()) return null

        if (token.any { !it.isDigit() && it != '.' && it != ',' }) {
            token = token.map { c ->
                when (c) {
                    'O', 'o', 'D', 'Q' -> '0'
                    'l', 'I', 'i', 't' -> '1'
                    'S', 's', 'Z' -> '5'
                    'B' -> '8'
                    'g', 'q' -> '9'
                    else -> c
                }
            }.joinToString("")
        }
        if (token.any { !it.isDigit() && it != '.' && it != ',' }) return null

        val digitsOnly = token.filter { it.isDigit() }
        if (digitsOnly.isEmpty()) return null

        val separators = token
            .mapIndexed { index, c -> if (c == '.' || c == ',') index else -1 }
            .filter { it >= 0 }

        if (separators.isEmpty()) {
            val value = digitsOnly.toDoubleOrNull() ?: return null
            val lostDecimalPoint = spacedFractionalValue(spacedGroups)
            val alternative = lostDecimalPoint?.takeIf { it != value }
            return Triple(if (negative) -value else value, alternative, negative)
        }

        val lastSeparator = separators.last()
        val groupAfterLast = token.length - lastSeparator - 1
        val digitsBeforeLast = token.substring(0, lastSeparator).count { it.isDigit() }

        val looksLikeGrouping = separators.size == 1 && groupAfterLast == 3 && digitsBeforeLast in 1..3
        if (looksLikeGrouping) {
            val grouped = digitsOnly.toDoubleOrNull() ?: return null
            val decimal = (token.substring(0, lastSeparator) + "." + token.substring(lastSeparator + 1))
                .toDoubleOrNull()
            val signedGrouped = if (negative) -grouped else grouped
            val signedDecimal = decimal?.let { if (negative) -it else it }
            return Triple(signedGrouped, signedDecimal, negative)
        }

        val rebuilt = StringBuilder(token.length)
        token.forEachIndexed { index, c ->
            if (c == '.' || c == ',') {
                if (index == lastSeparator) rebuilt.append('.')
            } else {
                rebuilt.append(c)
            }
        }
        val primary = rebuilt.toString().toDoubleOrNull() ?: return null
        val manySeparators = separators.size > 1
        val alternative = if (manySeparators) digitsOnly.toDoubleOrNull() else null
        val signedPrimary = if (negative) -primary else primary
        val signedAlternative = alternative?.let { if (negative) -it else it }
        return Triple(signedPrimary, signedAlternative, negative)
    }

    /**
     * Large scale digits frequently lose their decimal point in OCR, so "74 00" is offered as the
     * alternative reading 74.00. Only a one or two digit trailing group counts, which keeps grouping
     * such as "1 670" out.
     */
    private fun spacedFractionalValue(groups: List<String>): Double? {
        if (groups.size < 2) return null
        if (groups.any { it.isEmpty() || !it.all { ch -> ch.isDigit() } }) return null
        val last = groups.last()
        if (last.length !in 1..2) return null
        val head = groups.dropLast(1).joinToString("")
        if (head.isEmpty()) return null
        return "$head.$last".toDoubleOrNull()
    }
}
