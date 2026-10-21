package io.github.rthery.devicechecker.ui

import android.os.Build
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rthery.devicechecker.MainViewModel
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.data.Category
import io.github.rthery.devicechecker.data.RequirementPriority
import io.github.rthery.devicechecker.data.TestKind
import io.github.rthery.devicechecker.data.TestState
import io.github.rthery.devicechecker.data.UnifiedTest
import io.github.rthery.devicechecker.data.bundleState
import io.github.rthery.devicechecker.data.bundleTests
import io.github.rthery.devicechecker.data.lineFor
import io.github.rthery.devicechecker.data.needsYou
import io.github.rthery.devicechecker.data.stateOf
import io.github.rthery.devicechecker.data.testSummary
import io.github.rthery.devicechecker.shell.ShizukuState

enum class TestFilter(@StringRes val label: Int) {
    ALL(R.string.tests_filter_all), NEEDS(R.string.tests_filter_needs), FAILED(R.string.tests_filter_failed), AUTO(R.string.tests_filter_auto),
}

private fun TestFilter.accepts(test: UnifiedTest, state: TestState) = when (this) {
    TestFilter.ALL -> true
    TestFilter.NEEDS -> state.needsYou(test.kind)
    TestFilter.FAILED -> state == TestState.FAIL
    TestFilter.AUTO -> test.kind == TestKind.AUTO
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestsTab(viewModel: MainViewModel, listState: LazyListState, onOpenTest: (String) -> Unit, onOpenDevice: () -> Unit, modifier: Modifier = Modifier) {
    val results by viewModel.results.collectAsStateWithLifecycle()
    val verdicts by viewModel.verdicts.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val lastRun by viewModel.lastRun.collectAsStateWithLifecycle()
    val deviceInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()
    val shizuku by viewModel.shizukuState.collectAsStateWithLifecycle()
    val catalog = viewModel.catalog.collectAsStateWithLifecycle().value
    var filter by rememberSaveable { mutableStateOf(TestFilter.ALL) }
    val closed by viewModel.collapsedCategories.collectAsStateWithLifecycle()
    val priority by viewModel.requirementPriority.collectAsStateWithLifecycle()
    var selectedBundleId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }

    val tests = catalog.all
    val waivers = remember(catalog) { tests.associate { it.id to catalog.waiverFor(it) } }
    val priorityTests = remember(catalog, priority, deviceInfo.rom) { tests.filter { priority.accepts(it) && it.appliesTo(deviceInfo.rom) } }
    val bundles = remember(priorityTests) { bundleTests(priorityTests) }
    val grouped = remember(bundles) { bundles.groupBy { it.category } }
    val categoryTests = remember(grouped) { grouped.mapValues { (_, entries) -> entries.flatMap { it.tests } } }
    val baseStates = remember(catalog, results, verdicts, deviceInfo.rom) {
        tests.associate { it.id to stateOf(it, results, verdicts, deviceInfo.rom, null, waivers[it.id]) }
    }
    val counted = remember(priorityTests) { priorityTests.filter { it.kind != TestKind.OFF_DEVICE } }
    val automaticIds = remember(priorityTests) { priorityTests.filter { it.kind == TestKind.AUTO }.map { it.id }.toSet() }
    val autoCount = automaticIds.size
    val states = remember(baseStates, progress) {
        val run = progress
        if (run == null) baseStates else baseStates + tests.mapNotNull { test ->
            when {
                test.kind != TestKind.AUTO -> null
                test.id == run.currentId -> test.id to TestState.RUNNING
                test.id in run.pendingIds -> test.id to TestState.QUEUED
                else -> null
            }
        }
    }
    val idleStates = baseStates
    val counts = remember(counted, idleStates) {
        TestFilter.entries.associateWith { f -> counted.count { f.accepts(it, idleStates.getValue(it.id)) } }
    }
    val visibleBundles = remember(bundles, filter, idleStates) {
        grouped.mapValues { (_, entries) -> entries.filter { bundle -> bundle.tests.any { filter.accepts(it, idleStates.getValue(it.id)) } } }
    }
    val categorySummaries = remember(categoryTests, states) {
        categoryTests.mapValues { (_, members) -> testSummary(members, states) }
    }
    val bundleSummaries = remember(bundles, states) {
        bundles.associate { it.id to testSummary(it.tests, states) }
    }
    val bundleStates = remember(bundles, states) {
        bundles.associate { it.id to bundleState(it.tests, states) }
    }
    val runActive = progress != null
    val fullRun = progress?.let { it.total > 1 } == true

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.tests_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        deviceInfo.rom?.let { stringResource(R.string.tests_device_line_rom, Build.MODEL, Build.VERSION.RELEASE, it.label) }
                            ?: stringResource(R.string.tests_device_line, Build.MODEL, Build.VERSION.RELEASE),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    onClick = onOpenDevice,
                    shape = RoundedCornerShape(50),
                    color = if (shizuku == ShizukuState.READY) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        stringResource(if (shizuku == ShizukuState.READY) R.string.tests_shizuku_on else R.string.tests_shizuku_off),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        item(key = "priority", contentType = "priority") {
            PrioritySelector(priority, viewModel::setRequirementPriority)
        }

        item {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TestFilter.entries.forEach { f ->
                    val on = filter == f
                    Surface(
                        onClick = { filter = f },
                        modifier = Modifier.weight(1f).heightIn(min = 60.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        border = if (on) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${counts[f]}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(stringResource(f.label), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(top = 10.dp, bottom = 6.dp)) {
                val current = progress
                when {
                    current != null && fullRun -> RunningCard(current.done + 1, current.total, current.current, onStop = viewModel::stopRun)
                    else -> {
                        Button(
                            onClick = { viewModel.runTests(automaticIds) },
                            enabled = !runActive && autoCount > 0,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text(pluralStringResource(R.plurals.tests_run_automatic, autoCount, autoCount))
                        }
                        lastRun?.let { summary ->
                            Spacer(Modifier.size(8.dp))
                            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        pluralStringResource(if (summary.stopped) R.plurals.tests_run_stopped else R.plurals.tests_run_finished, summary.failed, summary.failed),
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (summary.failed > 0) {
                                        FilledTonalButton(onClick = {
                                            filter = TestFilter.FAILED
                                            viewModel.dismissLastRun()
                                        }) { Text(stringResource(R.string.tests_show_failed)) }
                                    } else {
                                        OutlinedButton(onClick = viewModel::dismissLastRun) { Text(stringResource(R.string.tests_ok)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (visibleBundles.values.all { it.isEmpty() }) {
            item(key = "empty") {
                Text(stringResource(R.string.tests_empty), modifier = Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Category.entries.forEach { category ->
            val visible = visibleBundles[category].orEmpty()
            if (visible.isEmpty()) return@forEach
            val open = category.name !in closed
            item(key = "cat-${category.name}", contentType = "category") {
                CategoryHeader(category, summaryText(categorySummaries.getValue(category)), open) { viewModel.toggleCategory(category) }
            }
            if (open) {
                itemsIndexed(visible, key = { _, bundle -> bundle.id }, contentType = { _, bundle -> if (bundle.tests.size > 1) "bundle" else "test" }) { index, bundle ->
                    if (bundle.tests.size > 1) {
                        BundleRow(bundle.title.resolve(), bundle.tests.size, summaryText(bundleSummaries.getValue(bundle.id)), bundleStates.getValue(bundle.id)) { selectedBundleId = bundle.id }
                    } else {
                        val test = bundle.tests.single()
                        val state = states.getValue(test.id)
                        TestRow(
                            test = test, state = state,
                            line = lineFor(test, state, results[test.id], waivers[test.id]),
                            first = index == 0, last = index == visible.lastIndex,
                            runActive = runActive, onOpen = { selectedId = test.id },
                            onAction = {
                                when {
                                    test.kind == TestKind.AUTO -> {
                                        selectedId = test.id
                                        viewModel.runOne(test.id)
                                    }
                                    test.isRequirementOnly -> selectedId = test.id
                                    else -> onOpenTest(test.id)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    val selectedBundle = bundles.firstOrNull { it.id == selectedBundleId }
    val selectedTest = selectedId?.let { catalog.byId[it] }
    if (selectedBundle != null || selectedTest != null) {
        val panelStates = rememberSaveableStateHolder()
        val back = { selectedId = null }
        val close = { selectedId = null; selectedBundleId = null }
        val openTest: (String) -> Unit = { id -> close(); onOpenTest(id) }
        // Keep the same modal window and sheet state while navigating within a group.
        ModalBottomSheet(
            onDismissRequest = close,
            properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            BackHandler { if (selectedTest != null && selectedBundle != null) back() else close() }
            if (selectedTest != null) {
                panelStates.SaveableStateProvider("detail-${selectedTest.id}") {
                    Column {
                        if (selectedBundle != null) TextButton(onClick = back) { Text(stringResource(R.string.tests_back_to, selectedBundle.title.resolve())) }
                        TestDetailContent(
                            viewModel = viewModel, test = selectedTest,
                            state = states.getValue(selectedTest.id), result = results[selectedTest.id],
                            runActive = runActive, onDismiss = back, onOpenTest = openTest,
                        )
                    }
                }
            } else if (selectedBundle != null) {
                panelStates.SaveableStateProvider("bundle-${selectedBundle.id}") {
                    TestBundleContent(
                        bundle = selectedBundle, states = states, results = results, waivers = waivers,
                        runActive = runActive, onDetails = { selectedId = it },
                        onRun = { id -> selectedId = id; viewModel.runOne(id) },
                        onOpenTest = openTest,
                        onRunChecks = { viewModel.runTests(selectedBundle.tests.filter { it.kind == TestKind.AUTO }.map { it.id }.toSet()) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PrioritySelector(priority: RequirementPriority, onSelect: (RequirementPriority) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedButton(
            onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        ) {
            Text(if (priority == RequirementPriority.ALL) stringResource(R.string.tests_all_priorities) else "${priority.label} · ${priority.levels}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.tests_priority_filter_description))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RequirementPriority.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label)
                            if (option.levels.isNotEmpty()) Text(option.levels, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    trailingIcon = { if (option == priority) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.tests_selected)) },
                    onClick = { expanded = false; onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun RunningCard(position: Int, total: Int, current: String, onStop: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tests_running_position, position, total), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = onStop,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimaryContainer),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                ) { Text(stringResource(R.string.tests_stop)) }
            }
            LinearProgressIndicator(progress = { position.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            Text(current, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CategoryHeader(category: Category, summary: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = stringResource(if (open) R.string.tests_collapse else R.string.tests_expand), onClick = onToggle)
            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(stringResource(category.titleRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
    }
}

@StringRes
private fun UnifiedTest.actionLabel(state: TestState): Int = when {
    kind == TestKind.AUTO -> R.string.tests_action_run
    isRequirementOnly -> R.string.tests_action_mark
    kind == TestKind.MANUAL -> R.string.tests_action_steps
    state == TestState.NOT_RUN -> R.string.tests_action_start
    state == TestState.NEEDS -> R.string.tests_action_continue
    else -> R.string.tests_action_open
}

@Composable
internal fun TestRow(
    test: UnifiedTest,
    state: TestState,
    line: UiText,
    first: Boolean,
    last: Boolean,
    runActive: Boolean,
    onOpen: () -> Unit,
    onAction: () -> Unit,
) {
    val big = 20.dp
    val small = 4.dp
    val shape = RoundedCornerShape(
        topStart = if (first) big else small, topEnd = if (first) big else small,
        bottomStart = if (last) big else small, bottomEnd = if (last) big else small,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StateDot(state)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                test.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            test.source?.let {
                Text(stringResource(R.string.tests_from_source, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LevelPill(test.level)
                Text(
                    line.resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state == TestState.FAIL) state.colors().border else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val label = test.actionLabel(state)
        val enabled = !(test.kind == TestKind.AUTO && runActive)
        TestActionButton(label = stringResource(if (test.kind == TestKind.AUTO || test.isRequirementOnly) label else handsOnLabel(test.id, test.kind == TestKind.MANUAL)),
            handsOn = test.kind != TestKind.AUTO, onClick = onAction, enabled = enabled)
    }
}
