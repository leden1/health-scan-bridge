package com.healthscan.app

import com.healthscan.app.domain.FieldValue
import com.healthscan.app.ocr.OcrLine
import com.healthscan.app.ocr.OcrResult
import com.healthscan.app.ocr.OcrToken
import com.healthscan.app.parse.Consistency
import com.healthscan.app.parse.DateParser
import com.healthscan.app.parse.MeasurementParser
import com.healthscan.app.parse.NumberParser
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End to end checks of the text extraction layer: synthetic OCR geometry that reproduces the way
 * body composition scale screens are laid out (large value above the caption, two columns, and the
 * single line style used by Croatian printouts).
 */
class ParsePipelineTest {

    private val zone: ZoneId = ZoneId.of("Europe/Zagreb")

    private fun row(text: String, left: Int, top: Int, right: Int, bottom: Int, confidence: Float = 0.95f): OcrLine {
        val words = text.split(" ").filter { it.isNotBlank() }
        val segment = (right - left).coerceAtLeast(1) / words.size
        val tokens = words.mapIndexed { index, word ->
            val start = left + index * segment
            OcrToken(word, start, top, start + segment, bottom, confidence)
        }
        return OcrLine(text, tokens, left, top, right, bottom, confidence)
    }

    private fun result(vararg lines: OcrLine): OcrResult = OcrResult(
        lines = lines.toList().sortedWith(compareBy({ it.top }, { it.left })),
        imageWidth = 2400,
        imageHeight = 2000,
        tiles = 1,
    )

    private fun valuesOf(outcome: com.healthscan.app.parse.ParseOutcome): Map<String, Double> =
        outcome.candidates.associate { it.spec.key to it.value }

    @Test
    fun twoColumnEnglishScreenWithValuesAboveLabels() {
        val outcome = MeasurementParser().parse(
            result(
                row("2026-11-14 09:02", 100, 40, 700, 100),
                row("72,4 kg", 100, 140, 350, 230),
                row("42,1 kg", 1200, 140, 1450, 230),
                row("Weight (kg)", 100, 250, 400, 310),
                row("Body water mass", 1200, 250, 1600, 310),
                row("175 cm", 100, 360, 320, 450),
                row("3,1 kg", 1200, 360, 1420, 450),
                row("Height", 100, 470, 300, 530),
                row("Bone mineral mass", 1200, 470, 1650, 530),
                row("23,6", 100, 580, 260, 670),
                row("55,4 kg", 1200, 580, 1450, 670),
                row("BMI", 100, 690, 240, 750),
                row("Muscle mass", 1200, 690, 1500, 750),
                row("18,7 %", 100, 800, 290, 890),
                row("4", 1200, 800, 1260, 890),
                row("Body fat", 100, 910, 320, 970),
                row("Visceral fat", 1200, 910, 1500, 970),
                row("1 540 kcal", 1200, 1020, 1500, 1110),
                row("BMR", 1200, 1130, 1320, 1190),
            ),
            zone,
        )

        val values = valuesOf(outcome)
        assertEquals(72.4, values.getValue("weight"), 0.01)
        assertEquals(1.75, values.getValue("height"), 0.001)
        assertEquals(23.6, values.getValue("bmi"), 0.01)
        assertEquals(18.7, values.getValue("body_fat_pct"), 0.01)
        assertEquals(42.1, values.getValue("body_water_mass"), 0.01)
        assertEquals(3.1, values.getValue("bone_mass"), 0.01)
        assertEquals(55.4, values.getValue("muscle_mass"), 0.01)
        assertEquals(4.0, values.getValue("visceral_fat"), 0.01)
        assertEquals(1540.0, values.getValue("bmr"), 0.5)

        val captured = outcome.dateTime
        assertNotNull(captured)
        assertEquals(2026, captured!!.zonedDateTime.year)
        assertEquals(11, captured.zonedDateTime.monthValue)
        assertEquals(14, captured.zonedDateTime.dayOfMonth)
        assertEquals(9, captured.zonedDateTime.hour)
        assertEquals(2, captured.zonedDateTime.minute)
    }

    @Test
    fun derivedFieldsFillTheGaps() {
        val outcome = MeasurementParser().parse(
            result(
                row("72,4 kg", 100, 140, 350, 230),
                row("Weight (kg)", 100, 250, 400, 310),
                row("175 cm", 100, 360, 320, 450),
                row("Height", 100, 470, 300, 530),
                row("18,7 %", 100, 580, 290, 670),
                row("Body fat", 100, 690, 320, 750),
            ),
            zone,
        )

        val reconciled = Consistency.reconcile(
            outcome.candidates.associate { it.spec.key to FieldValue(it.value, it.confidence) },
        )

        assertEquals(13.5, reconciled.values.getValue("fat_mass").value, 0.1)
        assertEquals(58.9, reconciled.values.getValue("fat_free_mass").value, 0.1)
        assertEquals(23.6, reconciled.values.getValue("bmi").value, 0.1)
        assertTrue("unexpected warnings: ${reconciled.warnings}", reconciled.warnings.isEmpty())
    }

    @Test
    fun croatianSingleLineScreen() {
        val outcome = MeasurementParser().parse(
            result(
                row("Mjerenje 14.11.2026 07:45", 120, 60, 900, 130),
                row("Tjelesna masa 78,3 kg", 120, 200, 900, 270),
                row("Visina 178 cm", 120, 300, 700, 370),
                row("Tjelesna mast 21,4 %", 120, 400, 900, 470),
                row("Voda 55,0 %", 120, 500, 700, 570),
                row("Masa kostiju 3,4 kg", 120, 600, 900, 670),
                row("Tjelesna dob 34", 1200, 200, 1700, 270),
                row("Tjelesni skor 78", 1200, 300, 1700, 370),
                row("Bazalni metabolizam 1560 kcal", 1200, 400, 2100, 470),
            ),
            zone,
        )

        val values = valuesOf(outcome)
        assertEquals(78.3, values.getValue("weight"), 0.01)
        assertEquals(1.78, values.getValue("height"), 0.001)
        assertEquals(21.4, values.getValue("body_fat_pct"), 0.01)
        assertEquals(55.0, values.getValue("body_water_pct"), 0.01)
        assertEquals(3.4, values.getValue("bone_mass"), 0.01)
        assertEquals(34.0, values.getValue("body_age"), 0.01)
        assertEquals(78.0, values.getValue("body_score"), 0.01)
        assertEquals(1560.0, values.getValue("bmr"), 0.5)

        val captured = outcome.dateTime
        assertNotNull(captured)
        assertEquals(2026, captured!!.zonedDateTime.year)
        assertEquals(11, captured.zonedDateTime.monthValue)
        assertEquals(14, captured.zonedDateTime.dayOfMonth)
        assertEquals(7, captured.zonedDateTime.hour)
        assertEquals(45, captured.zonedDateTime.minute)
    }

    @Test
    fun genericWeightLabelDoesNotStealQualifiedMassLabels() {
        val outcome = MeasurementParser().parse(
            result(
                row("Masa 81,2 kg", 120, 60, 700, 130),
                row("Masa vode 44,6 kg", 120, 160, 800, 230),
                row("Masa kostiju 3,5 kg", 120, 260, 800, 330),
            ),
            zone,
        )

        val values = valuesOf(outcome)
        assertEquals(81.2, values.getValue("weight"), 0.01)
        assertEquals(44.6, values.getValue("body_water_mass"), 0.01)
        assertEquals(3.5, values.getValue("bone_mass"), 0.01)
    }

    @Test
    fun numbersTolerateBothDecimalStyles() {
        assertEquals(72.4, NumberParser.parseSingle("72,4")!!, 0.001)
        assertEquals(72.4, NumberParser.parseSingle("72.4")!!, 0.001)
        assertEquals(1.75, NumberParser.parseSingle("1,75")!!, 0.001)
        assertEquals(1670.0, NumberParser.parseSingle("1.670")!!, 0.001)
        assertEquals(1670.0, NumberParser.parseSingle("1 670")!!, 0.001)
        assertEquals(1670.0, NumberParser.parseSingle("1.670,0")!!, 0.001)
        assertEquals(-2.5, NumberParser.parseSingle("-2,5")!!, 0.001)
        assertNull(NumberParser.parseSingle("72.4 kg"))
        assertNull(NumberParser.parseSingle(""))
        assertNull(NumberParser.parseSingle("bmi"))
    }

    @Test
    fun rangesBecomeSeparateHits() {
        val hits = NumberParser.hits("Fat control -0,5 do 1,0 kg")
        val numbers = hits.map { it.value }
        assertTrue("expected -0.5 and 1.0 in $numbers", numbers.contains(-0.5) && numbers.contains(1.0))
    }

    @Test
    fun typedDatesAcceptIsoAndDayFirst() {
        val iso = DateParser.parseTyped("2026-11-14 09:02", zone)!!
        assertEquals(2026, iso.zonedDateTime.year)
        assertEquals(11, iso.zonedDateTime.monthValue)
        assertEquals(14, iso.zonedDateTime.dayOfMonth)
        assertEquals(9, iso.zonedDateTime.hour)

        val dayFirst = DateParser.parseTyped("14.11.2026 09:02", zone)!!
        assertEquals(iso.zonedDateTime, dayFirst.zonedDateTime)

        val monthName = DateParser.parseTyped("14. studeni 2026 09:02", zone)!!
        assertEquals(iso.zonedDateTime, monthName.zonedDateTime)

        assertNull(DateParser.parseTyped("nema datuma", zone))
    }

    @Test
    fun heroWeightWithoutLabelComesFromTheLargestNumber() {
        val outcome = MeasurementParser().parse(
            result(
                row("11/14/2026 09:02", 280, 294, 661, 332),
                row("74 00", 454, 619, 965, 881, confidence = 0.54f),
                row("kg", 855, 639, 906, 690),
                row("Increase in 0.30 kg compared to 6 days ago", 234, 982, 1206, 1026),
                row("Body score", 123, 1257, 385, 1310),
                row("43.1 kg", 837, 2260, 1036, 2339, confidence = 0.62f),
                row("Body water mass", 839, 2366, 1190, 2407),
                row("13.8 kg", 835, 2478, 1046, 2561),
                row("Fat mass", 838, 2582, 1016, 2622),
                row("1,670.0 kcal", 821, 4527, 1304, 4603),
                row("Basal metabolic rate", 822, 4627, 1236, 4662),
            ),
            zone,
        )

        val values = valuesOf(outcome)
        assertEquals(74.0, values.getValue("weight"), 0.01)
        assertEquals(43.1, values.getValue("body_water_mass"), 0.01)
        assertEquals(13.8, values.getValue("fat_mass"), 0.01)
        assertEquals(1670.0, values.getValue("bmr"), 0.5)
        assertTrue(
            "hero geometry expected",
            outcome.candidates.first { it.spec.key == "weight" }.geometry == "hero",
        )
    }

    @Test
    fun labelSplitOverTwoLinesStillMatches() {
        val outcome = MeasurementParser().parse(
            result(
                row("0.9", 126, 4908, 228, 4970),
                row("Estimated waist-to-hip", 124, 5006, 586, 5048),
                row("ratio", 123, 5066, 211, 5101),
                row("35 years old", 819, 4908, 1111, 4988),
                row("Body age", 820, 5004, 1011, 5049),
            ),
            zone,
        )

        val values = valuesOf(outcome)
        assertEquals(0.9, values.getValue("waist_hip_ratio"), 0.01)
        assertEquals(35.0, values.getValue("body_age"), 0.01)
    }

    @Test
    fun lostDecimalPointBecomesAnAlternativeReading() {
        val numbers = NumberParser.hits("74 00").flatMap { it.candidates() }
        assertTrue("expected 74.0 among $numbers", numbers.contains(74.0))
        assertTrue("expected 7400 among $numbers", numbers.contains(7400.0))
        assertEquals(listOf(1670.0), NumberParser.hits("1 670").flatMap { it.candidates() })
    }

    @Test
    fun suggestionBlockReadsValuesBelowTheirOwnLabels() {
        val outcome = MeasurementParser().parse(
            result(
                row("Weight suggestions", 122, 7286, 700, 7350),
                row("Standard weight:", 295, 7430, 700, 7480),
                row("71.40 kg", 292, 7506, 560, 7600),
                row("Weight control:", 294, 7709, 700, 7760),
                row("(-", 164, 7765, 210, 7810),
                row("-2.60 kg", 293, 7782, 560, 7860),
                row("Fat control:", 297, 7992, 700, 8050),
                row("(-", 160, 8034, 206, 8080),
                row("-3.10 kg", 295, 8062, 560, 8140),
                row("Muscle control:", 298, 8268, 720, 8330),
                row("+0.50 kg", 299, 8342, 560, 8420),
            ),
            zone,
        )

        val values = valuesOf(outcome)
        assertEquals(71.4, values.getValue("standard_weight"), 0.01)
        assertEquals(-2.6, values.getValue("weight_control"), 0.01)
        assertEquals(-3.1, values.getValue("fat_control"), 0.01)
        assertEquals(0.5, values.getValue("muscle_control"), 0.01)
    }

    @Test
    fun inconsistentValuesAreReportedNotSilentlyAccepted() {
        val reconciled = Consistency.reconcile(
            mapOf(
                "weight" to FieldValue(80.0),
                "body_fat_pct" to FieldValue(20.0),
                "fat_mass" to FieldValue(30.0),
            ),
        )
        assertTrue(reconciled.warnings.any { it.contains("Fat mass") })
        assertTrue(Consistency.rangeWarnings(mapOf("weight" to FieldValue(7.0))).isNotEmpty())
    }
}
