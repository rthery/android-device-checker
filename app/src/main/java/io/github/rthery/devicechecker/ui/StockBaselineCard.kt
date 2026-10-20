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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rthery.devicechecker.MainViewModel
import io.github.rthery.devicechecker.R
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
            Text(stringResource(R.string.screen_stock_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val baseline = stock
            if (baseline == null) {
                Text(stringResource(R.string.screen_stock_none), style = MaterialTheme.typography.bodyMedium)
            } else {
                val date = Instant.ofEpochMilli(baseline.capturedAt).atZone(ZoneId.systemDefault()).toLocalDate()
                Text(stringResource(R.string.screen_stock_header, baseline.device, baseline.android, date.toString()), style = MaterialTheme.typography.bodyMedium)
                val fields = listOfNotNull(
                    baseline.cpuFrequencies.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.screen_stock_cpu_ranges, it.size) },
                    baseline.sensors.takeIf { it.isNotEmpty() }?.let { pluralStringResource(R.plurals.screen_stock_sensors, it.size, it.size) },
                    baseline.hardwareCodecs.takeIf { it.isNotEmpty() }?.let { pluralStringResource(R.plurals.screen_stock_codecs, it.size, it.size) },
                )
                val basicOnly = stringResource(R.string.screen_stock_basic_only)
                Text(fields.joinToString(" · ").ifEmpty { basicOnly }, style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(
                onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
            ) { Text(stringResource(R.string.screen_stock_import)) }
            Text(stringResource(R.string.screen_stock_capture_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = { exporter.launch("stock-${Build.DEVICE}.json") }, enabled = enabled,
                modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            ) { Text(stringResource(R.string.screen_stock_capture)) }
            if (transfer.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                val run = progress
                Text(if (run != null) stringResource(R.string.screen_stock_checking, run.current, run.done + 1, run.total) else stringResource(R.string.screen_stock_reading), style = MaterialTheme.typography.bodySmall)
            }
            transfer.message?.let { message ->
                Text(message.resolve(), style = MaterialTheme.typography.bodySmall, color = if (transfer.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
