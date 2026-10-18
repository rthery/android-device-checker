package io.github.rthery.devicechecker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rthery.devicechecker.MainViewModel
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

private val WHEN_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestDetailSheet(
    viewModel: MainViewModel,
    test: UnifiedTest,
    state: TestState,
    result: CheckResult?,
    runActive: Boolean,
    onDismiss: () -> Unit,
    onOpenTest: () -> Unit,
) {
    val catalog = viewModel.catalog.collectAsStateWithLifecycle().value
    val waiver = catalog.waiverFor(test)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val colors = state.colors()
                    Chip(state.label, colors.container, colors.content, colors.border)
                    Chip(test.kind.label)
                    LevelPill(test.level, Modifier.align(Alignment.CenterVertically))
                }
                Text(test.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }

            Section(if (test.requirements.size > 1) "Requirements it covers" else "Requirement") {
                test.requirements.forEach { requirement ->
                    Text("${requirement.level.label}: ${requirement.text}", style = MaterialTheme.typography.bodyLarge)
                    requirement.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                if (test.lineageOnly) Hint("Applies to LineageOS-based builds only")
                Hint(test.source?.let { "From the device pack $it" } ?: "From the ${RequirementCatalog.SOURCE_TITLE}")
                test.requirements.firstNotNullOfOrNull { catalog.replacedBy(it.id) }?.let { replaced ->
                    Hint("Replaces the built-in requirement: ${replaced.level.label}: ${replaced.text}")
                }
            }

            waiver?.let {
                Section("Granted exception") {
                    Text(it.reason, style = MaterialTheme.typography.bodyLarge)
                    it.reference?.let { reference -> Hint(reference) }
                    test.requirements.firstNotNullOfOrNull { requirement -> catalog.waivedBy(requirement.id) }?.let { pack -> Hint("Waived by $pack") }
                }
            }

            if (test.how.isNotEmpty()) {
                Section(if (test.kind == TestKind.OFF_DEVICE || test.isRequirementOnly) "How to check" else "What the app does") {
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

            if (result != null) {
                Section("Last result · ${WHEN_FORMAT.format(Instant.ofEpochMilli(result.timestamp))}") {
                    Text(result.summary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    if (result.details.isNotBlank()) {
                        Text(
                            result.details,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                        )
                    }
                    when {
                        result.byTester -> Hint("Set by the tester")
                        result.access == Access.SHIZUKU -> Hint("Read through Shizuku")
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    test.kind == TestKind.AUTO -> Button(
                        onClick = {
                            viewModel.runOne(test.id)
                            onDismiss()
                        },
                        enabled = !runActive,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text(if (result == null) "Run" else "Run again") }
                    !test.isRequirementOnly -> Button(onClick = onOpenTest, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(
                            when {
                                test.kind == TestKind.MANUAL -> "Open steps"
                                state == TestState.NOT_RUN -> "Start test"
                                state == TestState.NEEDS -> "Continue test"
                                else -> "Run again"
                            }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Verdict.PASS to "Mark passed", Verdict.FAIL to "Mark failed", Verdict.NOT_APPLICABLE to "Not applicable").forEach { (verdict, label) ->
                        OutlinedButton(
                            onClick = {
                                viewModel.markTest(test, verdict)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        ) { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2) }
                    }
                }
                if (result?.byTester == true || (test.isRequirementOnly && state != TestState.NOT_RUN)) {
                    TextButton(onClick = {
                        viewModel.markTest(test, Verdict.UNTESTED)
                        onDismiss()
                    }) { Text("Clear my verdict") }
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
