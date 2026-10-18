package io.github.rthery.devicechecker.interactive

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
import androidx.core.content.ContextCompat
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.checks.fail
import io.github.rthery.devicechecker.checks.notApplicable
import io.github.rthery.devicechecker.checks.pass
import io.github.rthery.devicechecker.checks.warn
import kotlinx.coroutines.launch

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
        Button(onClick = { launcher.launch(denied.toTypedArray()) }) { Text("Grant permission") }
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
        OutlinedButton(onClick = { failed = !shortcut.launch(context) }) { Text(shortcut.label) }
        if (failed) Text("Could not open this screen on this build.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun YesNo(question: String, yes: String = "Yes", no: String = "No", onAnswer: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(question, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAnswer(true) }) { Text(yes) }
            OutlinedButton(onClick = { onAnswer(false) }) { Text(no) }
        }
    }
}

class StepAction(val label: String, val run: suspend TestScope.() -> String)

class Step(
    val text: String,
    val shortcut: Shortcut? = null,
    val action: StepAction? = null,
)

enum class StepAnswer(val label: String) { PASS("Works"), FAIL("Broken"), SKIP("Can't test") }

/** One card per step, each answered by the tester. Fails if any step fails. */
@Composable
fun GuidedSteps(scope: TestScope, intro: String, steps: List<Step>) {
    val answers = remember { mutableStateMapOf<Int, StepAnswer>() }
    val outputs = remember { mutableStateMapOf<Int, String>() }
    val coroutines = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Instructions(intro)
        steps.forEachIndexed { index, step ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${index + 1}. ${step.text}", style = MaterialTheme.typography.bodyMedium)
                    step.shortcut?.let { ShortcutButton(it) }
                    step.action?.let { action ->
                        OutlinedButton(onClick = {
                            coroutines.launch { outputs[index] = runCatching { action.run(scope) }.getOrElse { it.message.orEmpty() } }
                        }) { Text(action.label) }
                    }
                    outputs[index]?.let { Mono(it) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StepAnswer.entries.forEach { answer ->
                            FilterChip(selected = answers[index] == answer, onClick = { answers[index] = answer }, label = { Text(answer.label) })
                        }
                    }
                }
            }
        }
        Button(
            onClick = { scope.finish(summarize(steps, answers, outputs)) },
            enabled = answers.size == steps.size,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save result") }
    }
}

private fun summarize(steps: List<Step>, answers: Map<Int, StepAnswer>, outputs: Map<Int, String>): CheckResult {
    val details = steps.indices.joinToString("\n") { i ->
        "${answers[i]?.label ?: "?"}: ${steps[i].text}${outputs[i]?.let { "\n  $it" }.orEmpty()}"
    }
    val failed = answers.values.count { it == StepAnswer.FAIL }
    val skipped = answers.values.count { it == StepAnswer.SKIP }
    return when {
        failed > 0 -> fail("$failed step(s) broken", details)
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
