package com.healthscan.app.parse

import com.healthscan.app.domain.FieldSchema
import com.healthscan.app.domain.FieldValue
import kotlin.math.abs
import kotlin.math.sqrt

data class Reconciled(
    val values: Map<String, FieldValue>,
    val warnings: List<String>,
)

/**
 * Cross-checks the extracted numbers against the identities body composition scales use
 * (fat mass = weight x fat %, fat free = weight - fat mass, bmi = weight / height squared) and fills
 * in values the screenshot does not print directly.
 */
object Consistency {

    private const val MASS_TOLERANCE = 0.9
    private const val PERCENT_TOLERANCE = 2.0
    private const val BMI_TOLERANCE = 0.8

    fun reconcile(parsed: Map<String, FieldValue>): Reconciled {
        val values = LinkedHashMap(parsed)
        val warnings = ArrayList<String>()
        val derived = LinkedHashMap<String, FieldValue>()

        val weight = values["weight"]?.value
        val fatPct = values["body_fat_pct"]?.value
        val fatMass = values["fat_mass"]?.value
        val lean = values["fat_free_mass"]?.value
        val height = values["height"]?.value
        val bmi = values["bmi"]?.value
        val waterPct = values["body_water_pct"]?.value
        val waterMass = values["body_water_mass"]?.value
        val bonePct = values["bone_mineral_pct"]?.value
        val boneMass = values["bone_mass"]?.value
        val proteinPct = values["protein_pct"]?.value
        val proteinMass = values["protein_mass"]?.value
        val muscleMass = values["muscle_mass"]?.value
        val skeletalMuscle = values["skeletal_muscle_mass"]?.value

        // fat mass <-> fat percentage
        if (weight != null && fatPct != null) {
            val implied = weight * fatPct / 100.0
            if (fatMass == null) {
                derived["fat_mass"] = FieldValue(implied, 0.85, derived = true, rawText = "derived")
            } else if (abs(fatMass - implied) > MASS_TOLERANCE) {
                warnings += "Fat mass ${fmt(fatMass)} kg does not match weight x fat % (${fmt(implied)} kg)."
            }
        }

        // fat free / lean body mass
        val resolvedFatMass = fatMass ?: (weight?.let { w -> fatPct?.let { w * it / 100.0 } })
        if (weight != null && resolvedFatMass != null) {
            val impliedLean = weight - resolvedFatMass
            if (lean == null) {
                derived["fat_free_mass"] = FieldValue(impliedLean, 0.85, derived = true, rawText = "derived")
            } else if (abs(lean - impliedLean) > MASS_TOLERANCE) {
                warnings += "Fat-free weight ${fmt(lean)} kg does not match weight - fat mass (${fmt(impliedLean)} kg)."
            }
        }

        // bmi <-> height
        if (weight != null && height != null) {
            val impliedBmi = weight / (height * height)
            if (bmi == null) {
                derived["bmi"] = FieldValue(impliedBmi, 0.9, derived = true, rawText = "derived")
            } else if (abs(bmi - impliedBmi) > BMI_TOLERANCE) {
                warnings += "BMI ${fmt(bmi)} does not match weight/height² (${fmt(impliedBmi)})."
            }
        } else if (weight != null && bmi != null && bmi > 5.0 && height == null) {
            val impliedHeight = sqrt(weight / bmi)
            if (impliedHeight in 0.9..2.4) {
                derived["height"] = FieldValue(impliedHeight, 0.7, derived = true, rawText = "derived")
            }
        }

        // body water
        if (weight != null && waterPct != null) {
            val implied = weight * waterPct / 100.0
            if (waterMass == null) {
                derived["body_water_mass"] = FieldValue(implied, 0.8, derived = true, rawText = "derived")
            } else if (abs(waterMass - implied) > MASS_TOLERANCE * 1.6) {
                warnings += "Body water mass ${fmt(waterMass)} kg does not match weight x water % (${fmt(implied)} kg)."
            }
        }

        // bone mineral
        if (weight != null && bonePct != null) {
            val implied = weight * bonePct / 100.0
            if (boneMass == null) {
                derived["bone_mass"] = FieldValue(implied, 0.8, derived = true, rawText = "derived")
            } else if (abs(boneMass - implied) > 0.6) {
                warnings += "Bone mass ${fmt(boneMass)} kg does not match weight x bone % (${fmt(implied)} kg)."
            }
        }

        // protein
        if (weight != null && proteinPct != null) {
            val implied = weight * proteinPct / 100.0
            if (proteinMass == null) {
                derived["protein_mass"] = FieldValue(implied, 0.8, derived = true, rawText = "derived")
            } else if (abs(proteinMass - implied) > MASS_TOLERANCE) {
                warnings += "Protein mass ${fmt(proteinMass)} kg does not match weight x protein % (${fmt(implied)} kg)."
            }
        }

        // muscle plausibility
        val resolvedLean = values["fat_free_mass"]?.value ?: derived["fat_free_mass"]?.value
        if (muscleMass != null && resolvedLean != null && muscleMass > resolvedLean + 1.0) {
            warnings += "Muscle mass ${fmt(muscleMass)} kg is larger than fat-free weight ${fmt(resolvedLean)} kg."
        }
        if (skeletalMuscle != null && muscleMass != null && skeletalMuscle > muscleMass + 1.0) {
            warnings += "Skeletal muscle mass ${fmt(skeletalMuscle)} kg is larger than muscle mass ${fmt(muscleMass)} kg."
        }

        val merged = LinkedHashMap(values)
        merged.putAll(derived)
        return Reconciled(values = merged, warnings = warnings)
    }

    fun rangeWarnings(values: Map<String, FieldValue>): List<String> {
        val out = ArrayList<String>()
        for ((key, fieldValue) in values) {
            val spec = FieldSchema.byKey[key] ?: continue
            if (fieldValue.value < spec.min || fieldValue.value > spec.max) {
                out += "${spec.displayName} ${FieldSchema.format(key, fieldValue.value)} is outside the plausible range ${spec.min}..${spec.max}."
            }
        }
        return out
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
}
