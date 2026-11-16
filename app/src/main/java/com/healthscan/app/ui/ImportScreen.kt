package com.healthscan.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.healthscan.app.domain.FieldSchema
import com.healthscan.app.health.HcAvailability

@Composable
fun ImportScreen(
    availability: HcAvailability?,
    historyCount: Int,
    onPickScreenshot: () -> Unit,
    onGrantPermissions: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Import a scale screenshot", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pick a full screenshot of your body composition scale app. Text is recognised on the " +
                        "device with ML Kit, nothing is uploaded.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onPickScreenshot, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose screenshot")
                }
                Text(
                    "You can also share a screenshot into Health Scan Bridge from the gallery share menu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HealthConnectStatusCard(availability, onGrantPermissions)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What is written to Health Connect", style = MaterialTheme.typography.titleMedium)
                Text(
                    FieldSchema.all.filter { it.hcType != null }
                        .joinToString(", ") { it.displayName },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Everything else (BMI, muscle and skeletal muscle mass, protein, visceral fat, body " +
                        "score, body age, waist-to-hip ratio and the suggestion targets) has no Health " +
                        "Connect data type, so it is kept in the app history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                    Text("Open history ($historyCount saved)")
                }
            }
        }
    }
}

@Composable
private fun HealthConnectStatusCard(availability: HcAvailability?, onGrantPermissions: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Health Connect", style = MaterialTheme.typography.titleMedium)
            when (availability) {
                null -> Text("Checking…", style = MaterialTheme.typography.bodyMedium)

                is HcAvailability.Unsupported -> Text(
                    "Health Connect is not available on this device or Android version.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                is HcAvailability.NeedsProvider -> Text(
                    "Install or update the Health Connect app, then restart Health Scan Bridge.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                is HcAvailability.Error -> Text(
                    "Health Connect check failed: ${availability.message}",
                    style = MaterialTheme.typography.bodyMedium,
                )

                is HcAvailability.Available -> {
                    if (availability.missingPermissions.isEmpty()) {
                        Text("Connected and all permissions granted.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            "Ready, but ${availability.missingPermissions.size} permission(s) still need to be granted.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = onGrantPermissions, modifier = Modifier.fillMaxWidth()) {
                            Text("Grant permissions")
                        }
                    }
                }
            }
        }
    }
}
