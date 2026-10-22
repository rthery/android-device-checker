package io.github.rthery.devicechecker.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rthery.devicechecker.MainViewModel
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.data.Category
import io.github.rthery.devicechecker.data.RequirementPriority
import io.github.rthery.devicechecker.data.Store
import io.github.rthery.devicechecker.data.TestKind
import io.github.rthery.devicechecker.data.TestOverview
import io.github.rthery.devicechecker.data.TestState
import io.github.rthery.devicechecker.data.UnifiedTest
import io.github.rthery.devicechecker.data.guidedQueue
import io.github.rthery.devicechecker.data.isDone
import io.github.rthery.devicechecker.data.isTask
import io.github.rthery.devicechecker.data.isToCheck
import io.github.rthery.devicechecker.data.lineFor
import io.github.rthery.devicechecker.data.overviewOf
import io.github.rthery.devicechecker.data.shortRomVersion
import io.github.rthery.devicechecker.data.stateOf
import io.github.rthery.devicechecker.data.stockComparisonIds
import io.github.rthery.devicechecker.shell.ShizukuState

private enum class Lane { YOU, AUTO }

private enum class ResultFilter(@StringRes val label: Int) {
    FAILED(R.string.tests_tile_failed), TO_CHECK(R.string.tests_tile_to_check), PASSED(R.string.tests_tile_passed),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestsTab(
    viewModel: MainViewModel,
    listState: LazyListState,
    onStartRun: (queue: List<String>, startId: String) -> Unit,
    onOpenDevice: () -> Unit,
    modifier: Modifier = Modifier,
    sheetRequest: String? = null,
    onSheetShown: () -> Unit = {},
) {
    val context = LocalContext.current
    val results by viewModel.results.collectAsStateWithLifecycle()
    val verdicts by viewModel.verdicts.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val deviceInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()
    val shizuku by viewModel.shizukuState.collectAsStateWithLifecycle()
    val shizukuRoot by viewModel.shizukuRoot.collectAsStateWithLifecycle()
    val adbRoot by viewModel.adbRoot.collectAsStateWithLifecycle()
    val stock by viewModel.stock.collectAsStateWithLifecycle()
    val priority by viewModel.requirementPriority.collectAsStateWithLifecycle()
    val catalog = viewModel.catalog.collectAsStateWithLifecycle().value
    var lane by rememberSaveable { mutableStateOf(Lane.YOU) }
    var filter by rememberSaveable { mutableStateOf<ResultFilter?>(null) }
    var youDoneOpen by rememberSaveable { mutableStateOf(false) }
    var autoDoneOpen by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val store = remember { Store(context) }
    LaunchedEffect(sheetRequest) {
        sheetRequest?.let { selectedId = it; onSheetShown() }
    }

    val tests = remember(catalog, priority, deviceInfo.rom) { catalog.all.filter { priority.accepts(it) && it.appliesTo(deviceInfo.rom) } }
    val waivers = remember(catalog) { catalog.all.associate { it.id to catalog.waiverFor(it) } }
    val states = remember(tests, results, verdicts, deviceInfo.rom) {
        tests.associate { it.id to stateOf(it, results, verdicts, deviceInfo.rom, null, waivers[it.id]) }
    }
    val live = remember(states, progress) {
        val run = progress ?: return@remember states
        states + tests.filter { it.kind == TestKind.AUTO }.mapNotNull { test ->
            when (test.id) {
                run.currentId -> test.id to TestState.RUNNING
                in run.pendingIds -> test.id to TestState.QUEUED
                else -> null
            }
        }
    }
    val overview = remember(tests, states) { overviewOf(tests, states) }
    val queue = remember(tests, states) { guidedQueue(tests, states) }
    val answered = remember(tests, results) {
        tests.filter { catalog.interactiveById[it.id]?.steps != null }.associate { it.id to store.loadGuidedDraft(it.id).answers.size }
    }
    val automaticIds = remember(tests) { tests.filter { it.kind == TestKind.AUTO }.map { it.id }.toSet() }
    val waitingForStock = remember(tests, stock) { if (stock == null) tests.count { it.id in stockComparisonIds } else 0 }
    val runActive = progress != null

    fun line(test: UnifiedTest) = lineFor(test, live.getValue(test.id), results[test.id], waivers[test.id])
    fun open(test: UnifiedTest) {
        if (states.getValue(test.id).isTask(test.kind)) onStartRun(queue.map { it.id }.ifEmpty { listOf(test.id) }, test.id) else selectedId = test.id
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "header") {
            Header(
                deviceLine = deviceLine(deviceInfo.rom?.label, deviceInfo.version),
                adbRoot = adbRoot == true,
                shizuku = shizuku, shizukuRoot = shizukuRoot,
                onOpenDevice = onOpenDevice,
            )
        }
        item(key = "summary") {
            SummaryCard(
                overview = overview,
                priority = priority, onPriority = viewModel::setRequirementPriority,
                filter = filter,
                onFilter = { filter = if (filter == it) null else it },
                onNeedYou = { filter = null; lane = Lane.YOU },
                waitingForStock = waitingForStock,
                onImportStock = viewModel::importStock,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
        }

        val active = filter
        if (active != null) {
            item(key = "filter-chip") {
                Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.tests_showing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    InputChip(
                        selected = true, onClick = { filter = null },
                        label = { Text(stringResource(active.label)) },
                        trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.tests_clear_filter), Modifier.size(InputChipDefaults.IconSize)) },
                    )
                }
            }
            val matching = tests.filter { test ->
                val state = states.getValue(test.id)
                when (active) {
                    ResultFilter.FAILED -> state == TestState.FAIL
                    ResultFilter.TO_CHECK -> state.isToCheck(test.kind)
                    ResultFilter.PASSED -> state == TestState.PASS
                }
            }
            if (matching.isEmpty()) item(key = "filter-empty") { EmptyText(R.string.tests_empty) }
            byCategory(matching) { category, members ->
                item(key = "filter-${category.name}") { SectionTitle(stringResource(category.titleRes)) }
                items(members, key = { "filter-${it.id}" }) { test ->
                    ResultRow(test, live.getValue(test.id), line(test), withKind = true, onOpen = { open(test) })
                }
            }
            return@LazyColumn
        }

        item(key = "lanes") {
            val autoLabel = progress?.let { stringResource(R.string.tests_lane_auto_running, it.done + 1, it.total) }
                ?: stringResource(R.string.tests_lane_auto, automaticIds.size)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                SegmentedButton(selected = lane == Lane.YOU, onClick = { lane = Lane.YOU }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text(stringResource(R.string.tests_lane_you, overview.needYou), maxLines = 1)
                }
                SegmentedButton(selected = lane == Lane.AUTO, onClick = { lane = Lane.AUTO }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text(autoLabel, maxLines = 1)
                }
            }
        }

        when (lane) {
            Lane.YOU -> {
                if (queue.isNotEmpty()) item(key = "start-run") {
                    val started = queue.any { (answered[it.id] ?: 0) > 0 || states.getValue(it.id) != TestState.NOT_RUN }
                    Button(onClick = { onStartRun(queue.map { it.id }, queue.first().id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (started) R.string.tests_run_continue else R.string.tests_run_start))
                    }
                } else item(key = "you-empty") { EmptyText(R.string.tests_you_empty) }
                byCategory(queue) { category, members ->
                    item(key = "you-${category.name}") {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle(stringResource(category.titleRes), Modifier.weight(1f))
                            TextButton(onClick = { onStartRun(queue.map { it.id }, members.first().id) }) { Text(stringResource(R.string.tests_start_group)) }
                        }
                    }
                    items(members, key = { "you-${it.id}" }) { test ->
                        val steps = catalog.interactiveById[test.id]?.steps
                        TaskRow(test, states.getValue(test.id), steps, answered[test.id] ?: 0, onOpen = { open(test) })
                    }
                }
                val done = tests.filter { it.kind != TestKind.AUTO && !states.getValue(it.id).isTask(it.kind) }
                doneSection(
                    key = "you-done", title = R.string.tests_done_by_you, states = states,
                    open = youDoneOpen, onToggle = { youDoneOpen = !youDoneOpen }, members = done,
                ) { test -> ResultRow(test, live.getValue(test.id), line(test), compact = true, onOpen = { selectedId = test.id }) }
            }
            Lane.AUTO -> {
                item(key = "run-all") {
                    val run = progress
                    if (run != null) RunningPill(run.done + 1, run.total, onStop = viewModel::stopRun)
                    else Button(onClick = { viewModel.runTests(automaticIds) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tests_run_all_again))
                    }
                }
                val automatic = tests.filter { it.kind == TestKind.AUTO }.sortedBy { it.level?.ordinal ?: Int.MAX_VALUE }
                listOf(
                    R.string.tests_section_failed to automatic.filter { states.getValue(it.id) == TestState.FAIL },
                    R.string.tests_section_to_check to automatic.filter { states.getValue(it.id).isToCheck(it.kind) },
                    R.string.tests_section_not_run to automatic.filter { states.getValue(it.id) == TestState.NOT_RUN },
                ).forEach { (title, members) ->
                    if (members.isEmpty()) return@forEach
                    item(key = "auto-title-$title") { SectionTitle(stringResource(title, members.size), Modifier.padding(top = 8.dp)) }
                    items(members, key = { "auto-${it.id}" }) { test ->
                        ResultRow(
                            test, live.getValue(test.id), line(test), onOpen = { selectedId = test.id },
                            onRerun = { viewModel.runOne(test.id) }, rerunEnabled = !runActive,
                        )
                    }
                }
                val done = automatic.filter { states.getValue(it.id).isDone() }
                doneSection(
                    key = "auto-done", title = R.string.tests_done, states = states,
                    open = autoDoneOpen, onToggle = { autoDoneOpen = !autoDoneOpen }, members = done, groupByCategory = true,
                ) { test -> ResultRow(test, live.getValue(test.id), line(test), compact = true, onOpen = { selectedId = test.id }) }
            }
        }
    }

    val selected = selectedId?.let { catalog.byId[it] }
    if (selected != null) {
        ModalBottomSheet(onDismissRequest = { selectedId = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            TestDetailContent(
                viewModel = viewModel, test = selected,
                state = live[selected.id] ?: TestState.NOT_RUN, result = results[selected.id],
                runActive = runActive, onDismiss = { selectedId = null },
                onRedo = { selectedId = null; onStartRun(listOf(selected.id), selected.id) },
            )
        }
    }
}

@Composable
private fun deviceLine(rom: String?, version: String?): String = when {
    rom != null && version != null -> stringResource(R.string.tests_device_line_version, Build.MODEL, rom, shortRomVersion(version), Build.TYPE)
    rom != null -> stringResource(R.string.tests_device_line_rom, Build.MODEL, rom, Build.TYPE)
    else -> stringResource(R.string.tests_device_line, Build.MODEL, Build.VERSION.RELEASE, Build.TYPE)
}

@Composable
private fun Header(deviceLine: String, adbRoot: Boolean, shizuku: ShizukuState, shizukuRoot: Boolean, onOpenDevice: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.tests_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (adbRoot) StateChip(stringResource(R.string.tests_adb_root), warning = true, onClick = onOpenDevice)
            val ready = shizuku == ShizukuState.READY
            StateChip(
                stringResource(
                    when {
                        !ready -> R.string.tests_shizuku_off
                        shizukuRoot -> R.string.tests_shizuku_root
                        else -> R.string.tests_shizuku_on
                    },
                ),
                warning = ready && shizukuRoot, active = ready, onClick = onOpenDevice,
            )
        }
        Text(deviceLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SummaryCard(
    overview: TestOverview,
    priority: RequirementPriority,
    onPriority: (RequirementPriority) -> Unit,
    filter: ResultFilter?,
    onFilter: (ResultFilter) -> Unit,
    onNeedYou: () -> Unit,
    waitingForStock: Int,
    onImportStock: (android.net.Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.tests_done_of, overview.total, overview.done, overview.total),
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
                    )
                    PriorityMenu(priority, onPriority)
                }
                LinearProgressIndicator(
                    progress = { if (overview.total == 0) 0f else overview.done.toFloat() / overview.total },
                    modifier = Modifier.fillMaxWidth(),
                    drawStopIndicator = {},
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val tile = Modifier.weight(1f)
                CountTile(overview.failed, R.string.tests_tile_failed, Icons.Filled.Close, TestState.FAIL, filter == ResultFilter.FAILED, tile) { onFilter(ResultFilter.FAILED) }
                CountTile(overview.toCheck, R.string.tests_tile_to_check, Icons.Filled.Warning, TestState.WARN, filter == ResultFilter.TO_CHECK, tile) { onFilter(ResultFilter.TO_CHECK) }
                CountTile(overview.needYou, R.string.tests_tile_need_you, Icons.Filled.Person, TestState.NEEDS, false, tile, onNeedYou)
                CountTile(overview.passed, R.string.tests_tile_passed, Icons.Filled.Check, TestState.PASS, filter == ResultFilter.PASSED, tile) { onFilter(ResultFilter.PASSED) }
            }
            if (waitingForStock > 0) StockBanner(waitingForStock, onImportStock)
        }
    }
}

@Composable
private fun PriorityMenu(priority: RequirementPriority, onSelect: (RequirementPriority) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            colors = if (priority == RequirementPriority.ALL) ButtonDefaults.textButtonColors()
            else ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            contentPadding = PaddingValues(start = 10.dp, end = 4.dp),
        ) {
            Text(if (priority == RequirementPriority.ALL) stringResource(R.string.tests_all_priorities) else priority.label)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.tests_priority_filter_description))
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

/** The icon and the label carry the meaning; the color only repeats it. */
@Composable
private fun CountTile(count: Int, @StringRes label: Int, icon: ImageVector, state: TestState, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val text = stringResource(label)
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = "$count $text" },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) scheme.secondaryContainer else scheme.surface,
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, scheme.primary) else null,
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(icon, null, Modifier.size(14.dp), tint = state.colors().border)
                Text("$count", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = state.colors().border)
            }
            Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StockBanner(count: Int, onImport: (android.net.Uri) -> Unit) {
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onImport) }
    val warn = TestState.WARN.colors()
    Surface(shape = RoundedCornerShape(14.dp), color = warnContainer(), contentColor = onWarnContainer()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.tests_no_baseline_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(pluralStringResource(R.plurals.tests_no_baseline_body, count, count), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                colors = ButtonDefaults.buttonColors(containerColor = warn.container, contentColor = warn.content),
            ) { Text(stringResource(R.string.screen_stock_import)) }
        }
    }
}

@Composable
private fun RunningPill(position: Int, total: Int, onStop: () -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, strokeWidth = 2.5.dp)
                Text(stringResource(R.string.tests_running_position, position, total), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1)
                TextButton(onClick = onStop, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) { Text(stringResource(R.string.tests_stop)) }
            }
            LinearProgressIndicator(
                progress = { position.toFloat() / total },
                modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomStart),
                trackColor = Color.Transparent, drawStopIndicator = {}, gapSize = 0.dp,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 4.dp).semantics { heading() },
    )
}

@Composable
private fun EmptyText(@StringRes text: Int) {
    Text(stringResource(text), modifier = Modifier.padding(vertical = 16.dp, horizontal = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun LazyListScope.byCategory(tests: List<UnifiedTest>, block: LazyListScope.(Category, List<UnifiedTest>) -> Unit) {
    Category.entries.forEach { category ->
        val members = tests.filter { it.category == category }
        if (members.isNotEmpty()) block(category, members)
    }
}

private fun LazyListScope.doneSection(
    key: String,
    @StringRes title: Int,
    states: Map<String, TestState>,
    open: Boolean,
    onToggle: () -> Unit,
    members: List<UnifiedTest>,
    groupByCategory: Boolean = false,
    row: @Composable (UnifiedTest) -> Unit,
) {
    if (members.isEmpty()) return
    item(key = key) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp)
                .border(1.dp, MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onToggle)
                .heightIn(min = 56.dp).padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(title, members.size), style = MaterialTheme.typography.titleSmall)
                Text(doneSummary(members, states), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, stringResource(if (open) R.string.tests_collapse else R.string.tests_expand))
        }
    }
    if (!open) return
    if (groupByCategory) byCategory(members) { category, inCategory ->
        item(key = "$key-${category.name}") { SectionTitle(stringResource(category.titleRes), Modifier.padding(top = 4.dp)) }
        items(inCategory, key = { "$key-${it.id}" }) { row(it) }
    } else items(members, key = { "$key-${it.id}" }) { row(it) }
}

@Composable
private fun doneSummary(tests: List<UnifiedTest>, states: Map<String, TestState>): String {
    val parts = listOf(
        TestState.PASS to R.plurals.tests_summary_passed, TestState.FAIL to R.plurals.tests_summary_failed,
        TestState.WAIVED to R.plurals.tests_summary_waived, TestState.NA to R.plurals.tests_summary_na,
    )
    return parts.mapNotNull { (state, res) ->
        tests.count { states[it.id] == state }.takeIf { it > 0 }?.let { pluralStringResource(res, it, it) }
    }.joinToString(" · ")
}

private fun kindIcon(kind: TestKind): ImageVector = when (kind) {
    TestKind.AUTO -> Glyphs.Bolt
    TestKind.GUIDED -> Glyphs.TouchApp
    TestKind.MANUAL -> Glyphs.Checklist
    TestKind.OFF_DEVICE -> Glyphs.Code
}

/** A hands-on test: its kind, how far it got, and a badge whose shape tells the state. */
@Composable
private fun TaskRow(test: UnifiedTest, state: TestState, steps: Int?, answered: Int, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val partial = steps != null && answered in 1 until steps
    val counter = when {
        partial -> pluralStringResource(R.plurals.tests_steps_answered, steps, answered, steps)
        steps != null -> pluralStringResource(R.plurals.tests_steps, steps, steps)
        test.isRequirementOnly -> stringResource(R.string.tests_your_verdict)
        else -> stringResource(test.kind.labelRes)
    }
    val badge: Pair<ImageVector, Color>? = when {
        partial -> Glyphs.HalfCircle to scheme.primary
        state == TestState.WARN -> Icons.Filled.Warning to TestState.WARN.colors().border
        state == TestState.NEEDS -> Glyphs.Pending to TestState.NEEDS.colors().border
        else -> null
    }
    val badgeLabel = when {
        partial -> stringResource(R.string.tests_badge_started)
        state == TestState.WARN -> stringResource(R.string.tests_badge_redo)
        state == TestState.NEEDS -> stringResource(R.string.tests_badge_waiting)
        else -> null
    }
    val kindLabel = stringResource(test.kind.labelRes)
    Row(
        Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(test.title, kindLabel, badgeLabel, counter, test.level?.label).joinToString(", ") }
            .heightIn(min = 56.dp).padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            Box(Modifier.size(28.dp).background(scheme.secondaryContainer, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Icon(kindIcon(test.kind), null, Modifier.size(18.dp), tint = scheme.onSecondaryContainer)
            }
            badge?.let { (icon, tint) ->
                Box(Modifier.align(Alignment.BottomEnd).offset(6.dp, 6.dp).size(18.dp).background(scheme.surface, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(14.dp), tint = tint)
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(test.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                counter + (test.source?.let { " · " + stringResource(R.string.tests_from_source, it) } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        test.level?.let { Text(it.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = scheme.onSurfaceVariant) }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}

/** A result: state, title, one line of finding, and what it covers. */
@Composable
private fun ResultRow(
    test: UnifiedTest,
    state: TestState,
    line: UiText,
    onOpen: () -> Unit,
    compact: Boolean = false,
    withKind: Boolean = false,
    onRerun: (() -> Unit)? = null,
    rerunEnabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(12.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clickable(onClick = onOpen).heightIn(min = if (compact) 48.dp else 60.dp).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StateDot(state)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(test.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    line.resolve(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (state == TestState.FAIL) state.colors().border else scheme.onSurfaceVariant,
                )
                if (!compact) {
                    val meta = listOfNotNull(
                        test.level?.label,
                        if (withKind) stringResource(test.kind.labelRes) else stringResource(test.category.titleRes),
                        test.source?.let { stringResource(R.string.tests_from_source, it) },
                    ).joinToString(" · ")
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        onRerun?.let {
            IconButton(onClick = it, enabled = rerunEnabled) {
                Icon(Icons.Filled.Refresh, stringResource(R.string.tests_run_again_named, test.title), tint = if (rerunEnabled) scheme.primary else scheme.onSurface.copy(alpha = 0.38f))
            }
        }
    }
}

/** Header status pill; [warning] marks a state that makes results unlike a user's phone. */
@Composable
internal fun StateChip(label: String, warning: Boolean, onClick: () -> Unit, active: Boolean = true) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = when {
            warning -> MaterialTheme.colorScheme.errorContainer
            active -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}
