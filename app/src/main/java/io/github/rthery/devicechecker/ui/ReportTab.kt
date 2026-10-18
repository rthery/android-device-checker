package io.github.rthery.devicechecker.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rthery.devicechecker.MainViewModel
import io.github.rthery.devicechecker.data.TestKind
import io.github.rthery.devicechecker.data.TestState
import io.github.rthery.devicechecker.data.stateOf

private val REPORT_STATES = listOf(TestState.PASS, TestState.FAIL, TestState.WARN, TestState.NEEDS, TestState.NOT_RUN, TestState.NA, TestState.WAIVED)

@Composable
fun ReportTab(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val results by viewModel.results.collectAsStateWithLifecycle()
    val verdicts by viewModel.verdicts.collectAsStateWithLifecycle()
    val deviceInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val catalog = viewModel.catalog.collectAsStateWithLifecycle().value
    LaunchedEffect(results, verdicts) { viewModel.refreshHistory() }

    val tests = catalog.all.filter { it.kind != TestKind.OFF_DEVICE }
    val counts = tests.groupingBy { stateOf(it, results, verdicts, deviceInfo.lineageBased, null, catalog.waiverFor(it)) }.eachCount()
    val total = tests.size.coerceAtLeast(1)

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Report", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${tests.size} tests on the phone, saved after every test and verdict", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                REPORT_STATES.forEach { state ->
                    val count = counts[state] ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(state.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(92.dp))
                        Box(
                            Modifier
                                .weight(1f)
                                .height(10.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(5.dp)),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(count.toFloat() / total)
                                    .background(state.colors().border, RoundedCornerShape(5.dp)),
                            )
                        }
                        Text("$count", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(28.dp))
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { shareText(context, "Device requirements report", viewModel.markdownReport()) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Text("Share as text")
            }
            OutlinedButton(onClick = { shareText(context, "Device requirements report (JSON)", viewModel.jsonReport()) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Text("Share JSON")
            }
        }
        Text(
            "Also written to ${viewModel.reportsDir.absolutePath} for adb pull.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (history.isNotEmpty()) {
            Text("History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                history.forEach { entry ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(entry.generatedAt.take(16).replace('T', ' '), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Requirements: " + listOf("PASS" to "met", "FAIL" to "failed", "WARN" to "to check").mapNotNull { (key, word) -> entry.summary[key]?.let { "$it $word" } }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun shareText(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, subject))
}
