package io.github.rthery.devicechecker.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rthery.devicechecker.MainViewModel
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.data.TestState
import java.time.Instant
import java.time.ZoneId

/** The stock baseline as a test condition: what it holds, or how to get one. */
@Composable
internal fun StockBaselineCard(viewModel: MainViewModel) {
    val stock by viewModel.stock.collectAsStateWithLifecycle()
    val transfer by viewModel.stockTransfer.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importStock) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(viewModel::exportStock) }
    val enabled = !transfer.busy && progress == null
    val baseline = stock
    val scheme = MaterialTheme.colorScheme

    val actions: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val import = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                if (baseline == null) {
                    val warn = TestState.WARN.colors()
                    Button(onClick = import, enabled = enabled, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = warn.container, contentColor = warn.content)) {
                        Text(stringResource(R.string.screen_stock_import))
                    }
                } else {
                    OutlinedButton(onClick = import, enabled = enabled, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.screen_stock_import)) }
                }
                OutlinedButton(onClick = { exporter.launch("stock-${Build.DEVICE}.json") }, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.screen_stock_capture))
                }
            }
            if (transfer.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                val run = progress
                Text(
                    if (run != null) stringResource(R.string.screen_stock_checking, run.current, run.done + 1, run.total) else stringResource(R.string.screen_stock_reading),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            transfer.message?.let { message ->
                Text(message.resolve(), style = MaterialTheme.typography.bodySmall, color = if (transfer.error) scheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
    }

    if (baseline == null) {
        ConditionCard(stringResource(R.string.screen_stock_none_title), stringResource(R.string.screen_stock_none), Icons.Filled.Warning, warnContainer(), onWarnContainer(), actions)
        return
    }
    val date = Instant.ofEpochMilli(baseline.capturedAt).atZone(ZoneId.systemDefault()).toLocalDate()
    val fields = listOfNotNull(
        baseline.cpuFrequencies.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.screen_stock_cpu_ranges, it.size) },
        baseline.sensors.takeIf { it.isNotEmpty() }?.let { pluralStringResource(R.plurals.screen_stock_sensors, it.size, it.size) },
        baseline.hardwareCodecs.takeIf { it.isNotEmpty() }?.let { pluralStringResource(R.plurals.screen_stock_codecs, it.size, it.size) },
    ).joinToString(" · ").ifEmpty { stringResource(R.string.screen_stock_basic_only) }
    ConditionCard(
        stringResource(R.string.screen_stock_title),
        stringResource(R.string.screen_stock_header, baseline.device, baseline.android, date.toString()) + "\n" + fields,
        Icons.Filled.Check, scheme.surfaceContainer, scheme.onSurface, actions,
    )
}
