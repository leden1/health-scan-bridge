package com.healthscan.app.domain

enum class ValueKind {
    MASS_KG,
    PERCENT,
    LENGTH_M,
    POWER_KCAL_DAY,
    RATIO,
    SCORE,
    YEARS,
}

enum class HcType { WEIGHT, HEIGHT, BODY_FAT, LEAN_BODY_MASS, BODY_WATER_MASS, BONE_MASS, BMR }

data class FieldSpec(
    val key: String,
    val displayName: String,
    val unit: String,
    val kind: ValueKind,
    val decimals: Int,
    val min: Double,
    val max: Double,
    val synonyms: List<String>,
    val hcType: HcType? = null,
    val essential: Boolean = false,
    val target: Boolean = false,
)

/**
 * Normalized synonyms are lower case, ascii only, punctuation replaced by single spaces.
 * Matching happens against the same normalization applied to OCR lines.
 */
object FieldSchema {

    val weight = FieldSpec(
        key = "weight",
        displayName = "Weight",
        unit = "kg",
        kind = ValueKind.MASS_KG,
        decimals = 2,
        min = 20.0,
        max = 250.0,
        synonyms = listOf(
            "weight", "body weight", "weight kg", "body mass", "current weight",
            "tjelesna masa", "telesna masa", "tezina", "masa", "misa",
        ),
        hcType = HcType.WEIGHT,
        essential = true,
    )

    val height = FieldSpec(
        key = "height",
        displayName = "Height",
        unit = "m",
        kind = ValueKind.LENGTH_M,
        decimals = 2,
        min = 0.8,
        max = 2.4,
        synonyms = listOf("height", "body height", "height m", "visina", "visina tijela", "rast"),
        hcType = HcType.HEIGHT,
    )

    val bmi = FieldSpec(
        key = "bmi",
        displayName = "BMI",
        unit = "",
        kind = ValueKind.RATIO,
        decimals = 1,
        min = 10.0,
        max = 60.0,
        synonyms = listOf("bmi", "body mass index", "body mass index bmi", "imt", "index mase tijela"),
    )

    val bodyFatPct = FieldSpec(
        key = "body_fat_pct",
        displayName = "Body fat",
        unit = "%",
        kind = ValueKind.PERCENT,
        decimals = 1,
        min = 1.0,
        max = 75.0,
        synonyms = listOf(
            "body fat", "body fat percentage", "body fat percent", "fat percentage",
            "fat rate", "body fat ratio", "tjelesna mast", "postotak masti", "masnoce",
        ),
        hcType = HcType.BODY_FAT,
        essential = true,
    )

    val fatMass = FieldSpec(
        key = "fat_mass",
        displayName = "Fat mass",
        unit = "kg",
        kind = ValueKind.MASS_KG,
        decimals = 1,
        min = 0.5,
        max = 150.0,
        synonyms = listOf(
            "fat mass", "fat mass kg", "mass of fat", "fat weight",
            "masa masnog tkiva", "masa masti",
        ),
    )

    val fatFreeMass = FieldSpec(
        key = "fat_free_mass",
        displayName = "Fat-free body weight",
        unit = "kg",
        kind = ValueKind.MASS_KG,
        decimals = 1,
        min = 10.0,
        max = 200.0,
        synonyms = listOf(
            "fat free body weight", "fat free weight", "fat free body mass", "fat free mass",
            "lean body mass", "lean mass", "lean body weight", "lean weight",
            "slobodna masa", "bez masti",
        ),
        hcType = HcType.LEAN_BODY_MASS,
    )

    val muscleMass = FieldSpec(
        key = "muscle_mass",
        displayName = "Muscle mass",
        unit = "kg",
        kind = ValueKind.MASS_KG,
        decimals = 1,
        min = 5.0,
        max = 150.0,
        synonyms = listOf("muscle mass", "muscle mass kg", "misicna masa", "mislucna masa", "muskulatura"),
    )

    val musclePct = FieldSpec(
        key = "muscle_pct",
        displayName = "Muscle percentage",
        unit = "%",
        kind = ValueKind.PERCENT,
        decimals = 1,
        min = 1.0,
        max = 95.0,
        synonyms = listOf(
            "muscle percentage", "muscle percent", "muscle rate", "muscle ratio",
            "postotak misica", "postotak miskulature",
        ),
    )

    val skeletalMuscleMass = FieldSpec(
        key = "skeletal_muscle_mass",
        displayName = "Skeletal muscle mass",
        unit = "kg",
        kind = ValueKind.MASS_KG,
        decimals = 1,
        min = 5.0,
        max = 120.0,
        synonyms = listOf(
            "skeletal muscle mass", "skeletal muscle", "smm", "skeletal muscle mass smm",
            "kostena muskulatura", "kostana muskulatura",
        ),
    )

    val bodyWaterPct = FieldSpec(
        key = "body_water_pct",
        displayName = "Body water",
        unit = "%",
        kind = ValueKind.PERCENT,
        decimals = 1,
        min = 10.0,
        max = 90.0,
        synonyms = listOf(
            "body water", "body water percentage", "body water percent", "water percentage",
            "water rate", "total body water", "tbw",
            "tjelesna voda", "postotak vode", "voda",
        ),
    )

    val bodyWaterMass = FieldSpec(
        key = "body_water_mass",
        displayName = "Body water mass",
        unit = "kg",
        kind = ValueKind.MASS_KG,
        decimals = 1,
        min = 5.0,
        max = 200.0,
        synonyms = listOf("body water mass", "water mass", "masa vode"),
        hcType = HcType.BODY_WATER_MASS,
    )

    val proteinPct = FieldSpec(
        key = "protein_pct",
        displayName = "Protein",
        unit = "%",
        kind = ValueKind.PERCENT,
        decimals = 1,
        min = 3.0,
        max = 40.0,
        synonyms = listOf("protein", "protein percentage", "protein percent", "protein rate", "bjelancevine"),
    )

    val proteinMass = FieldSpec(
        key = "protein_mass",
        displayName = "Protein mass",
        unit = "kg",
        kind = ValueKind.MASS_KG,
        decimals = 1,
        min = 1.0,
        max = 100.0,
        synonyms = listOf("protein mass", "masa proteina", "masa bjelancevina"),
    )

    val boneMineralPct = FieldSpec(
        key = "bone_mineral_pct",
        displayName = "Bone mineral",
        unit = "%",
        kind = ValueKind.PERCENT,
        decimals = 1,
        min = 1.0,
        max = 30.0,
        synonyms = listOf(
            "bone mineral", "bone mineral percentage", "bone mineral content", "bmc",
            "bone percentage", "bone percent", "mineral", "minerali", "postotak kostiju",
            "tjelesna kost", "kosti",
        ),
    )

    val boneMass = FieldSpec(
        key = "bone_mass",
        displayName = "Bone mass",
        unit = "kg",
        kind = ValueKind.MASS_KG,
        decimals = 1,
        min = 0.5,
        max = 30.0,
        synonyms = listOf("bone mass", "bone mineral mass", "bone mass kg", "masa kostiju"),
        hcType = HcType.BONE_MASS,
    )

    val visceralFat = FieldSpec(
        key = "visceral_fat",
        displayName = "Visceral fat rating",
        unit = "",
        kind = ValueKind.SCORE,
        decimals = 1,
        min = 0.5,
        max = 60.0,
        synonyms = listOf(
            "visceral fat", "visceral fat rating", "visceral fat level", "vf",
            "visceralna mast", "unutarnja mast",
        ),
    )

    val bmr = FieldSpec(
        key = "bmr",
        displayName = "BMR",
        unit = "kcal/day",
        kind = ValueKind.POWER_KCAL_DAY,
        decimals = 0,
        min = 500.0,
        max = 5000.0,
        synonyms = listOf(
            "bmr", "basal metabolic rate", "basic metabolic rate", "basal metabolism",
            "resting metabolic rate", "rnr", "bazalni metabolizam", "bazalni promet",
        ),
        hcType = HcType.BMR,
    )

    val bodyScore = FieldSpec(
        key = "body_score",
        displayName = "Body score",
        unit = "pts",
        kind = ValueKind.SCORE,
        decimals = 0,
        min = 1.0,
        max = 200.0,
        synonyms = listOf("body score", "score", "body age score", "tjelesni skor", "skor"),
    )

    val bodyAge = FieldSpec(
        key = "body_age",
        displayName = "Body age",
        unit = "years",
        kind = ValueKind.YEARS,
        decimals = 0,
        min = 8.0,
        max = 110.0,
        synonyms = listOf("body age", "biological age", "metabolic age", "tjelesna dob", "bioloska dob"),
    )

    val waistHipRatio = FieldSpec(
        key = "waist_hip_ratio",
        displayName = "Waist-to-hip ratio",
        unit = "",
        kind = ValueKind.RATIO,
        decimals = 2,
        min = 0.5,
        max = 1.6,
        synonyms = listOf(
            "waist to hip ratio", "waist to hip", "waist hip ratio", "whr", "w h ratio", "w h",
            "omjer struka i bokova", "omjer struka bokova",
        ),
    )

    val standardWeight = FieldSpec(
        key = "standard_weight",
        displayName = "Standard weight",
        unit = "kg",
        kind = ValueKind.MASS_KG,
        decimals = 2,
        min = 20.0,
        max = 250.0,
        synonyms = listOf("standard weight", "ideal weight", "target weight", "standardna tjelesna masa"),
        target = true,
    )

    val weightControl = FieldSpec(
        key = "weight_control",
        displayName = "Weight control",
        unit = "kg",
        kind = ValueKind.MASS_KG,
        decimals = 2,
        min = -60.0,
        max = 60.0,
        synonyms = listOf("weight control", "weight to lose", "weight change", "kontrola mase"),
        target = true,
    )

    val fatControl = FieldSpec(
        key = "fat_control",
        displayName = "Fat control",
        unit = "kg",
        kind = ValueKind.MASS_KG,
        decimals = 2,
        min = -60.0,
        max = 60.0,
        synonyms = listOf("fat control", "fat to lose", "kontrola masti"),
        target = true,
    )

    val muscleControl = FieldSpec(
        key = "muscle_control",
        displayName = "Muscle control",
        unit = "kg",
        kind = ValueKind.MASS_KG,
        decimals = 2,
        min = -60.0,
        max = 60.0,
        synonyms = listOf("muscle control", "muscle to gain", "kontrola misica"),
        target = true,
    )

    val all: List<FieldSpec> = listOf(
        weight, height, bmi, bodyFatPct, fatMass, fatFreeMass, muscleMass, musclePct,
        skeletalMuscleMass, bodyWaterPct, bodyWaterMass, proteinPct, proteinMass,
        boneMineralPct, boneMass, visceralFat, bmr, bodyScore, bodyAge, waistHipRatio,
        standardWeight, weightControl, fatControl, muscleControl,
    )

    val byKey: Map<String, FieldSpec> = all.associateBy { it.key }

    val measured: List<FieldSpec> = all.filter { !it.target }
    val targets: List<FieldSpec> = all.filter { it.target }

    fun format(key: String, value: Double): String {
        val spec = byKey[key] ?: return value.toString()
        return NumberFormatter.format(value, spec.decimals) + if (spec.unit.isEmpty()) "" else " ${spec.unit}"
    }
}

object NumberFormatter {
    fun format(value: Double, decimals: Int): String =
        String.format(java.util.Locale.US, "%.${decimals}f", value)
}
