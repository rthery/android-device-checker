package io.github.rthery.devicechecker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.data.SummaryPart
import io.github.rthery.devicechecker.data.TestBundle
import io.github.rthery.devicechecker.data.TestKind
import io.github.rthery.devicechecker.data.TestState
import io.github.rthery.devicechecker.data.lineFor
import io.github.rthery.devicechecker.data.testSummary
import io.github.rthery.devicechecker.pack.Waiver

@Composable
internal fun summaryText(parts: List<Pair<SummaryPart, Int>>): String =
    parts.map { (part, count) -> pluralStringResource(part.res, count, count) }.joinToString(" · ")

@Composable
internal fun BundleRow(title: String, count: Int, summary: String, state: TestState, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
            .semantics { stateDescription = summary }
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.tests_bundle_open, title), onClick = onOpen)
            .padding(12.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StateDot(state)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(pluralStringResource(R.plurals.tests_bundle_count, count, count, summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
internal fun TestBundleContent(
    bundle: TestBundle,
    states: Map<String, TestState>,
    results: Map<String, CheckResult>,
    waivers: Map<String, Waiver?>,
    runActive: Boolean,
    onDetails: (String) -> Unit,
    onRun: (String) -> Unit,
    onOpenTest: (String) -> Unit,
    onRunChecks: () -> Unit,
) {
    val initialResults = rememberSaveable(bundle.id) { HashMap(bundle.tests.associate { it.id to results[it.id]?.timestamp }) }
    val changed = bundle.tests.any { results[it.id]?.timestamp != initialResults[it.id] }
    val running = bundle.tests.any { states[it.id] == TestState.RUNNING || states[it.id] == TestState.QUEUED }
    val automatic = remember(bundle) { bundle.tests.count { it.kind == TestKind.AUTO } }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "header", contentType = "header") {
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bundle.title.resolve(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                val summary = summaryText(testSummary(bundle.tests, states))
                Text(if (changed && !running) stringResource(R.string.tests_bundle_updated, summary) else summary, style = MaterialTheme.typography.bodyMedium)
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (automatic > 0) TestActionButton(
                    label = pluralStringResource(R.plurals.tests_bundle_run_checks, automatic, automatic), handsOn = false, onClick = onRunChecks,
                    enabled = !runActive, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        itemsIndexed(bundle.tests, key = { _, test -> test.id }, contentType = { _, _ -> "test" }) { index, test ->
            val state = states.getValue(test.id)
            TestRow(
                test = test, state = state, line = lineFor(test, state, results[test.id], waivers[test.id]),
                first = index == 0, last = index == bundle.tests.lastIndex, runActive = runActive,
                onOpen = { onDetails(test.id) },
                onAction = {
                    when {
                        test.kind == TestKind.AUTO -> onRun(test.id)
                        test.isRequirementOnly -> onDetails(test.id)
                        else -> onOpenTest(test.id)
                    }
                },
            )
        }
    }
}
