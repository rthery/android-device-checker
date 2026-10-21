package io.github.rthery.devicechecker.pack

import android.content.ComponentName
import android.content.Intent
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.ui.UiText
import io.github.rthery.devicechecker.catalog.Requirement
import io.github.rthery.devicechecker.catalog.TestMode
import io.github.rthery.devicechecker.checks.Access
import io.github.rthery.devicechecker.checks.Check
import io.github.rthery.devicechecker.checks.CheckContext
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.fail
import io.github.rthery.devicechecker.checks.needsShell
import io.github.rthery.devicechecker.checks.pass
import io.github.rthery.devicechecker.interactive.GuidedSteps
import io.github.rthery.devicechecker.interactive.InteractiveTest
import io.github.rthery.devicechecker.interactive.Shortcut
import io.github.rthery.devicechecker.interactive.Step
import io.github.rthery.devicechecker.interactive.StepAction

/** What enabled packs add to the catalog. Ids are pack.<packId>.<testId>, shared by the requirement and its check or test. */
class PackContent(
    val requirements: List<Requirement>,
    val checks: List<Check>,
    val tests: List<InteractiveTest>,
    /** Requirement id to waiver; a later pack overrides an earlier one. */
    val waivers: Map<String, Waiver>,
    /** Requirement id to the name of the pack that waived it. */
    val waivedBy: Map<String, String>,
    /** Test id to the steps shown under "What the app does". */
    val how: Map<String, List<String>>,
    /** Built-in requirement id to the id of the pack requirement that replaces it. */
    val replaced: Map<String, String>,
) {
    companion object {
        val EMPTY = PackContent(emptyList(), emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap())

        fun from(packs: List<DevicePack>): PackContent {
            val requirements = mutableListOf<Requirement>()
            val checks = mutableListOf<Check>()
            val tests = mutableListOf<InteractiveTest>()
            val waivers = linkedMapOf<String, Waiver>()
            val waivedBy = linkedMapOf<String, String>()
            val how = mutableMapOf<String, List<String>>()
            val replaced = linkedMapOf<String, String>()
            packs.forEach { pack ->
                pack.tests.forEach { test ->
                    val id = pack.requirementId(test)
                    val automated = test is PropTest || test is FileTest || test is ShellTest
                    requirements += Requirement(
                        id = id,
                        section = test.category,
                        level = test.level,
                        text = test.title,
                        mode = if (automated) TestMode.AUTOMATED else TestMode.MANUAL,
                        checkIds = if (automated) listOf(id) else emptyList(),
                        testIds = if (automated) emptyList() else listOf(id),
                        note = test.description,
                        source = pack.name,
                    )
                    how[id] = listOfNotNull(test.description, describe(test))
                    test.replaces?.let { replaced[it] = id }
                    if (automated) checks += Check(id, test.title) { runAutomated(test) }
                    else tests += interactive(id, test, pack.name)
                }
                pack.waivers.forEach { waiver ->
                    waivers[waiver.requirement] = waiver
                    waivedBy[waiver.requirement] = pack.name
                }
            }
            return PackContent(requirements, checks, tests, waivers, waivedBy, how, replaced)
        }
    }
}

private fun describe(test: PackTest): String = when (test) {
    is PropTest -> "Reads ${test.prop} and expects ${test.equals?.let { "\"$it\"" } ?: "/${test.matches}/"}."
    is FileTest -> "Reads ${test.path} and expects it to ${test.contains?.let { "contain \"$it\"" } ?: "match /${test.matches}/"}."
    is ShellTest -> "Runs ${test.command} through Shizuku and looks for /${test.expect}/."
    is SettingsTest -> "Opens ${test.intent.describe()} and asks you to confirm."
    is GuidedTest -> "${test.steps.size} step(s) you answer one by one."
}

private suspend fun CheckContext.runAutomated(test: PackTest): CheckResult = when (test) {
    is PropTest -> {
        val value = prop(test.prop)
        val ok = PropCondition(test.prop, test.equals, test.matches).holds(value)
        val expected = test.equals?.let { "\"$it\"" } ?: "/${test.matches}/"
        if (ok) pass("${test.prop} = \"$value\"") else fail("${test.prop} = \"$value\", expected $expected")
    }
    is FileTest -> runFileTest(test)
    is ShellTest -> runShellTest(test)
    else -> error("Not an automated test")
}

private suspend fun CheckContext.runFileTest(test: FileTest): CheckResult {
    var access = Access.APP
    val content = readFileAsApp(test.path) ?: run {
        if (!test.path.startsWith("/sys/") && !test.path.startsWith("/proc/")) return fail("Cannot read ${test.path}")
        val command = "cat ${test.path}"
        CommandAllowlist.check(command)?.let { return fail("Read refused: $it") }
        val output = execPrivileged(command) ?: return needsShell()
        if (!output.succeeded) return fail("Cannot read ${test.path}", output.output)
        access = Access.SHIZUKU
        output.output.trim()
    }
    val ok = test.contains?.let { content.contains(it) } ?: Regex(test.matches.orEmpty()).containsMatchIn(content)
    val expected = test.contains?.let { "contains \"$it\"" } ?: "matches /${test.matches}/"
    val details = content.take(MAX_DETAILS)
    return if (ok) pass("${test.path} $expected", details, access) else fail("${test.path} does not satisfy: $expected", details, access)
}

private suspend fun CheckContext.runShellTest(test: ShellTest): CheckResult {
    CommandAllowlist.check(test.command)?.let { return fail("Command refused: $it") }
    val output = execPrivileged(test.command) ?: return needsShell("Pack shell commands run only through Shizuku. Connect it and run again.")
    val details = "$ ${test.command}\n${output.output.take(MAX_DETAILS)}"
    return if (Regex(test.expect).containsMatchIn(output.output)) pass("Output matches /${test.expect}/", details, Access.SHIZUKU)
    else fail("Output does not match /${test.expect}/", details, Access.SHIZUKU)
}

private fun interactive(id: String, test: PackTest, packName: String): InteractiveTest = when (test) {
    is SettingsTest -> InteractiveTest(id, test.title, test.description.orEmpty(), manual = true, source = packName) { scope ->
        GuidedSteps(scope, id, UiText.Raw(test.description ?: test.question), listOf(Step(test.question, shortcut = test.intent.toShortcut())))
    }
    is GuidedTest -> InteractiveTest(id, test.title, test.description.orEmpty(), manual = true, source = packName) { scope ->
        GuidedSteps(scope, id, UiText.Raw(test.intro), test.steps.map { step -> Step(step.text, step.shortcut?.toShortcut(), step.command?.let(::commandAction)) })
    }
    else -> error("Not an interactive test")
}

private fun IntentSpec.toShortcut() = Shortcut(label?.let { UiText.Raw(it) } ?: UiText.Res(R.string.shortcut_open_settings)) {
    val intent = if (packageName != null && className != null) Intent().setComponent(ComponentName(packageName, className)) else Intent(action)
    listOf(intent)
}

private fun commandAction(command: String) = StepAction(UiText.Res(R.string.step_run_command, listOf(command))) {
    if (!CommandAllowlist.isAllowed(command)) return@StepAction "Refused: not a read-only command"
    val shell = privilegedShell ?: return@StepAction "Needs Shizuku. Connect it from the home screen."
    shell.exec(command).output.ifBlank { "(no output)" }
}

private const val MAX_DETAILS = 4000
