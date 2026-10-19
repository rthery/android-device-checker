package io.github.rthery.devicechecker.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rthery.devicechecker.MainViewModel
import java.time.Instant
import java.time.ZoneId

@Composable
internal fun StockBaselineCard(viewModel: MainViewModel) {
    val stock by viewModel.stock.collectAsStateWithLifecycle()
    val transfer by viewModel.stockTransfer.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importStock) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(viewModel::exportStock) }
    val enabled = !transfer.busy && progress == null
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Stock baseline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val baseline = stock
            if (baseline == null) {
                Text("No stock capture imported. Import one to compare this ROM with stock.", style = MaterialTheme.typography.bodyMedium)
            } else {
                val date = Instant.ofEpochMilli(baseline.capturedAt).atZone(ZoneId.systemDefault()).toLocalDate()
                Text("${baseline.device} · Android ${baseline.android} · $date", style = MaterialTheme.typography.bodyMedium)
                val fields = listOfNotNull(
                    "${baseline.cpuFrequencies.size} CPU ranges".takeIf { baseline.cpuFrequencies.isNotEmpty() },
                    "${baseline.sensors.size} sensors".takeIf { baseline.sensors.isNotEmpty() },
                    "${baseline.hardwareCodecs.size} codec formats".takeIf { baseline.hardwareCodecs.isNotEmpty() },
                )
                Text(fields.joinToString(" · ").ifEmpty { "Basic device profile only" }, style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(
                onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
            ) { Text("Import stock file") }
            Text("On stock ROM, capture a baseline and keep a copy off the phone before changing ROMs.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = { exporter.launch("stock-${Build.DEVICE}.json") }, enabled = enabled,
                modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            ) { Text("Capture this ROM") }
            if (transfer.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                val run = progress
                Text(if (run != null) "Checking ${run.current} (${run.done + 1}/${run.total})" else "Reading stock data…", style = MaterialTheme.typography.bodySmall)
            }
            transfer.message?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = if (transfer.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
