package com.healthscan.app.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healthscan.app.domain.FieldSchema
import com.healthscan.app.domain.FieldValue
import com.healthscan.app.domain.Measurement
import com.healthscan.app.health.HcAvailability
import com.healthscan.app.health.HealthConnectRepository
import com.healthscan.app.ocr.ImageLoader
import com.healthscan.app.ocr.OcrEngine
import com.healthscan.app.parse.Candidate
import com.healthscan.app.parse.Consistency
import com.healthscan.app.parse.MeasurementParser
import com.healthscan.app.store.MeasurementStore
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Stage { IMPORT, REVIEW, HISTORY }

data class Draft(
    val measurement: Measurement,
    val selected: Set<String>,
    val candidates: Map<String, Candidate>,
    val ocrLines: List<String>,
    val ocrConfidence: Float,
    val timeText: String,
    val timeError: String? = null,
)

data class UiState(
    val stage: Stage = Stage.IMPORT,
    val busy: String? = null,
    val draft: Draft? = null,
    val history: List<Measurement> = emptyList(),
    val availability: HcAvailability? = null,
    val notice: String? = null,
)

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "HealthScanScan"
    }

    private val store = MeasurementStore(application)
    private val repository = HealthConnectRepository(application)
    private val parser = MeasurementParser()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val permissions: Set<String> get() = repository.permissions
    fun permissionContract() = repository.permissionContract()

    private var engine: OcrEngine? = null
    private var scanJob: Job? = null

    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    init {
        refreshAvailability()
        reloadHistory()
    }

    override fun onCleared() {
        engine?.close()
        engine = null
        super.onCleared()
    }

    fun show(stage: Stage) = _state.update { it.copy(stage = stage) }

    fun scan(uri: Uri) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _state.update { it.copy(busy = "Reading image…") }
            val resolver = getApplication<Application>().contentResolver
            val loaded = runCatching { withContext(Dispatchers.Default) { ImageLoader.load(resolver, uri) } }
                .getOrElse {
                    Log.e(TAG, "image load failed", it)
                    _state.update { state -> state.copy(busy = null, notice = "Could not open image: ${it.message}") }
                    return@launch
                }

            _state.update { it.copy(busy = "Recognising text…") }
            val activeEngine = engine ?: OcrEngine().also { engine = it }
            val outcome = runCatching {
                withContext(Dispatchers.Default) {
                    val ocr = activeEngine.recognize(loaded.bitmap) { tile, total ->
                        _state.update { state -> state.copy(busy = "Recognising text ($tile/$total)…") }
                    }
                    parser.parse(ocr).also {
                        Log.i(
                            TAG,
                            "recognised ${ocr.lines.size} lines in ${loaded.bitmap.width}x${loaded.bitmap.height}, " +
                                "parsed ${it.candidates.size} of ${FieldSchema.all.size} fields",
                        )
                    }
                }
            }.getOrElse {
                loaded.bitmap.recycle()
                Log.e(TAG, "ocr/parse failed", it)
                _state.update { state -> state.copy(busy = null, notice = "Text recognition failed: ${it.message}") }
                return@launch
            }
            loaded.bitmap.recycle()

            val parsed = LinkedHashMap<String, FieldValue>()
            val candidates = LinkedHashMap<String, Candidate>()
            for (candidate in outcome.candidates) {
                parsed[candidate.spec.key] = FieldValue(
                    value = candidate.value,
                    confidence = candidate.confidence,
                    rawText = "${candidate.labelLine} -> ${candidate.valueText} (${candidate.geometry})",
                )
                candidates[candidate.spec.key] = candidate
            }

            val reconciled = Consistency.reconcile(parsed)
            for ((key, value) in reconciled.values) {
                if (!parsed.containsKey(key)) parsed[key] = value
            }

            val zone = ZoneId.systemDefault()
            val capturedAt = outcome.dateTime?.zonedDateTime
                ?: ZonedDateTime.now(zone).withSecond(0).withNano(0)

            val warnings = reconciled.warnings + if (outcome.dateTime == null) {
                listOf("No date found in the screenshot, using the current date and time.")
            } else {
                emptyList()
            }

            val measurement = Measurement(
                id = Measurement.newId(capturedAt),
                capturedAt = capturedAt,
                sourceLabel = loaded.sourceLabel,
                values = parsed,
                warnings = warnings,
            )

            val selected = FieldSchema.all
                .filter { it.hcType != null && parsed.containsKey(it.key) }
                .map { it.key }
                .toSet()

            store.save(measurement)
            _state.update { state ->
                state.copy(
                    busy = null,
                    stage = Stage.REVIEW,
                    history = store.loadAll(),
                    draft = Draft(
                        measurement = measurement,
                        selected = selected,
                        candidates = candidates,
                        ocrLines = outcome.rawLines,
                        ocrConfidence = outcome.ocrConfidence,
                        timeText = capturedAt.format(timeFormat),
                    ),
                )
            }
        }
    }

    fun updateValue(key: String, text: String) {
        val draft = _state.value.draft ?: return
        if (text.isBlank()) {
            _state.update { state ->
                val current = state.draft ?: return@update state
                state.copy(draft = current.copy(measurement = current.measurement.without(key)))
            }
            return
        }
        val parsed = com.healthscan.app.parse.NumberParser.parseSingle(text)
        if (parsed == null) {
            _state.update { state -> state.copy(notice = "Not a number: $text") }
            return
        }
        _state.update { state ->
            val current = state.draft ?: return@update state
            val existing = current.measurement.value(key)
            val spec = FieldSchema.byKey[key]
            val value = if (spec != null && spec.kind == com.healthscan.app.domain.ValueKind.LENGTH_M &&
                parsed > 2.5 && parsed <= 250.0
            ) {
                parsed / 100.0
            } else {
                parsed
            }
            val field = FieldValue(
                value = value,
                confidence = 1.0,
                rawText = if (existing != null) "edited" else "manual",
            )
            val updated = current.measurement.withValue(key, field)
            current.copy(
                measurement = updated,
                selected = current.selected + key,
            ).let { state.copy(draft = it) }
        }
    }

    fun toggleSelected(key: String, on: Boolean) {
        _state.update { state ->
            val current = state.draft ?: return@update state
            val selected = if (on) current.selected + key else current.selected - key
            current.copy(selected = selected).let { state.copy(draft = it) }
        }
    }

    fun shiftTime(days: Long) {
        _state.update { state ->
            val current = state.draft ?: return@update state
            val shifted = current.measurement.capturedAt.plusDays(days)
            current.copy(
                measurement = current.measurement.copy(capturedAt = shifted),
                timeText = shifted.format(timeFormat),
                timeError = null,
            ).let { state.copy(draft = it) }
        }
    }

    fun setTimeText(text: String) {
        _state.update { state ->
            val current = state.draft ?: return@update state
            val parsed = com.healthscan.app.parse.DateParser.parseTyped(text, ZoneId.systemDefault())
            if (parsed == null) {
                current.copy(timeText = text, timeError = "Use 2026-11-14 09:02 or 14.11.2026 09:02")
                    .let { state.copy(draft = it) }
            } else {
                current.copy(
                    measurement = current.measurement.copy(capturedAt = parsed.zonedDateTime),
                    timeText = text,
                    timeError = null,
                ).let { state.copy(draft = it) }
            }
        }
    }

    fun commit() {
        val draft = _state.value.draft ?: return
        val keys = draft.selected.filter { FieldSchema.byKey[it]?.hcType != null }.toSet()
        if (keys.isEmpty()) {
            _state.update { it.copy(notice = "No Health Connect fields selected.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = "Writing to Health Connect…") }
            val rangeWarnings = Consistency.rangeWarnings(draft.measurement.values)
            val warnings = (draft.measurement.warnings + rangeWarnings).distinct()
            val outcome = runCatching {
                repository.write(draft.measurement.copy(warnings = warnings), keys, System.currentTimeMillis())
            }.getOrElse {
                _state.update { state ->
                    state.copy(busy = null, notice = "Health Connect error: ${it.message}")
                }
                return@launch
            }
            val saved = draft.measurement.copy(
                warnings = warnings,
                pushedAt = Instant.now(),
            )
            store.save(saved)
            val message = buildString {
                if (outcome.written.isNotEmpty()) append("Written to Health Connect: ${outcome.written.joinToString(", ")}")
                if (outcome.failed.isNotEmpty()) {
                    if (isNotEmpty()) append(". ")
                    append("Failed: " + outcome.failed.entries.joinToString(", ") { "${it.key} (${it.value})" })
                }
            }
            val availability = repository.availability()
            _state.update { state ->
                val current = state.draft
                state.copy(
                    busy = null,
                    notice = message.ifBlank { "Nothing written." },
                    draft = current?.copy(measurement = saved),
                    history = store.loadAll(),
                    availability = availability,
                )
            }
        }
    }

    fun saveLocal() {
        val draft = _state.value.draft ?: return
        val warnings = Consistency.reconcile(draft.measurement.values).warnings
        store.save(draft.measurement.copy(warnings = (draft.measurement.warnings + warnings).distinct()))
        _state.update { state ->
            state.copy(notice = "Saved locally.", history = store.loadAll())
        }
    }

    fun discard() {
        val draft = _state.value.draft ?: return
        store.delete(draft.measurement.id)
        _state.update { state ->
            state.copy(draft = null, stage = Stage.IMPORT, history = store.loadAll())
        }
    }

    fun reloadHistory() {
        _state.update { it.copy(history = store.loadAll()) }
    }

    fun deleteMeasurement(id: String, alsoHealthConnect: Boolean) {
        viewModelScope.launch {
            val measurement = store.loadAll().firstOrNull { it.id == id }
            if (measurement == null) return@launch
            if (alsoHealthConnect) {
                runCatching { repository.deleteAt(measurement, measurement.hcKeys) }
            }
            store.delete(id)
            _state.update { state ->
                state.copy(
                    history = store.loadAll(),
                    notice = if (alsoHealthConnect) "Deleted locally and in Health Connect." else "Deleted locally.",
                )
            }
        }
    }

    fun refreshAvailability() {
        viewModelScope.launch {
            val availability = repository.availability()
            _state.update { it.copy(availability = availability) }
        }
    }

    fun onPermissionsResult(granted: Set<String>) {
        refreshAvailability()
        val missing = permissions - granted
        _state.update { state ->
            state.copy(
                notice = if (missing.isEmpty()) {
                    "All Health Connect permissions granted."
                } else {
                    "Still missing ${missing.size} Health Connect permission(s)."
                },
            )
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }
}
