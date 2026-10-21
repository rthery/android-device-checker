package io.github.rthery.devicechecker.interactive

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.data.Store
import io.github.rthery.devicechecker.ui.UiText
import io.github.rthery.devicechecker.ui.englishContext
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.checks.fail
import io.github.rthery.devicechecker.checks.notApplicable
import io.github.rthery.devicechecker.checks.pass
import io.github.rthery.devicechecker.checks.warn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Composable
fun RequirePermissions(permissions: List<String>, rationale: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    fun missing() = permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    var denied by remember { mutableStateOf(missing()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { denied = missing() }

    if (denied.isEmpty()) {
        content()
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(rationale, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { launcher.launch(denied.toTypedArray()) }) { Text(stringResource(R.string.grant_permission)) }
    }
}

@Composable
fun Instructions(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
fun Mono(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

@Composable
fun ShortcutButton(shortcut: Shortcut) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { failed = !shortcut.launch(context) }) { Text(shortcut.label.resolve()) }
        if (failed) Text(stringResource(R.string.shortcut_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun YesNo(question: String, yes: String = stringResource(R.string.yes), no: String = stringResource(R.string.no), onAnswer: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(question, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAnswer(true) }) { Text(yes) }
            OutlinedButton(onClick = { onAnswer(false) }) { Text(no) }
        }
    }
}

/** [run] returns command output, which is saved in the result as is. */
class StepAction(val label: UiText, val run: suspend TestScope.() -> String) {
    constructor(@StringRes label: Int, run: suspend TestScope.() -> String) : this(UiText.Res(label), run)
}

class Step(
    val text: UiText,
    val shortcut: Shortcut? = null,
    val action: StepAction? = null,
) {
    constructor(@StringRes text: Int, shortcut: Shortcut? = null, action: StepAction? = null) : this(UiText.Res(text), shortcut, action)
    constructor(text: String, shortcut: Shortcut? = null, action: StepAction? = null) : this(UiText.Raw(text), shortcut, action)
}

@Serializable
enum class StepAnswer(@StringRes val label: Int) { PASS(R.string.step_works), FAIL(R.string.step_broken), SKIP(R.string.step_cant_test) }

/** Answers and action outputs kept between visits, keyed by step index. */
@Serializable
data class GuidedDraft(val answers: Map<Int, StepAnswer> = emptyMap(), val outputs: Map<Int, String> = emptyMap()) {
    fun isEmpty() = answers.isEmpty() && outputs.isEmpty()
}

/** One card per step, each answered by the tester. Fails if any step fails; unanswered steps can be left for later. */
@Composable
fun GuidedSteps(scope: TestScope, testId: String, intro: UiText, steps: List<Step>) {
    val english = LocalContext.current.englishContext()
    val store = remember { Store(scope.activity) }
    val draft = remember { store.loadGuidedDraft(testId) }
    val answers = remember { mutableStateMapOf<Int, StepAnswer>().apply { putAll(draft.answers.filterKeys { it in steps.indices }) } }
    val outputs = remember { mutableStateMapOf<Int, String>().apply { putAll(draft.outputs.filterKeys { it in steps.indices }) } }
    val coroutines = rememberCoroutineScope()
    fun persist() = store.saveGuidedDraft(testId, GuidedDraft(answers.toMap(), outputs.toMap()))

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Instructions(intro.resolve())
        steps.forEachIndexed { index, step ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${index + 1}. ${step.text.resolve()}", style = MaterialTheme.typography.bodyMedium)
                    step.shortcut?.let { ShortcutButton(it) }
                    step.action?.let { action ->
                        OutlinedButton(onClick = {
                            coroutines.launch {
                                outputs[index] = runCatching { action.run(scope) }.getOrElse { it.message.orEmpty() }
                                persist()
                            }
                        }) { Text(action.label.resolve()) }
                    }
                    outputs[index]?.let { Mono(it) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StepAnswer.entries.forEach { answer ->
                            FilterChip(
                                selected = answers[index] == answer,
                                onClick = {
                                    if (answers[index] == answer) answers.remove(index) else answers[index] = answer
                                    persist()
                                },
                                label = { Text(stringResource(answer.label)) },
                            )
                        }
                    }
                }
            }
        }
        val unanswered = steps.size - answers.size
        if (unanswered > 0 && answers.isNotEmpty()) {
            Text(stringResource(R.string.guided_steps_left_for_later, unanswered), style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = { scope.finish(summarize(english, steps, answers, outputs)) },
            enabled = answers.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.save_result)) }
        if (answers.isNotEmpty() || outputs.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    answers.clear()
                    outputs.clear()
                    persist()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.guided_clear_answers)) }
        }
    }
}

internal fun summarize(english: Context, steps: List<Step>, answers: Map<Int, StepAnswer>, outputs: Map<Int, String>): CheckResult {
    val details = steps.indices.joinToString("\n") { i ->
        "${answers[i]?.let { english.getString(it.label) } ?: "Not answered yet"}: ${steps[i].text.resolve(english)}${outputs[i]?.let { "\n  $it" }.orEmpty()}"
    }
    val failed = answers.values.count { it == StepAnswer.FAIL }
    val skipped = answers.values.count { it == StepAnswer.SKIP }
    val unanswered = steps.size - answers.size
    return when {
        failed > 0 -> fail("$failed step(s) broken", details)
        unanswered > 0 -> warn("$unanswered step(s) left for later", details)
        skipped == steps.size -> notApplicable("All steps skipped").copy(details = details)
        skipped > 0 -> warn("$skipped step(s) not tested", details)
        else -> pass("All steps work", details)
    }
}

/** Collects per-item outcomes, for tests that run over a list of devices or cameras. */
class ItemResults {
    val lines = mutableStateListOf<Pair<CheckStatus, String>>()

    fun add(status: CheckStatus, line: String) {
        lines += status to line
    }

    fun toResult(passSummary: String, emptySummary: String): CheckResult {
        val details = lines.joinToString("\n") { (status, line) -> "${status.name}: $line" }
        val worst = lines.minOfOrNull { it.first }
        return when (worst) {
            null -> warn(emptySummary)
            CheckStatus.FAIL, CheckStatus.ERROR -> fail("${lines.count { it.first <= CheckStatus.ERROR }} item(s) failed", details)
            CheckStatus.WARN, CheckStatus.NEEDS_SHELL -> warn("${lines.count { it.first == CheckStatus.WARN }} item(s) need a look", details)
            else -> pass(passSummary, details)
        }
    }
}
