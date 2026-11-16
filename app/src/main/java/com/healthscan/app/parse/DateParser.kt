package com.healthscan.app.parse

import com.healthscan.app.domain.TextNormalizer
import com.healthscan.app.ocr.OcrLine
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class ParsedDateTime(
    val zonedDateTime: ZonedDateTime,
    val raw: String,
    val hasTimeFromImage: Boolean,
    val confidence: Double,
)

object DateParser {

    private val isoDate = Regex("""\b(20\d{2})[-/.](\d{1,2})[-/.](\d{1,2})\b""")
    private val dayFirstDate = Regex("""\b(\d{1,2})[.](\d{1,2})[.](20\d{2}|\d{2})\b""")
    private val slashedDate = Regex("""\b(\d{1,2})/(\d{1,2})/(20\d{2}|\d{2})\b""")
    private val compactDate = Regex("""\b(20\d{2})(\d{2})(\d{2})\b""")
    private val monthNameDate = Regex("""\b(\d{1,2})\.?\s+([a-zčćšžđ]{3,12})\.?\s+(20\d{2})\b""")
    private val monthNameDateReversed = Regex("""\b([a-zčćšžđ]{3,12})\.?\s+(\d{1,2})(?:st|nd|rd|th)?\.?,?\s+(20\d{2})\b""")
    private val timePattern = Regex("""\b(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(am|pm|a\.?m\.?|p\.?m\.?)?\b""")

    private val monthNames = mapOf(
        "jan" to 1, "januar" to 1, "january" to 1, "sijecanj" to 1,
        "feb" to 2, "februar" to 2, "february" to 2, "veljaca" to 2,
        "mar" to 3, "march" to 3, "mart" to 3, "ozujak" to 3,
        "apr" to 4, "april" to 4, "travanj" to 4,
        "may" to 5, "maj" to 5, "svibanj" to 5,
        "jun" to 6, "june" to 6, "lipanj" to 6,
        "jul" to 7, "july" to 7, "srpanj" to 7,
        "aug" to 8, "august" to 8, "kolovoz" to 8,
        "sep" to 9, "sept" to 9, "september" to 9, "rujan" to 9,
        "oct" to 10, "october" to 10, "listopad" to 10,
        "nov" to 11, "november" to 11, "studeni" to 11,
        "dec" to 12, "december" to 12, "prosinac" to 12,
    )

    fun find(lines: List<OcrLine>, zone: ZoneId): ParsedDateTime? {
        val imageHeight = lines.maxOfOrNull { it.bottom } ?: 0
        var best: ParsedDateTime? = null
        for (line in lines) {
            val normalized = TextNormalizer.normalizeLoose(line.text)
            if (normalized.isEmpty()) continue
            val date = parseDateFrom(normalized) ?: continue
            val time = parseTimeFrom(normalized)
            val timeFromNeighbour = time ?: nearestTime(line, lines)
            val confidence = when {
                imageHeight <= 0 -> 0.7
                line.top < imageHeight * 0.35 -> 0.9
                else -> 0.6
            }
            val resolved = ParsedDateTime(
                zonedDateTime = date.atTime(timeFromNeighbour ?: LocalTime.now(zone)).atZone(zone),
                raw = line.text.trim(),
                hasTimeFromImage = timeFromNeighbour != null,
                confidence = if (time != null) confidence else confidence * 0.8,
            )
            if (best == null || betterThan(resolved, best)) best = resolved
        }
        return best
    }

    /** Parses user typed date/time, e.g. "2026-11-14 09:02" or "14.11.2026 9:02". */
    fun parseTyped(text: String, zone: ZoneId): ParsedDateTime? {
        val normalized = TextNormalizer.normalizeLoose(text)
        val date = parseDateFrom(normalized) ?: return null
        val time = parseTimeFrom(normalized) ?: LocalTime.now(zone)
        return ParsedDateTime(
            zonedDateTime = date.atTime(time).atZone(zone),
            raw = text.trim(),
            hasTimeFromImage = parseTimeFrom(normalized) != null,
            confidence = 1.0,
        )
    }

    private fun betterThan(candidate: ParsedDateTime, current: ParsedDateTime): Boolean {
        if (candidate.confidence != current.confidence) return candidate.confidence > current.confidence
        return candidate.raw.length < current.raw.length
    }

    private fun nearestTime(line: OcrLine, lines: List<OcrLine>): LocalTime? {
        val candidates = lines.filter { it !== line && kotlin.math.abs(it.cy - line.cy) < line.height * 3 }
        for (candidate in candidates) {
            parseTimeFrom(TextNormalizer.normalizeLoose(candidate.text))?.let { return it }
        }
        return null
    }

    private fun parseDateFrom(normalized: String): LocalDate? {
        isoDate.find(normalized)?.let { match ->
            buildDate(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            )?.let { return it }
        }
        monthNameDate.find(normalized)?.let { match ->
            val day = match.groupValues[1].toIntOrNull() ?: 0
            val month = monthOf(match.groupValues[2])
            val year = match.groupValues[3].toIntOrNull() ?: 0
            buildDate(year, month, day)?.let { return it }
        }
        monthNameDateReversed.find(normalized)?.let { match ->
            val month = monthOf(match.groupValues[1])
            val day = match.groupValues[2].toIntOrNull() ?: 0
            val year = match.groupValues[3].toIntOrNull() ?: 0
            buildDate(year, month, day)?.let { return it }
        }
        dayFirstDate.find(normalized)?.let { match ->
            val day = match.groupValues[1].toIntOrNull() ?: 0
            val month = match.groupValues[2].toIntOrNull() ?: 0
            val year = expandYear(match.groupValues[3])
            buildDate(year, month, day)?.let { return it }
        }
        slashedDate.find(normalized)?.let { match ->
            val first = match.groupValues[1].toIntOrNull() ?: 0
            val second = match.groupValues[2].toIntOrNull() ?: 0
            val year = expandYear(match.groupValues[3])
            (buildDate(year, second, first) ?: buildDate(year, first, second))?.let { return it }
        }
        compactDate.find(normalized)?.let { match ->
            val year = match.groupValues[1].toIntOrNull() ?: 0
            val month = match.groupValues[2].toIntOrNull() ?: 0
            val day = match.groupValues[3].toIntOrNull() ?: 0
            buildDate(year, month, day)?.let { return it }
        }
        return null
    }

    private fun parseTimeFrom(normalized: String): LocalTime? {
        val match = timePattern.find(normalized) ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        val second = match.groupValues[3].toIntOrNull() ?: 0
        val meridiem = match.groupValues[4].replace(".", "").trim()
        if (meridiem.isNotEmpty()) {
            if (meridiem.startsWith("p") && hour < 12) hour += 12
            if (meridiem.startsWith("a") && hour == 12) hour = 0
        }
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        return LocalTime.of(hour, minute, second)
    }

    private fun monthOf(token: String): Int {
        val exact = monthNames[token]
        if (exact != null) return exact
        val prefix = if (token.length >= 3) token.substring(0, 3) else token
        return monthNames[prefix] ?: 0
    }

    private fun expandYear(token: String): Int {
        val value = token.toIntOrNull() ?: return 0
        return if (value < 100) {
            val candidate = 2000 + value
            if (candidate > java.time.LocalDate.now().year + 1) candidate - 100 else candidate
        } else {
            value
        }
    }

    private fun buildDate(year: Int, month: Int, day: Int): LocalDate? {
        if (year < 2000 || year > java.time.LocalDate.now().year + 1) return null
        if (month !in 1..12) return null
        if (day !in 1..31) return null
        return try {
            LocalDate.of(year, month, day)
        } catch (_: Exception) {
            null
        }
    }
}
