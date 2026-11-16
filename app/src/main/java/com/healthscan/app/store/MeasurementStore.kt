package com.healthscan.app.store

import android.content.Context
import com.healthscan.app.domain.FieldValue
import com.healthscan.app.domain.Measurement
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

class MeasurementStore(context: Context) {

    private val file = File(context.filesDir, "measurements.json")

    fun loadAll(): List<Measurement> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            val out = ArrayList<Measurement>(array.length())
            for (i in 0 until array.length()) {
                runCatching { fromJson(array.getJSONObject(i)) }.getOrNull()?.let { out += it }
            }
            out.sortedByDescending { it.capturedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(measurement: Measurement) {
        val all = loadAll().toMutableList()
        all.removeAll { it.id == measurement.id }
        all += measurement
        writeAll(all.sortedByDescending { it.capturedAt })
    }

    fun delete(id: String) {
        val all = loadAll().filter { it.id != id }
        writeAll(all)
    }

    fun previous(before: ZonedDateTime): Measurement? =
        loadAll().firstOrNull { it.capturedAt <= before }

    private fun writeAll(all: List<Measurement>) {
        val array = JSONArray()
        all.forEach { array.put(toJson(it)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(array.toString(2))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun toJson(measurement: Measurement): JSONObject = JSONObject().apply {
        put("id", measurement.id)
        put("capturedAt", measurement.capturedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        put("zone", measurement.capturedAt.zone.id)
        put("sourceLabel", measurement.sourceLabel)
        put("createdAt", measurement.createdAt.toString())
        measurement.pushedAt?.let { put("pushedAt", it.toString()) }
        put("warnings", JSONArray(measurement.warnings))
        val values = JSONObject()
        measurement.values.forEach { (key, value) ->
            values.put(
                key,
                JSONObject().apply {
                    put("v", value.value)
                    put("c", value.confidence)
                    put("d", value.derived)
                    put("raw", value.rawText)
                },
            )
        }
        put("values", values)
    }

    private fun fromJson(obj: JSONObject): Measurement {
        val zone = ZoneId.of(obj.optString("zone", ZoneId.systemDefault().id))
        val capturedAt = runCatching {
            ZonedDateTime.parse(obj.getString("capturedAt")).withZoneSameInstant(zone)
        }.getOrElse { ZonedDateTime.now(zone) }

        val values = mutableMapOf<String, FieldValue>()
        val valuesObject = obj.optJSONObject("values")
        if (valuesObject != null) {
            for (key in valuesObject.keys()) {
                val entry = valuesObject.getJSONObject(key)
                values[key] = FieldValue(
                    value = entry.getDouble("v"),
                    confidence = entry.optDouble("c", 1.0),
                    derived = entry.optBoolean("d", false),
                    rawText = entry.optString("raw", ""),
                )
            }
        }

        val warnings = mutableListOf<String>()
        val warningArray = obj.optJSONArray("warnings")
        if (warningArray != null) {
            for (i in 0 until warningArray.length()) warnings += warningArray.getString(i)
        }

        return Measurement(
            id = obj.optString("id", Measurement.newId(capturedAt)),
            capturedAt = capturedAt,
            sourceLabel = obj.optString("sourceLabel", ""),
            values = values,
            warnings = warnings,
            createdAt = runCatching { Instant.parse(obj.optString("createdAt")) }.getOrDefault(Instant.now()),
            pushedAt = obj.optString("pushedAt").takeIf { it.isNotEmpty() }?.let {
                runCatching { Instant.parse(it) }.getOrNull()
            },
        )
    }
}
