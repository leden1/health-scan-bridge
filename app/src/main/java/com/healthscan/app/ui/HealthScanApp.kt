package com.healthscan.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthscan.app.viewmodel.ScanViewModel
import com.healthscan.app.viewmodel.Stage

@Composable
fun HealthScanApp(viewModel: ScanViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) viewModel.scan(uri)
    }

    val permissionLauncher = rememberLauncherForActivityResult(viewModel.permissionContract()) { granted ->
        viewModel.onPermissionsResult(granted)
    }

    HealthScanTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("Health Scan Bridge", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Scale screenshot to Health Connect",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StageTabs(
                    stage = state.stage,
                    reviewEnabled = state.draft != null,
                    onSelect = viewModel::show,
                )

                state.busy?.let { BusyRow(it) }
                state.notice?.let { NoticeRow(it, viewModel::dismissNotice) }

                when (state.stage) {
                    Stage.IMPORT -> ImportScreen(
                        availability = state.availability,
                        historyCount = state.history.size,
                        onPickScreenshot = {
                            photoLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        onGrantPermissions = { permissionLauncher.launch(viewModel.permissions) },
                        onOpenHistory = { viewModel.show(Stage.HISTORY) },
                    )

                    Stage.REVIEW -> {
                        val draft = state.draft
                        if (draft == null) {
                            Text(
                                "Nothing to review yet. Import a screenshot first.",
                                modifier = Modifier.padding(16.dp),
                            )
                        } else {
                            ReviewScreen(draft, viewModel)
                        }
                    }

                    Stage.HISTORY -> HistoryScreen(
                        history = state.history,
                        onDelete = { id, alsoHc -> viewModel.deleteMeasurement(id, alsoHc) },
                        onImport = {
                            photoLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun StageTabs(stage: Stage, reviewEnabled: Boolean, onSelect: (Stage) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TabButton("Import", stage == Stage.IMPORT) { onSelect(Stage.IMPORT) }
        TabButton("Review", stage == Stage.REVIEW && reviewEnabled, enabled = reviewEnabled) {
            onSelect(Stage.REVIEW)
        }
        TabButton("History", stage == Stage.HISTORY) { onSelect(Stage.HISTORY) }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Card(
        modifier = Modifier.clickable(enabled = enabled) { onClick() },
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        },
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BusyRow(label: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProgressBar(Modifier.weight(1f))
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProgressBar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.4f)
                .height(6.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
        )
    }
}

@Composable
private fun NoticeRow(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}
