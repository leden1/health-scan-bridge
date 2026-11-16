package com.healthscan.app.domain

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class FieldValue(
    val value: Double,
    val confidence: Double = 1.0,
    val derived: Boolean = false,
    val rawText: String = "",
)

data class Measurement(
    val id: String,
    val capturedAt: ZonedDateTime,
    val sourceLabel: String,
    val values: Map<String, FieldValue>,
    val warnings: List<String> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val pushedAt: Instant? = null,
) {
    fun value(key: String): Double? = values[key]?.value

    fun field(key: String): FieldValue? = values[key]

    fun withValue(key: String, fieldValue: FieldValue): Measurement =
        copy(values = values + (key to fieldValue))

    fun without(key: String): Measurement = copy(values = values - key)

    fun display(key: String): String = values[key]?.let { FieldSchema.format(key, it.value) } ?: "-"

    val hcKeys: Set<String>
        get() = FieldSchema.all.mapNotNull { spec ->
            if (spec.hcType != null && values.containsKey(spec.key)) spec.key else null
        }.toSet()

    companion object {
        private val idFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

        fun newId(capturedAt: ZonedDateTime): String =
            capturedAt.format(idFormat) + "-" + (System.nanoTime() % 100000).toString(36)
    }
}

object TextNormalizer {
    private val diacritics = mapOf(
        'č' to 'c', 'ć' to 'c', 'š' to 's', 'ž' to 'z', 'đ' to 'd',
        'à' to 'a', 'ä' to 'a', 'ö' to 'o', 'ü' to 'u',
        'é' to 'e', 'è' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u', 'ñ' to 'n',
        'ł' to 'l', 'ş' to 's', 'ğ' to 'g', 'ı' to 'i', 'å' to 'a',
    )

    private fun asciiLower(input: String): String {
        val lower = input.lowercase(java.util.Locale.ROOT)
        val sb = StringBuilder(lower.length)
        for (ch in lower) {
            val replaced = diacritics[ch]
            sb.append(replaced ?: ch)
        }
        return sb.toString()
    }

    /** Lower case, ascii only, every punctuation replaced by a space. Used for label matching. */
    fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in asciiLower(input)) {
            sb.append(if (ch.isLetterOrDigit()) ch else ' ')
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Lower case and ascii only but keeps punctuation such as %, - and decimal separators.
     * Used for unit detection and number extraction.
     */
    fun normalizeLoose(input: String): String =
        asciiLower(input).replace(Regex("\\s+"), " ").trim()
}
