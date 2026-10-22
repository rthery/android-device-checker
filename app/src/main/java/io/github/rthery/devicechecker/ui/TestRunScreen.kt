package io.github.rthery.devicechecker.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rthery.devicechecker.MainViewModel
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.data.TestState
import io.github.rthery.devicechecker.data.UnifiedTest
import io.github.rthery.devicechecker.data.Verdict
import io.github.rthery.devicechecker.data.isTask
import io.github.rthery.devicechecker.data.lineFor
import io.github.rthery.devicechecker.data.stateOf
import io.github.rthery.devicechecker.interactive.TestScope
import io.github.rthery.devicechecker.shell.ShizukuShell

private enum class RunPhase { CATEGORY, TEST, SAVED }

/**
 * Hands-on tests one after another: a screen when the category changes, the test itself,
 * then what was saved and what comes next. A single test runs the same way without a counter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuidedRunScreen(viewModel: MainViewModel, queue: List<String>, startId: String, onExit: () -> Unit) {
    val catalog = viewModel.catalog.collectAsStateWithLifecycle().value
    val results by viewModel.results.collectAsStateWithLifecycle()
    val verdicts by viewModel.verdicts.collectAsStateWithLifecycle()
    val deviceInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()
    var currentId by rememberSaveable(queue, startId) { mutableStateOf(startId) }
    var phase by rememberSaveable(queue, startId) { mutableStateOf(if (queue.size > 1) RunPhase.CATEGORY else RunPhase.TEST) }
    // Saved or skipped in this run: never offered again, even when left for later.
    var handled by rememberSaveable(queue, startId) { mutableStateOf(listOf<String>()) }
    val current = catalog.byId[currentId]
    if (current == null) {
        LaunchedEffect(currentId) { onExit() }
        return
    }

    fun stateOf(test: UnifiedTest) = stateOf(test, results, verdicts, deviceInfo.rom, null, catalog.waiverFor(test))
    val tests = queue.mapNotNull { catalog.byId[it] }
    val pending = tests.filter { it.id !in handled && it.id != currentId && stateOf(it).isTask(it.kind) }
    val position = queue.indexOf(currentId)
    val next = pending.firstOrNull { queue.indexOf(it.id) > position } ?: pending.firstOrNull()
    fun goTo(test: UnifiedTest?) {
        if (test == null) return onExit()
        phase = if (test.category != current.category) RunPhase.CATEGORY else RunPhase.TEST
        currentId = test.id
    }

    val skipCategory = {
        val inCategory = tests.filter { it.category == current.category }.map { it.id }
        handled = handled + inCategory
        goTo(pending.firstOrNull { it.category != current.category && it.id !in inCategory })
    }

    val view = LocalView.current
    DisposableEffect(view) {
        // Interactive tests do not publish automatic-run progress. Keep their visible
        // screen awake too, without changing the automatic runner's window flags.
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(if (queue.size > 1) stringResource(R.string.guided_run_title) else current.title, maxLines = 1)
                            if (queue.size > 1) Text(
                                if (position >= 0) stringResource(R.string.guided_run_position, position + 1, queue.size) else stringResource(R.string.guided_run_redo),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = { IconButton(onClick = onExit) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.guided_run_close)) } },
                )
                if (queue.size > 1) RunSegments(tests, currentId) { !stateOf(it).isTask(it.kind) }
            }
        },
        bottomBar = {
            // Kept outside the scrolling content so the actions stay visible above long category lists.
            if (phase == RunPhase.CATEGORY) Column(Modifier.navigationBarsPadding()) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = skipCategory, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.guided_skip_category)) }
                    Button(onClick = { phase = RunPhase.TEST }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.guided_start)) }
                }
            }
            if (phase == RunPhase.TEST && queue.size > 1) Column(Modifier.navigationBarsPadding()) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { handled = handled + currentId; goTo(next) }) { Text(stringResource(R.string.guided_skip_test)) }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (phase) {
                RunPhase.CATEGORY -> CategoryIntro(current, (listOf(current) + pending).filter { it.category == current.category })
                RunPhase.TEST -> if (current.isRequirementOnly) RequirementQuestion(current) { verdict ->
                    viewModel.markTest(current, verdict)
                    handled = handled + current.id
                    phase = RunPhase.SAVED
                } else TestRunContent(viewModel, current.id) {
                    handled = handled + current.id
                    phase = RunPhase.SAVED
                }
                RunPhase.SAVED -> SavedResult(
                    test = current, state = stateOf(current), line = lineFor(current, stateOf(current), results[current.id], catalog.waiverFor(current)),
                    details = results[current.id]?.details.orEmpty(),
                    next = next, newCategory = next != null && next.category != current.category,
                    onChange = { phase = RunPhase.TEST }, onNext = { goTo(next) }, onExit = onExit,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RunSegments(tests: List<UnifiedTest>, currentId: String, isDone: (UnifiedTest) -> Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        tests.forEach { test ->
            val color = when {
                test.id == currentId -> scheme.primary.copy(alpha = 0.5f)
                isDone(test) -> scheme.primary
                else -> scheme.surfaceContainerHighest
            }
            Box(Modifier.weight(1f).height(4.dp).background(color, RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
private fun CategoryIntro(current: UnifiedTest, members: List<UnifiedTest>) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.guided_next_category).uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(current.category.titleRes), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            members.forEach { test ->
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)).heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(test.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    test.level?.let { Text(it.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

/** A requirement no screen can test: the tester checks it and answers. */
@Composable
private fun RequirementQuestion(test: UnifiedTest, onAnswer: (Verdict) -> Unit) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                listOfNotNull(stringResource(test.kind.labelRes), test.level?.label, stringResource(test.category.titleRes)).joinToString(" · ").uppercase(),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(test.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                test.requirements.forEach { Text("${it.level.label}: ${it.text}", style = MaterialTheme.typography.titleMedium) }
                test.how.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        listOf(
            Triple(Verdict.PASS, R.string.guided_requirement_met, Icons.Filled.Check),
            Triple(Verdict.FAIL, R.string.guided_requirement_not_met, Icons.Filled.Close),
            Triple(Verdict.NOT_APPLICABLE, R.string.tests_detail_not_applicable, Glyphs.Block),
        ).forEach { (verdict, label, icon) -> AnswerButton(stringResource(label), icon, primary = verdict == Verdict.PASS) { onAnswer(verdict) } }
    }
}

@Composable
private fun AnswerButton(label: String, icon: ImageVector, primary: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        Icon(icon, null)
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
    if (primary) Button(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.large, content = content)
    else OutlinedButton(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.large, content = content)
}

@Composable
private fun SavedResult(
    test: UnifiedTest,
    state: TestState,
    line: UiText,
    details: String,
    next: UnifiedTest?,
    newCategory: Boolean,
    onChange: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit,
) {
    val answers = remember(details) { parseStepLines(details) }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StateDot(state, Modifier.size(64.dp))
                Text(stringResource(R.string.guided_saved, test.title).uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(state.labelRes) + ": " + line.resolve(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        answers.forEachIndexed { index, answer -> AnswerLine(index + 1, answer) }
        TextButton(onClick = onChange) { Text(stringResource(R.string.guided_change_answers)) }
        if (next != null) Button(onClick = onNext, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.guided_next, next.title))
                if (newCategory) Text(stringResource(R.string.guided_next_new_category, stringResource(next.category.titleRes)), style = MaterialTheme.typography.labelSmall)
            }
        }
        OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.guided_back_to_tests)) }
    }
}

/** The interactive test itself, with the crash watcher state and the previous result. */
@Composable
private fun TestRunContent(viewModel: MainViewModel, testId: String, onFinished: () -> Unit) {
    val catalog = viewModel.catalog.collectAsStateWithLifecycle().value
    val test = catalog.interactiveById[testId] ?: return
    val activity = LocalActivity.current as ComponentActivity
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val scope = remember(testId, profile) {
        TestScope(activity, profile, ShizukuShell.takeIf { it.isReady }) { result ->
            viewModel.saveTestResult(testId, result)
            onFinished()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(test.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(test.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            stringResource(if (scope.crashWatcher.available) R.string.tests_run_crash_watcher_on else R.string.tests_run_crash_watcher_off),
            style = MaterialTheme.typography.labelSmall,
            color = if (scope.crashWatcher.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        results[testId]?.let { previous ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(previous.status.name.replace('_', ' '), previous.status.color())
                Text(stringResource(R.string.tests_run_last_result, previous.summary), style = MaterialTheme.typography.bodySmall)
            }
        }
        test.content(scope)
    }
}
