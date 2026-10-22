package io.github.rthery.devicechecker.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rthery.devicechecker.MainViewModel
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.data.HistoryEntry
import io.github.rthery.devicechecker.data.TestState
import java.io.File
import kotlinx.coroutines.launch

private enum class ReportFormat(val badge: String, val label: Int) {
    HTML("HTML", R.string.screen_report_format_html), MARKDOWN("MD", R.string.screen_report_format_markdown), JSON("{ }", R.string.screen_report_format_json),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportTab(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val results by viewModel.results.collectAsStateWithLifecycle()
    val verdicts by viewModel.verdicts.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf(false) }
    val coroutines = rememberCoroutineScope()
    LaunchedEffect(results, verdicts) { viewModel.refreshHistory() }
    val subject = stringResource(R.string.screen_report_share_subject)

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.screen_report_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.screen_report_share_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Button(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.screen_report_share))
                }
                Text(
                    stringResource(R.string.screen_report_written_to, viewModel.reportsDir.absolutePath),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (history.isNotEmpty()) {
            Text(stringResource(R.string.screen_report_history), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { history.forEach { HistoryRow(it) } }
        }
    }

    if (choosing) {
        ModalBottomSheet(onDismissRequest = { choosing = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
                Text(
                    stringResource(R.string.screen_report_share_as), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                )
                ReportFormat.entries.forEach { format ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            choosing = false
                            when (format) {
                                ReportFormat.HTML -> coroutines.launch {
                                    runCatching { viewModel.htmlReportFile() }
                                        .onSuccess { shareFile(context, subject, it, "text/html") }
                                        .onFailure { Toast.makeText(context, R.string.screen_report_html_failed, Toast.LENGTH_LONG).show() }
                                }
                                ReportFormat.MARKDOWN -> shareText(context, subject, viewModel.markdownReport())
                                ReportFormat.JSON -> shareText(context, subject, viewModel.jsonReport())
                            }
                        }.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Text(format.badge, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Text(stringResource(format.label), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

private val HISTORY_COUNTS = listOf(
    Triple("FAIL", Icons.Filled.Close, TestState.FAIL),
    Triple("WARN", Icons.Filled.Warning, TestState.WARN),
    Triple("TO_VERIFY", Icons.Filled.Person, TestState.NEEDS),
    Triple("PASS", Icons.Filled.Check, TestState.PASS),
)

@Composable
private fun HistoryRow(entry: HistoryEntry) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.generatedAt.take(16).replace('T', ' '), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                entry.build?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HISTORY_COUNTS.forEach { (key, icon, state) -> HistoryCount(entry.summary[key] ?: 0, icon, state) }
                if (entry.rooted) StateChip(stringResource(R.string.tests_adb_root), warning = true, onClick = {})
            }
        }
    }
}

@Composable
private fun HistoryCount(count: Int, icon: ImageVector, state: TestState) {
    val label = stringResource(state.labelRes)
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "$count $label" }, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = state.colors().border)
        Spacer(Modifier.width(3.dp))
        Text("$count", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = state.colors().border)
    }
}

fun shareText(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, subject))
}

private fun shareFile(context: Context, subject: String, file: File, type: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(type)
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(subject, uri)
    context.startActivity(Intent.createChooser(send, subject))
}
