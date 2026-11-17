package com.healthscan.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.healthscan.app.domain.FieldSchema
import com.healthscan.app.domain.FieldSpec
import com.healthscan.app.domain.FieldValue
import com.healthscan.app.domain.NumberFormatter
import com.healthscan.app.viewmodel.Draft
import com.healthscan.app.viewmodel.ScanViewModel
import kotlin.math.roundToInt

@Composable
fun ReviewScreen(draft: Draft, viewModel: ScanViewModel) {
    val edited = remember(draft.measurement.id) { mutableStateMapOf<String, String>() }
    var showOcr by remember(draft.measurement.id) { mutableStateOf(false) }

    val hcFields = FieldSchema.all.filter { it.hcType != null && draft.measurement.values.containsKey(it.key) }
    val localFields = FieldSchema.all.filter { it.hcType == null && draft.measurement.values.containsKey(it.key) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Review measurement", style = MaterialTheme.typography.titleMedium)
                Text("Source: ${draft.measurement.sourceLabel}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Recogniser confidence ${(draft.ocrConfidence * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft.timeText,
                    onValueChange = viewModel::setTimeText,
                    label = { Text("Date and time") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                draft.timeError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.shiftTime(-1) }) { Text("-1 day") }
                    OutlinedButton(onClick = { viewModel.shiftTime(1) }) { Text("+1 day") }
                }
            }
        }

        if (draft.measurement.warnings.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Check these values", style = MaterialTheme.typography.titleMedium)
                    draft.measurement.warnings.forEach { warning ->
                        Text("- $warning", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = viewModel::commit,
                enabled = !draft.pushed,
                modifier = Modifier.weight(1f),
            ) { Text(if (draft.pushed) "Written" else "Write") }
            OutlinedButton(
                onClick = viewModel::saveLocal,
                enabled = !draft.savedLocally,
                modifier = Modifier.weight(1f),
            ) { Text(if (draft.savedLocally) "Saved" else "Save locally") }
            TextButton(onClick = viewModel::discard) { Text("Discard") }
        }
        if (draft.pushed || draft.savedLocally) {
            val status = buildString {
                if (draft.pushed) {
                    append("Already written to Health Connect. Edit a value to write it again.")
                }
                if (draft.savedLocally) {
                    if (isNotEmpty()) append(" ")
                    append("Already saved in this app.")
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        FieldGroup(
            title = "Written to Health Connect",
            subtitle = "Only the ticked fields are written.",
            fields = hcFields,
            draft = draft,
            edited = edited,
            selectable = true,
            onValue = viewModel::updateValue,
            onToggle = viewModel::toggleSelected,
        )

        FieldGroup(
            title = "Kept in this app only",
            subtitle = "No Health Connect data type exists for these.",
            fields = localFields,
            draft = draft,
            edited = edited,
            selectable = false,
            onValue = viewModel::updateValue,
            onToggle = { _, _ -> },
        )

        OutlinedButton(onClick = { showOcr = !showOcr }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showOcr) "Hide recognised text" else "Show recognised text (${draft.ocrLines.size} lines)")
        }
        if (showOcr) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    draft.ocrLines.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldGroup(
    title: String,
    subtitle: String,
    fields: List<FieldSpec>,
    draft: Draft,
    edited: MutableMap<String, String>,
    selectable: Boolean,
    onValue: (String, String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    if (fields.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            fields.forEach { spec ->
                val value = draft.measurement.values[spec.key] ?: return@forEach
                val shown = edited[spec.key] ?: NumberFormatter.format(value.value, spec.decimals)
                FieldRow(
                    spec = spec,
                    value = value,
                    text = shown,
                    onText = { typed ->
                        edited[spec.key] = typed
                        onValue(spec.key, typed)
                    },
                    selectable = selectable,
                    selected = spec.key in draft.selected,
                    onSelected = { onToggle(spec.key, it) },
                )
            }
        }
    }
}

@Composable
private fun FieldRow(
    spec: FieldSpec,
    value: FieldValue,
    text: String,
    onText: (String) -> Unit,
    selectable: Boolean,
    selected: Boolean,
    onSelected: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (selectable) {
            Checkbox(checked = selected, onCheckedChange = onSelected)
        }
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(spec.displayName, style = MaterialTheme.typography.bodyMedium)
                if (value.derived) {
                    Text(
                        "derived",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            Text(
                "${(value.confidence * 100).roundToInt()}% ${value.rawText}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            singleLine = true,
            modifier = Modifier.width(110.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Text(
            spec.unit,
            modifier = Modifier.width(56.dp).padding(start = 6.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
