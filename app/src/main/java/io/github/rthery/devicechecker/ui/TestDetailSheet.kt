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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rthery.devicechecker.MainViewModel
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.checks.Access
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.data.TestKind
import io.github.rthery.devicechecker.data.TestState
import io.github.rthery.devicechecker.data.UnifiedTest
import io.github.rthery.devicechecker.data.Verdict
import io.github.rthery.devicechecker.data.lineFor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val WHEN_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())
private const val PREVIEW_LINES = 6

/** One answered step as written by the step list summary: "Works: Switch USB to file transfer." */
internal data class StepLine(val answer: TestState, val text: String)

private val ANSWER_STATES = mapOf("Works" to TestState.PASS, "Broken" to TestState.FAIL, "Can't test" to TestState.NA, "Not answered yet" to TestState.NOT_RUN)

internal fun parseStepLines(details: String): List<StepLine> = details.lines().filter { it.isNotBlank() && !it.startsWith(" ") }.mapNotNull { line ->
    ANSWER_STATES.entries.firstOrNull { line.startsWith(it.key + ": ") }?.let { StepLine(it.value, line.removePrefix(it.key + ": ")) }
}

@Composable
internal fun TestDetailContent(
    viewModel: MainViewModel,
    test: UnifiedTest,
    state: TestState,
    result: CheckResult?,
    runActive: Boolean,
    onDismiss: () -> Unit,
    onRedo: () -> Unit,
) {
    val catalog = viewModel.catalog.collectAsStateWithLifecycle().value
    val waiver = catalog.waiverFor(test)
    val handsOn = test.kind != TestKind.AUTO && !test.isRequirementOnly
    val initialTimestamp by rememberSaveable(test.id) { mutableStateOf(result?.timestamp) }
    var requested by rememberSaveable(test.id) { mutableStateOf(false) }
    var runTimestamp by rememberSaveable(test.id) { mutableStateOf(result?.timestamp) }
    var showingProgress by rememberSaveable(test.id) { mutableStateOf(false) }
    var showingRawOutput by rememberSaveable(test.id) { mutableStateOf(false) }
    var markOpen by rememberSaveable(test.id) { mutableStateOf(false) }
    val completed = result != null && result.timestamp != initialTimestamp
    LaunchedEffect(requested, state, result?.timestamp) {
        val active = state == TestState.RUNNING || state == TestState.QUEUED
        if (requested || active) showingProgress = true
        if (showingProgress && !active && result != null && result.timestamp != runTimestamp) {
            delay(600)
            showingProgress = false
            requested = false
        }
    }
    val detailsListState = rememberLazyListState()
    BackHandler(enabled = showingRawOutput) { showingRawOutput = false }
    if (showingRawOutput && result != null) {
        RawOutput(result.details) { showingRawOutput = false }
        return
    }
    val byTester = result?.byTester == true || (test.isRequirementOnly && state != TestState.NOT_RUN)
    val answers = remember(result?.details) { if (handsOn && !byTester) parseStepLines(result?.details.orEmpty()) else emptyList() }

    LazyColumn(
        state = detailsListState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "title", contentType = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val colors = state.colors()
                    Chip(stringResource(state.labelRes), colors.container, colors.content, colors.border)
                    Chip(stringResource(test.kind.labelRes) + " · " + stringResource(test.category.titleRes))
                    LevelPill(test.level, Modifier.align(Alignment.CenterVertically))
                }
                Text(test.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (result != null || state != TestState.NOT_RUN) {
                    Text(
                        lineFor(test, state, result, waiver).resolve(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                        color = if (state == TestState.FAIL) state.colors().border else MaterialTheme.colorScheme.onSurface,
                    )
                }
                result?.let {
                    val stamp = WHEN_FORMAT.format(Instant.ofEpochMilli(it.timestamp))
                    Hint(
                        when {
                            it.byTester -> stringResource(R.string.tests_detail_marked_at, stamp)
                            handsOn -> stringResource(R.string.tests_detail_answered_at, stamp)
                            it.access == Access.SHIZUKU -> stringResource(R.string.tests_detail_read_shizuku_at, stamp)
                            else -> stamp
                        },
                    )
                }
            }
        }

        item(key = "actions", contentType = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    handsOn -> TestActionButton(
                        label = stringResource(if (result == null) R.string.tests_detail_start else R.string.tests_detail_redo),
                        handsOn = true, onClick = onRedo,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { markOpen = !markOpen }) {
                        Text(stringResource(R.string.tests_detail_mark_as))
                        Icon(if (markOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
                    }
                    if (byTester) TextButton(onClick = { viewModel.markTest(test, Verdict.UNTESTED) }) { Text(stringResource(R.string.tests_detail_clear_verdict)) }
                    else if (handsOn && result != null) TextButton(onClick = { viewModel.clearAnswers(test); onDismiss() }) { Text(stringResource(R.string.tests_detail_clear_answers)) }
                }
                if (markOpen) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            listOf(Verdict.PASS to R.string.tests_state_pass, Verdict.FAIL to R.string.tests_state_fail, Verdict.NOT_APPLICABLE to R.string.tests_detail_not_applicable).forEach { (verdict, label) ->
                                TextButton(
                                    onClick = { markOpen = false; viewModel.markTest(test, verdict) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(0.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                ) { Text(stringResource(label), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                            }
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

        waiver?.let {
            item(key = "waiver", contentType = "section") {
                Section(stringResource(R.string.tests_detail_exception)) {
                    Text(it.reason, style = MaterialTheme.typography.bodyLarge)
                    it.reference?.let { reference -> Hint(reference) }
                    test.requirements.firstNotNullOfOrNull { requirement -> catalog.waivedBy(requirement.id) }?.let { pack -> Hint(stringResource(R.string.tests_detail_waived_by, pack)) }
                }
            }
        }

        if (answers.isNotEmpty()) {
            item(key = "answers", contentType = "section") {
                Section(stringResource(R.string.tests_detail_your_answers)) {
                    answers.forEachIndexed { index, line -> AnswerLine(index + 1, line) }
                }
            }
        } else if (result != null && result.details.isNotBlank() && !showingProgress && !result.byTester) {
            item(key = "found", contentType = "section") {
                val lines = result.details.lines()
                Section(stringResource(if (completed) R.string.tests_detail_found_now else R.string.tests_detail_found)) {
                    Text(
                        lines.take(PREVIEW_LINES).joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)).padding(10.dp),
                    )
                    if (lines.size > PREVIEW_LINES) TextButton(onClick = { showingRawOutput = true }) {
                        Text(pluralStringResource(R.plurals.tests_detail_raw_output, result.details.length, result.details.length))
                    }
                }
            }
        }

        if (state == TestState.NEEDS && !showingProgress && test.kind == TestKind.AUTO) {
            item(key = "next", contentType = "section") {
                val related = test.requirements.flatMap { it.testIds }.distinct().filter { it != test.id }.mapNotNull { catalog.interactiveById[it] }
                Section(stringResource(R.string.tests_detail_next_step)) {
                    if (related.isEmpty()) Text(stringResource(R.string.tests_detail_compare))
                    else related.forEach { next -> Text(if (next.id == "nfc.tag") stringResource(R.string.tests_detail_nfc_hint) else next.summary) }
                }
            }
        }

        item(key = "requirements", contentType = "section") {
            Section(stringResource(if (test.requirements.size > 1) R.string.tests_detail_requirements else R.string.tests_detail_requirement)) {
                test.requirements.forEach { requirement ->
                    Text("${requirement.level.label}: ${requirement.text}", style = MaterialTheme.typography.bodyLarge)
                    requirement.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                test.scopeLabel?.let { Hint(it) }
                test.source?.let { Hint(stringResource(R.string.tests_detail_from_pack, it)) }
                test.requirements.firstNotNullOfOrNull { catalog.replacedBy(it.id) }?.let { replaced ->
                    Hint(stringResource(R.string.tests_detail_replaces, replaced.level.label, replaced.text))
                }
            }
        }

        if (test.how.isNotEmpty()) {
            item(key = "how", contentType = "section") {
                Section(stringResource(if (test.kind == TestKind.OFF_DEVICE || test.isRequirementOnly) R.string.tests_detail_how_to_check else R.string.tests_detail_what_app_does)) {
                    test.how.forEachIndexed { index, step ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(22.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                                Text("${index + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RawOutput(details: String, onBack: () -> Unit) {
    val chunks = remember(details) { details.chunked(2000) }
    Column {
        TextButton(onClick = onBack) { Text(stringResource(R.string.tests_detail_back)) }
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp), state = rememberLazyListState(), contentPadding = PaddingValues(20.dp)) {
            itemsIndexed(chunks, key = { index, _ -> index }, contentType = { _, _ -> "raw" }) { _, chunk ->
                Text(chunk, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
/** [compact] keeps one line per answer, for the steps already behind the current one. */
internal fun AnswerLine(number: Int, line: StepLine, compact: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        StateDot(line.answer, Modifier.size(24.dp))
        Text(
            "$number. ${line.text}", style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f), maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(
                when (line.answer) {
                    TestState.PASS -> R.string.step_works
                    TestState.FAIL -> R.string.step_broken
                    TestState.NA -> R.string.step_cant_test
                    else -> R.string.tests_detail_not_answered
                },
            ),
            style = MaterialTheme.typography.labelLarge, color = line.answer.colors().border.takeIf { line.answer == TestState.PASS || line.answer == TestState.FAIL } ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
