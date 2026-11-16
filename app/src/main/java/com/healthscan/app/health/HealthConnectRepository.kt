package com.healthscan.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.kilocaloriesPerDay
import androidx.health.connect.client.units.kilograms
import androidx.health.connect.client.units.meters
import androidx.health.connect.client.units.percent
import com.healthscan.app.domain.FieldSchema
import com.healthscan.app.domain.FieldSpec
import com.healthscan.app.domain.HcType
import com.healthscan.app.domain.Measurement
import java.time.Instant
import java.time.temporal.ChronoUnit

data class WriteOutcome(val written: List<String>, val failed: Map<String, String>) {
    val isEmpty: Boolean get() = written.isEmpty() && failed.isEmpty()
}

sealed class HcAvailability {
    data object Unsupported : HcAvailability()
    data object NeedsProvider : HcAvailability()
    data class Available(val grantedPermissions: Set<String>, val missingPermissions: Set<String>) : HcAvailability()
    data class Error(val message: String) : HcAvailability()
}

/**
 * Writes the confirmed body composition values into Health Connect. Records carry a client record id
 * derived from the measurement timestamp so rescanning or re-confirming the same screenshot time
 * updates instead of duplicating the data.
 */
class HealthConnectRepository(private val context: Context) {

    val permissions: Set<String> = buildSet {
        for (type in WRITABLE_TYPES) {
            add(HealthPermission.getWritePermission(typeRecordClass(type)))
            add(HealthPermission.getReadPermission(typeRecordClass(type)))
        }
    }

    fun availabilityStatus(): Int = runCatching {
        HealthConnectClient.getSdkStatus(context)
    }.getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)

    suspend fun availability(): HcAvailability = when (availabilityStatus()) {
        HealthConnectClient.SDK_AVAILABLE -> runCatching {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            HcAvailability.Available(granted, permissions - granted)
        }.getOrElse { HcAvailability.Error(it.message ?: "Health Connect unavailable") }

        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HcAvailability.NeedsProvider
        else -> HcAvailability.Unsupported
    }

    fun settingsIntent(): android.content.Intent =
        android.content.Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)

    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun write(measurement: Measurement, keys: Set<String>, version: Long): WriteOutcome {
        val written = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()

        val instant = measurement.capturedAt.toInstant()
        val offset = measurement.capturedAt.offset

        for (key in keys) {
            val spec = FieldSchema.byKey[key] ?: continue
            val hcType = spec.hcType ?: continue
            val fieldValue = measurement.values[key] ?: continue
            val record = runCatching {
                buildRecord(hcType, spec, fieldValue.value, instant, offset, measurement.id, version)
            }.getOrElse {
                failed[spec.displayName] = it.message ?: "invalid value"
                continue
            }
            try {
                HealthConnectClient.getOrCreate(context).insertRecords(listOf(record))
                written += spec.displayName
            } catch (error: Exception) {
                failed[spec.displayName] = error.message ?: error.javaClass.simpleName
            }
        }
        return WriteOutcome(written, failed)
    }

    suspend fun deleteAt(measurement: Measurement, keys: Set<String>): WriteOutcome {
        val deleted = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()
        val client = HealthConnectClient.getOrCreate(context)
        val from = measurement.capturedAt.toInstant().minus(2, ChronoUnit.MINUTES)
        val to = measurement.capturedAt.toInstant().plus(2, ChronoUnit.MINUTES)
        for (key in keys) {
            val spec = FieldSchema.byKey[key] ?: continue
            val hcType = spec.hcType ?: continue
            try {
                client.deleteRecords(recordType = typeRecordClass(hcType), timeRangeFilter = TimeRangeFilter.between(from, to))
                deleted += spec.displayName
            } catch (error: Exception) {
                failed[spec.displayName] = error.message ?: error.javaClass.simpleName
            }
        }
        return WriteOutcome(deleted, failed)
    }

    suspend fun readWeights(since: Instant, until: Instant): List<Pair<Instant, Double>> = runCatching {
        val client = HealthConnectClient.getOrCreate(context)
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.between(since, until),
                ascendingOrder = false,
                pageSize = 40,
            ),
        )
        response.records.map { it.time to it.weight.inKilograms }
    }.getOrDefault(emptyList())

    private fun buildRecord(
        type: HcType,
        spec: FieldSpec,
        value: Double,
        instant: Instant,
        offset: java.time.ZoneOffset,
        measurementId: String,
        version: Long,
    ): Record {
        val metadata = Metadata.manualEntry(
            clientRecordId = "$measurementId:${spec.key}",
            clientRecordVersion = version,
        )
        return when (type) {
            HcType.WEIGHT -> WeightRecord(instant, offset, value.kilograms, metadata)
            HcType.HEIGHT -> HeightRecord(instant, offset, value.meters, metadata)
            HcType.BODY_FAT -> BodyFatRecord(instant, offset, value.percent, metadata)
            HcType.LEAN_BODY_MASS -> LeanBodyMassRecord(instant, offset, value.kilograms, metadata)
            HcType.BODY_WATER_MASS -> BodyWaterMassRecord(instant, offset, value.kilograms, metadata)
            HcType.BONE_MASS -> BoneMassRecord(instant, offset, value.kilograms, metadata)
            HcType.BMR -> BasalMetabolicRateRecord(instant, offset, value.kilocaloriesPerDay, metadata)
        }
    }

    private fun typeRecordClass(type: HcType): kotlin.reflect.KClass<out Record> = when (type) {
        HcType.WEIGHT -> WeightRecord::class
        HcType.HEIGHT -> HeightRecord::class
        HcType.BODY_FAT -> BodyFatRecord::class
        HcType.LEAN_BODY_MASS -> LeanBodyMassRecord::class
        HcType.BODY_WATER_MASS -> BodyWaterMassRecord::class
        HcType.BONE_MASS -> BoneMassRecord::class
        HcType.BMR -> BasalMetabolicRateRecord::class
    }

    companion object {
        val WRITABLE_TYPES: List<HcType> = listOf(
            HcType.WEIGHT,
            HcType.HEIGHT,
            HcType.BODY_FAT,
            HcType.LEAN_BODY_MASS,
            HcType.BODY_WATER_MASS,
            HcType.BONE_MASS,
            HcType.BMR,
        )
    }
}
