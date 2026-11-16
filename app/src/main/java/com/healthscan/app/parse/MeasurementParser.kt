package com.healthscan.app.parse

import com.healthscan.app.domain.FieldSchema
import com.healthscan.app.domain.FieldSpec
import com.healthscan.app.domain.TextNormalizer
import com.healthscan.app.domain.ValueKind
import com.healthscan.app.ocr.OcrLine
import com.healthscan.app.ocr.OcrResult
import java.time.ZoneId
import kotlin.math.abs

data class Candidate(
    val spec: FieldSpec,
    val value: Double,
    val confidence: Double,
    val geometry: String,
    val labelLine: String,
    val valueText: String,
)

data class ParseOutcome(
    val candidates: List<Candidate>,
    val dateTime: ParsedDateTime?,
    val rawLines: List<String>,
    val ocrConfidence: Float,
)

/**
 * Geometry driven extraction. Every OCR line is first attributed to the field whose label synonym it
 * matches best; the metric value is then looked for on the same line, directly above or directly
 * below that label. Body composition scale screenshots usually render the large number above the
 * caption.
 */
class MeasurementParser {

    private data class Pick(
        val value: Double,
        val rawText: String,
        val unitFactor: Double,
        val unitConfidence: Double,
        val score: Double,
    )

    private data class UnitSignal(val multiplier: Double, val explicit: Boolean, val incompatible: Boolean)

    fun parse(result: OcrResult, zone: ZoneId = ZoneId.systemDefault()): ParseOutcome {
        val lines = result.lines
        val labelForms = lines.map { TextNormalizer.normalize(it.text) }
        val valueForms = lines.map { TextNormalizer.normalizeLoose(it.text) }
        val hits = valueForms.map { NumberParser.hits(it) }

        val owner = arrayOfNulls<FieldSpec>(lines.size)
        val ownerScore = DoubleArray(lines.size)
        for ((index, text) in labelForms.withIndex()) {
            if (text.isEmpty() || text.split(" ").size > 6) continue
            for (spec in FieldSchema.all) {
                val score = labelScore(text, spec)
                if (score > ownerScore[index]) {
                    ownerScore[index] = score
                    owner[index] = spec
                }
            }
        }

        val candidates = ArrayList<Candidate>(FieldSchema.all.size)
        val usedLines = HashSet<Int>()
        for (spec in FieldSchema.all) {
            var best: Candidate? = null
            for (index in lines.indices) {
                if (owner[index] !== spec) continue
                val candidate = candidateForLabel(
                    index, spec, ownerScore[index], lines, labelForms, valueForms, hits, owner, usedLines,
                ) ?: continue
                if (best == null || candidate.confidence > best.confidence) best = candidate
            }
            if (best == null && spec.essential) {
                best = heroCandidate(spec, lines, valueForms, hits, owner, usedLines)
            }
            best?.let { candidates += it }
        }

        return ParseOutcome(
            candidates = candidates,
            dateTime = DateParser.find(lines, zone),
            rawLines = lines.map { it.text },
            ocrConfidence = result.meanConfidence,
        )
    }

    private fun candidateForLabel(
        labelIndex: Int,
        spec: FieldSpec,
        score: Double,
        lines: List<OcrLine>,
        labelForms: List<String>,
        valueForms: List<String>,
        hits: List<List<NumberHit>>,
        owner: Array<FieldSpec?>,
        used: MutableSet<Int>,
    ): Candidate? {
        val label = lines[labelIndex]

        pickOnSameLine(labelIndex, label, spec, valueForms, hits)?.let { pick ->
            used += labelIndex
            return buildCandidate(spec, pick, label, "same", score, 1.0)
        }

        val maxGap = label.height * 2.6f

        var index = labelIndex - 1
        while (index >= 0) {
            val line = lines[index]
            if (label.top - line.bottom > maxGap) break
            if (!line.overlapsHorizontally(label) || used.contains(index)) {
                index -= 1
                continue
            }
            val lineOwner = owner[index]
            if (lineOwner != null && lineOwner !== spec) break
            val pick = pickInLine(line, label, spec, valueForms[index], hits[index])
            if (pick != null) {
                used += labelIndex
                used += index
                return buildCandidate(spec, pick, label, "above", score, 0.9)
            }
            index -= 1
        }

        index = labelIndex + 1
        while (index < lines.size) {
            val line = lines[index]
            if (line.top - label.bottom > maxGap) break
            if (!line.overlapsHorizontally(label) || used.contains(index)) {
                index += 1
                continue
            }
            val lineOwner = owner[index]
            if (lineOwner != null && lineOwner !== spec) break
            val pick = pickInLine(line, label, spec, valueForms[index], hits[index])
            if (pick != null) {
                used += labelIndex
                used += index
                return buildCandidate(spec, pick, label, "below", score, 0.82)
            }
            index += 1
        }
        return null
    }

    /**
     * Scale apps normally render the current weight as a very large number with a unit and without any
     * caption. When a required field has no label to anchor on, fall back to the tallest unattributed
     * numeric line that has an explicit unit close to it.
     */
    private fun heroCandidate(
        spec: FieldSpec,
        lines: List<OcrLine>,
        valueForms: List<String>,
        hits: List<List<NumberHit>>,
        owner: Array<FieldSpec?>,
        used: Set<Int>,
    ): Candidate? {
        if (lines.isEmpty()) return null
        val medianHeight = lines.map { it.height }.sorted()[lines.size / 2].coerceAtLeast(1)
        var best: Candidate? = null
        var bestHeight = 0
        for ((index, line) in lines.withIndex()) {
            if (owner[index] != null || used.contains(index) || hits[index].isEmpty()) continue
            if (line.height < medianHeight * HERO_HEIGHT_FACTOR) continue
            val unitForm = nearbyUnitForm(index, line, lines, valueForms, spec) ?: continue
            if (!unitSignal(spec, unitForm).explicit) continue
            val pick = bestPick(hits[index], spec, unitForm, line.cx) ?: continue
            if (line.height <= bestHeight) continue
            best = buildCandidate(spec, pick, line, "hero", HERO_LABEL_SCORE, 1.0)
            bestHeight = line.height
        }
        return best
    }

    private fun nearbyUnitForm(
        index: Int,
        line: OcrLine,
        lines: List<OcrLine>,
        valueForms: List<String>,
        spec: FieldSpec,
    ): String? {
        if (unitSignal(spec, valueForms[index]).explicit) return valueForms[index]
        val reach = line.height * 1.6f
        for ((other, neighbour) in lines.withIndex()) {
            if (other == index) continue
            if (abs(neighbour.cy - line.cy) > reach + neighbour.height) continue
            if (neighbour.left > line.right + reach || neighbour.right < line.left - reach) continue
            val combined = valueForms[index] + " " + valueForms[other]
            if (unitSignal(spec, combined).explicit) return combined
        }
        return null
    }

    private fun pickOnSameLine(
        labelIndex: Int,
        label: OcrLine,
        spec: FieldSpec,
        valueForms: List<String>,
        hits: List<List<NumberHit>>,
    ): Pick? {
        val lineHits = hits[labelIndex]
        if (lineHits.isEmpty()) return null
        val form = valueForms[labelIndex]
        val labelEnd = labelEndIndex(form, spec)
        val afterLabel = lineHits.filter { it.startInLine >= labelEnd }
        val pool = if (afterLabel.isEmpty()) lineHits else afterLabel
        return bestPick(pool, spec, form, label.cx)
    }

    private fun pickInLine(
        line: OcrLine,
        label: OcrLine,
        spec: FieldSpec,
        form: String,
        lineHits: List<NumberHit>,
    ): Pick? {
        if (lineHits.isEmpty()) return null
        val pick = bestPick(lineHits, spec, form, label.cx) ?: return null
        val token = line.tokens.firstOrNull { it.text.trim() == pick.rawText }
        val width = (label.right - label.left).coerceAtLeast(1)
        val proximity = if (token == null) 0.95 else if (abs(token.cx - label.cx) < width * 1.6) 1.0 else 0.88
        return pick.copy(unitConfidence = pick.unitConfidence * proximity)
    }

    private fun labelEndIndex(form: String, spec: FieldSpec): Int {
        var best = 0
        for (synonym in spec.synonyms) {
            val at = form.indexOf(synonym)
            if (at >= 0) best = maxOf(best, at + synonym.length)
        }
        return best
    }

    private fun bestPick(hits: List<NumberHit>, spec: FieldSpec, form: String, labelCx: Int): Pick? {
        val unit = unitSignal(spec, form)
        if (unit.incompatible) return null
        val unitBonus = if (unit.explicit) 0.45 else 0.0
        val unitConfidence = if (unit.explicit) 1.0 else 0.94

        var best: Pick? = null
        for (hit in hits) {
            for (raw in hit.candidates()) {
                if (raw.isNaN() || raw.isInfinite()) continue
                val value = raw * unit.multiplier
                if (!spec.target && value < 0) continue
                val inRange = value >= spec.min && value <= spec.max
                var score = if (inRange) 1.0 else -0.6
                if (!hit.negative) score += 0.3
                score += unitBonus
                if (score > (best?.score ?: Double.NEGATIVE_INFINITY)) {
                    best = Pick(
                        value = value,
                        rawText = hit.text,
                        unitFactor = unit.multiplier,
                        unitConfidence = unitConfidence,
                        score = score,
                    )
                }
            }
        }
        return best
    }

    private fun buildCandidate(
        spec: FieldSpec,
        pick: Pick,
        labelLine: OcrLine,
        geometry: String,
        labelScore: Double,
        geometryFactor: Double,
    ): Candidate {
        val inRange = pick.value >= spec.min && pick.value <= spec.max
        val ocrFactor = 0.45 + 0.55 * labelLine.confidence.coerceIn(0f, 1f)
        val confidence = labelScore * geometryFactor * pick.unitConfidence * ocrFactor *
            (if (inRange) 1.0 else 0.3)
        return Candidate(
            spec = spec,
            value = pick.value,
            confidence = confidence.coerceIn(0.0, 1.0),
            geometry = geometry,
            labelLine = labelLine.text.trim(),
            valueText = pick.rawText,
        )
    }

    private fun unitSignal(spec: FieldSpec, form: String): UnitSignal {
        val tokens = form.split(" ").toSet()
        val hasPercent = form.contains("%") || "percent" in tokens || "percentage" in tokens
        val hasKg = "kg" in tokens
        val hasGrams = "g" in tokens
        val hasPounds = "lb" in tokens || "lbs" in tokens || "pounds" in tokens
        val hasKcal = "kcal" in tokens || form.contains("kcal")
        val hasCm = "cm" in tokens
        val hasMetres = "m" in tokens || "meters" in tokens || "metres" in tokens
        val hasYears = "years" in tokens || "old" in tokens || "yrs" in tokens || "god" in tokens

        fun ok(multiplier: Double, explicit: Boolean) = UnitSignal(multiplier, explicit, false)

        return when (spec.kind) {
            ValueKind.MASS_KG -> when {
                hasKg -> ok(1.0, true)
                hasPounds -> ok(0.45359237, true)
                hasGrams -> ok(0.001, true)
                hasPercent || hasKcal -> UnitSignal(1.0, false, true)
                else -> ok(1.0, false)
            }

            ValueKind.PERCENT -> when {
                hasPercent -> ok(1.0, true)
                hasKg || hasKcal -> UnitSignal(1.0, false, true)
                else -> ok(1.0, false)
            }

            ValueKind.LENGTH_M -> when {
                hasCm -> ok(0.01, true)
                hasMetres -> ok(1.0, true)
                hasKg -> UnitSignal(1.0, false, true)
                else -> ok(1.0, false)
            }

            ValueKind.POWER_KCAL_DAY -> when {
                hasKcal -> ok(1.0, true)
                hasKg || hasPercent -> UnitSignal(1.0, false, true)
                else -> ok(1.0, false)
            }

            ValueKind.RATIO -> when {
                hasPercent || hasKg || hasKcal -> UnitSignal(1.0, false, true)
                else -> ok(1.0, false)
            }

            ValueKind.SCORE -> when {
                hasKg || hasPercent || hasKcal -> UnitSignal(1.0, false, true)
                "pts" in tokens || "score" in tokens -> ok(1.0, true)
                else -> ok(1.0, false)
            }

            ValueKind.YEARS -> when {
                hasYears -> ok(1.0, true)
                hasKg || hasPercent || hasKcal -> UnitSignal(1.0, false, true)
                else -> ok(1.0, false)
            }
        }
    }

    private fun labelScore(normalizedLine: String, spec: FieldSpec): Double {
        var best = 0.0
        for (synonym in spec.synonyms) {
            if (synonym.isEmpty()) continue
            // A multi word synonym is a more specific label than a bare generic word such as "masa",
            // which would otherwise claim "masa kostiju" or "masa vode".
            val specificity = if (synonym.contains(' ')) 1.0 else 0.8
            if (normalizedLine == synonym) {
                best = maxOf(best, 1.0)
                continue
            }
            if (normalizedLine.startsWith("$synonym ")) {
                val rest = normalizedLine.removePrefix("$synonym ").trim()
                val restTokens = rest.split(" ").filter { it.isNotEmpty() }
                val allUnits = restTokens.isNotEmpty() && restTokens.all { it in UNIT_WORDS }
                when {
                    allUnits -> best = maxOf(best, 0.95 * specificity)
                    restTokens.size <= 2 -> best = maxOf(best, 0.8 * specificity)
                    else -> best = maxOf(best, 0.6 * specificity)
                }
            }
            if (normalizedLine.endsWith(" $synonym")) best = maxOf(best, 0.85 * specificity)
            if (containsSequence(normalizedLine, synonym)) {
                val extra = normalizedLine.split(" ").size - synonym.split(" ").size
                best = maxOf(best, (if (extra <= 2) 0.75 else 0.55) * specificity)
            }
        }
        if (best > 0.0 && normalizedLine.any { it.isDigit() }) best -= 0.12
        return best
    }

    private fun containsSequence(line: String, sequence: String): Boolean {
        val lineTokens = line.split(" ")
        val needle = sequence.split(" ")
        if (needle.size > lineTokens.size) return false
        for (start in 0..(lineTokens.size - needle.size)) {
            var matches = true
            for (offset in needle.indices) {
                if (lineTokens[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    companion object {
        private const val HERO_HEIGHT_FACTOR = 1.9f
        private const val HERO_LABEL_SCORE = 0.7

        private val UNIT_WORDS = setOf(
            "kg", "kgs", "g", "lb", "lbs", "pounds", "%", "percent", "percentage",
            "kcal", "kcla", "cm", "m", "mm", "pts", "score", "years", "year", "old",
            "god", "dana", "dan", "w", "wat", "points", "bmi",
        )
    }
}
