package io.github.rthery.devicechecker.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rthery.devicechecker.MainViewModel
import io.github.rthery.devicechecker.data.Category
import io.github.rthery.devicechecker.data.TestKind
import io.github.rthery.devicechecker.data.TestState
import io.github.rthery.devicechecker.data.UnifiedTest
import io.github.rthery.devicechecker.data.lineFor
import io.github.rthery.devicechecker.data.needsYou
import io.github.rthery.devicechecker.data.stateOf
import io.github.rthery.devicechecker.shell.ShizukuState

enum class TestFilter(val label: String) { ALL("All"), NEEDS("Need you"), FAILED("Failed"), AUTO("Automatic") }

private fun TestFilter.accepts(test: UnifiedTest, state: TestState) = when (this) {
    TestFilter.ALL -> true
    TestFilter.NEEDS -> state.needsYou(test.kind)
    TestFilter.FAILED -> state == TestState.FAIL
    TestFilter.AUTO -> test.kind == TestKind.AUTO
}

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
    var closed by remember { mutableStateOf(setOf(Category.OFF_DEVICE)) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }

    val tests = catalog.all
    val states = tests.associate { it.id to stateOf(it, results, verdicts, deviceInfo.lineageBased, progress, catalog.waiverFor(it)) }
    val counted = tests.filter { it.kind != TestKind.OFF_DEVICE }
    val autoCount = tests.count { it.kind == TestKind.AUTO }
    val idleStates = counted.associate { it.id to stateOf(it, results, verdicts, deviceInfo.lineageBased, null, catalog.waiverFor(it)) }
    val counts = TestFilter.entries.associateWith { f -> counted.count { f.accepts(it, idleStates.getValue(it.id)) } }
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
                    Text("Tests", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${Build.MODEL} · Android ${Build.VERSION.RELEASE}${if (deviceInfo.lineageBased) " · LineageOS-based" else ""}",
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
                        if (shizuku == ShizukuState.READY) "Shizuku on" else "Shizuku off",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
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
                            Text(f.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
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
                            onClick = viewModel::runAllAutomatic,
                            enabled = !runActive,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Run $autoCount automatic tests")
                        }
                        lastRun?.let { summary ->
                            Spacer(Modifier.size(8.dp))
                            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${if (summary.stopped) "Run stopped" else "Run finished"} · ${summary.failed} automatic tests failed",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (summary.failed > 0) {
                                        FilledTonalButton(onClick = {
                                            filter = TestFilter.FAILED
                                            viewModel.dismissLastRun()
                                        }) { Text("Show failed") }
                                    } else {
                                        OutlinedButton(onClick = viewModel::dismissLastRun) { Text("OK") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Category.entries.forEach { category ->
            val all = tests.filter { it.category == category }
            val visible = all.filter { filter.accepts(it, states.getValue(it.id)) }
            if (visible.isEmpty()) return@forEach
            val open = category !in closed
            item(key = "cat-${category.name}") {
                CategoryHeader(category, all, states, open) {
                    closed = if (open) closed + category else closed - category
                }
            }
            if (open) {
                itemsIndexed(visible, key = { _, test -> test.id }) { index, test ->
                    val state = states.getValue(test.id)
                    TestRow(
                        test = test,
                        state = state,
                        line = lineFor(test, state, results[test.id], catalog.waiverFor(test)),
                        first = index == 0,
                        last = index == visible.lastIndex,
                        runActive = runActive,
                        onOpen = { selectedId = test.id },
                        onAction = {
                            when {
                                test.kind == TestKind.AUTO -> viewModel.runOne(test.id)
                                test.isRequirementOnly -> selectedId = test.id
                                else -> onOpenTest(test.id)
                            }
                        },
                    )
                }
            }
        }
    }

    selectedId?.let { catalog.byId[it] }?.let { test ->
        TestDetailSheet(
            viewModel = viewModel,
            test = test,
            state = states.getValue(test.id),
            result = results[test.id],
            runActive = runActive,
            onDismiss = { selectedId = null },
            onOpenTest = {
                selectedId = null
                onOpenTest(test.id)
            },
        )
    }
}

@Composable
private fun RunningCard(position: Int, total: Int, current: String, onStop: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Running $position of $total", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = onStop,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimaryContainer),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                ) { Text("Stop") }
            }
            LinearProgressIndicator(progress = { position.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            Text(current, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CategoryHeader(category: Category, tests: List<UnifiedTest>, states: Map<String, TestState>, open: Boolean, onToggle: () -> Unit) {
    val failed = tests.count { states[it.id] == TestState.FAIL }
    val need = tests.count { states.getValue(it.id).needsYou(it.kind) }
    val passed = tests.count { states[it.id] == TestState.PASS }
    val summary = if (category == Category.OFF_DEVICE) "${tests.size} items about the source tree and the maintainer"
    else listOfNotNull(
        "$failed failed".takeIf { failed > 0 },
        "$need need you".takeIf { need > 0 },
        "$passed passed".takeIf { passed > 0 },
    ).joinToString(" · ").ifEmpty { "${tests.size} tests" }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Expand", onClick = onToggle)
            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(category.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
    }
}

private fun UnifiedTest.actionLabel(state: TestState): String = when {
    kind == TestKind.AUTO -> "Run"
    isRequirementOnly -> "Mark"
    kind == TestKind.MANUAL -> "Steps"
    state == TestState.NOT_RUN -> "Start"
    state == TestState.NEEDS -> "Continue"
    else -> "Open"
}

@Composable
private fun TestRow(
    test: UnifiedTest,
    state: TestState,
    line: String,
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    test.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                LevelPill(test.level)
            }
            test.source?.let {
                Text("From $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state == TestState.FAIL) state.colors().border else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val label = test.actionLabel(state)
        val emphasized = test.kind == TestKind.AUTO || state == TestState.NOT_RUN || state == TestState.NEEDS
        val enabled = !(test.kind == TestKind.AUTO && runActive)
        if (emphasized) {
            FilledTonalButton(onClick = onAction, enabled = enabled) { Text(label) }
        } else {
            OutlinedButton(onClick = onAction, enabled = enabled) { Text(label) }
        }
    }
}
