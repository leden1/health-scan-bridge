package com.healthscan.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.healthscan.app.domain.FieldSchema
import com.healthscan.app.domain.Measurement
import java.time.format.DateTimeFormatter

private val historyTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
fun HistoryScreen(
    history: List<Measurement>,
    onDelete: (id: String, alsoHealthConnect: Boolean) -> Unit,
    onImport: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (history.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("No saved measurements yet", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                        Text("Import screenshot")
                    }
                }
            }
            return
        }

        history.forEach { measurement ->
            MeasurementCard(measurement, onDelete)
        }
    }
}

@Composable
private fun MeasurementCard(measurement: Measurement, onDelete: (String, Boolean) -> Unit) {
    val present = FieldSchema.measured.filter { measurement.values.containsKey(it.key) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(measurement.capturedAt.format(historyTimeFormat), style = MaterialTheme.typography.titleMedium)
            Text(
                if (measurement.pushedAt != null) "Written to Health Connect" else "Local only",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            present.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { spec ->
                        Text(
                            "${spec.displayName}: ${FieldSchema.format(spec.key, measurement.values.getValue(spec.key).value)}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (row.size == 1) {
                        Text("", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            measurement.warnings.forEach { warning ->
                Text("! $warning", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onDelete(measurement.id, false) }) { Text("Delete local") }
                if (measurement.pushedAt != null) {
                    TextButton(onClick = { onDelete(measurement.id, true) }) { Text("Delete + Health Connect") }
                }
            }
        }
    }
}
