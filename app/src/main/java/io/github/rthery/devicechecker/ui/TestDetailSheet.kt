package io.github.rthery.devicechecker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rthery.devicechecker.MainViewModel
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.catalog.RequirementCatalog
import io.github.rthery.devicechecker.checks.Access
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.data.TestKind
import io.github.rthery.devicechecker.data.TestState
import io.github.rthery.devicechecker.data.UnifiedTest
import io.github.rthery.devicechecker.data.Verdict
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val WHEN_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())

@Composable
internal fun TestDetailContent(
    viewModel: MainViewModel,
    test: UnifiedTest,
    state: TestState,
    result: CheckResult?,
    runActive: Boolean,
    onDismiss: () -> Unit,
    onOpenTest: (String) -> Unit,
) {
    val catalog = viewModel.catalog.collectAsStateWithLifecycle().value
    val waiver = catalog.waiverFor(test)
    val initialTimestamp by rememberSaveable(test.id) { mutableStateOf(result?.timestamp) }
    var requested by rememberSaveable(test.id) { mutableStateOf(false) }
    var runTimestamp by rememberSaveable(test.id) { mutableStateOf(result?.timestamp) }
    var showingProgress by rememberSaveable(test.id) { mutableStateOf(false) }
    var showingRawOutput by rememberSaveable(test.id) { mutableStateOf(false) }
    val completed = result != null && result.timestamp != initialTimestamp
    LaunchedEffect(requested, state, result?.timestamp) {
        if (requested || state == TestState.RUNNING || state == TestState.QUEUED) {
            showingProgress = true
            if (state != TestState.RUNNING && state != TestState.QUEUED && result != null && result.timestamp != runTimestamp) {
                delay(600)
                showingProgress = false
                requested = false
            }
        }
    }
    val detailsListState = rememberLazyListState()
    val outputListState = rememberLazyListState()
    BackHandler(enabled = showingRawOutput) { showingRawOutput = false }
    if (showingRawOutput && result != null) {
        val chunks = remember(result.details) { result.details.chunked(2000) }
        Column {
            TextButton(onClick = { showingRawOutput = false }) { Text(stringResource(R.string.tests_detail_back)) }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
                state = outputListState, contentPadding = PaddingValues(20.dp),
            ) {
                itemsIndexed(chunks, key = { index, _ -> index }, contentType = { _, _ -> "raw" }) { _, chunk ->
                    Text(chunk, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
        return
    }
    LazyColumn(
        state = detailsListState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "title", contentType = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val colors = state.colors()
                    Chip(stringResource(state.labelRes), colors.container, colors.content, colors.border)
                    Chip(stringResource(test.kind.labelRes))
                    LevelPill(test.level, Modifier.align(Alignment.CenterVertically))
                }
                Text(test.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }

        item(key = "requirements", contentType = "section") {
            Section(stringResource(if (test.requirements.size > 1) R.string.tests_detail_requirements else R.string.tests_detail_requirement)) {
                test.requirements.forEach { requirement ->
                    Text("${requirement.level.label}: ${requirement.text}", style = MaterialTheme.typography.bodyLarge)
                    requirement.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                test.scopeLabel?.let { Hint(it) }
                Hint(
                    test.source?.let { stringResource(R.string.tests_detail_from_pack, it) }
                        ?: stringResource(R.string.tests_detail_from_reference, test.requirements.firstNotNullOfOrNull { it.reference } ?: RequirementCatalog.SOURCE_TITLE)
                )
                test.requirements.firstNotNullOfOrNull { catalog.replacedBy(it.id) }?.let { replaced ->
                    Hint(stringResource(R.string.tests_detail_replaces, replaced.level.label, replaced.text))
                }
            }
        }

        waiver?.let {
            item(key = "waiver", contentType = "section") {
                Section(stringResource(R.string.tests_detail_exception)) {
                    Text(it.reason, style = MaterialTheme.typography.bodyLarge)
                    it.reference?.let { reference -> Hint(reference) }
                    test.requirements.firstNotNullOfOrNull { requirement -> catalog.waivedBy(requirement.id) }?.let { pack -> Hint(stringResource(R.string.tests_detail_waived_by, pack)) }
                }
            }
        }

        if (test.how.isNotEmpty()) {
            item(key = "how", contentType = "section") {
                Section(stringResource(if (test.kind == TestKind.OFF_DEVICE || test.isRequirementOnly) R.string.tests_detail_how_to_check else R.string.tests_detail_what_app_does)) {
                    test.how.forEachIndexed { index, step ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                            Box(
                                Modifier.size(22.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${index + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        if (showingProgress || state == TestState.RUNNING || state == TestState.QUEUED) {
            item(key = "progress", contentType = "section") {
                Section(stringResource(R.string.tests_detail_running, test.title)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(test.how.firstOrNull() ?: stringResource(R.string.tests_detail_please_wait))
                }
            }
        }

        if (result != null && !showingProgress) {
            item(key = "result", contentType = "section") {
                Section(stringResource(if (completed) R.string.tests_detail_done else R.string.tests_detail_last_result, WHEN_FORMAT.format(Instant.ofEpochMilli(result.timestamp)))) {
                    Text(result.summary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    if (result.details.isNotBlank()) {
                        TextButton(onClick = { showingRawOutput = true }) {
                            Text(pluralStringResource(R.plurals.tests_detail_raw_output, result.details.length, result.details.length))
                        }
                    }
                    when {
                        result.byTester -> Hint(stringResource(R.string.tests_detail_by_tester))
                        result.access == Access.SHIZUKU -> Hint(stringResource(R.string.tests_detail_through_shizuku))
                    }
                }
            }
        }

        if (state == TestState.NEEDS && !showingProgress && test.kind == TestKind.AUTO) {
            item(key = "next", contentType = "section") {
                val related = test.requirements.flatMap { it.testIds }.distinct().filter { it != test.id }.mapNotNull { catalog.interactiveById[it] }
                Section(stringResource(R.string.tests_detail_next_step)) {
                    if (related.isEmpty()) {
                        Text(stringResource(R.string.tests_detail_compare))
                    } else {
                        related.forEach { next ->
                            Text(if (next.id == "nfc.tag") stringResource(R.string.tests_detail_nfc_hint) else next.summary)
                            TestActionButton(label = stringResource(handsOnLabel(next.id, next.manual)), handsOn = true,
                                onClick = { onOpenTest(next.id) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }

        item(key = "actions", contentType = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    test.kind == TestKind.AUTO -> TestActionButton(
                        label = stringResource(if (showingProgress) R.string.tests_detail_running_ellipsis else if (result == null) R.string.tests_detail_run_check else R.string.tests_detail_run_again),
                        handsOn = false,
                        onClick = {
                            runTimestamp = result?.timestamp
                            requested = true
                            showingProgress = true
                            viewModel.runOne(test.id)
                        },
                        enabled = !runActive && !showingProgress,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    )
                    !test.isRequirementOnly -> TestActionButton(
                        label = stringResource(handsOnLabel(test.id, test.kind == TestKind.MANUAL)),
                        handsOn = true, onClick = { onOpenTest(test.id) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Verdict.PASS to R.string.tests_detail_mark_passed, Verdict.FAIL to R.string.tests_detail_mark_failed, Verdict.NOT_APPLICABLE to R.string.tests_detail_not_applicable).forEach { (verdict, label) ->
                        OutlinedButton(
                            onClick = {
                                viewModel.markTest(test, verdict)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        ) { Text(stringResource(label), style = MaterialTheme.typography.labelMedium, maxLines = 2) }
                    }
                }
                if (result?.byTester == true || (test.isRequirementOnly && state != TestState.NOT_RUN)) {
                    TextButton(onClick = {
                        viewModel.markTest(test, Verdict.UNTESTED)
                        onDismiss()
                    }) { Text(stringResource(R.string.tests_detail_clear_verdict)) }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Chip(
    text: String,
    container: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
    content: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    border: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.outline,
) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = content,
        modifier = Modifier
            .background(container, shape)
            .border(1.dp, border, shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
