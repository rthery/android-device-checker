package io.github.rthery.devicechecker.data

import io.github.rthery.devicechecker.catalog.Requirement
import io.github.rthery.devicechecker.catalog.Rom
import io.github.rthery.devicechecker.catalog.TestMode
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.pack.Waiver

enum class Outcome(val label: String) {
    PASS("Pass"),
    FAIL("Fail"),
    WARN("Check"),
    NEEDS_SHELL("Needs Shizuku"),
    TO_VERIFY("To verify"),
    UNTESTED("Untested"),
    NOT_APPLICABLE("N/A"),
    WAIVED("Waived"),
    NOT_ON_DEVICE("Source review"),
}

/**
 * A manual verdict always wins. Interactive tests are human-confirmed, so passing them passes the requirement.
 * Otherwise only AUTOMATED items pass on their own; the rest need a human unless something failed.
 * A pack waiver drops the listed check and test results, or the whole requirement when it lists none.
 */
fun evaluate(
    requirement: Requirement,
    results: Map<String, CheckResult>,
    verdict: ManualVerdict?,
    rom: Rom?,
    waiver: Waiver? = null,
): Outcome {
    when (verdict?.verdict) {
        Verdict.PASS -> return Outcome.PASS
        Verdict.FAIL -> return Outcome.FAIL
        Verdict.NOT_APPLICABLE -> return Outcome.NOT_APPLICABLE
        Verdict.UNTESTED, null -> Unit
    }
    if (!requirement.appliesTo(rom)) return Outcome.NOT_APPLICABLE
    if (waiver != null && waiver.checks.isEmpty()) return Outcome.WAIVED
    if (requirement.mode == TestMode.OUT_OF_SCOPE) return Outcome.NOT_ON_DEVICE
    val waived = waiver?.checks.orEmpty().toSet()
    val checkIds = requirement.checkIds - waived
    val testIds = requirement.testIds - waived
    if (waived.isNotEmpty() && checkIds.isEmpty() && testIds.isEmpty()) return Outcome.WAIVED
    val testResults = testIds.map { results[it] }
    val statuses = checkIds.mapNotNull { results[it]?.status } + testResults.mapNotNull { it?.status }
    if (statuses.isEmpty()) return Outcome.UNTESTED
    if (statuses.all { it == CheckStatus.NOT_APPLICABLE }) return Outcome.NOT_APPLICABLE
    val humanConfirmed = testResults.filterNotNull() + checkIds.mapNotNull { results[it] }.filter { it.byTester }
    val testsPassed = testResults.all { it != null && (it.status == CheckStatus.PASS || it.status == CheckStatus.NOT_APPLICABLE) } &&
        humanConfirmed.any { it.status == CheckStatus.PASS }
    return when (statuses.min()) {
        CheckStatus.FAIL, CheckStatus.ERROR -> Outcome.FAIL
        CheckStatus.NEEDS_SHELL -> Outcome.NEEDS_SHELL
        CheckStatus.WARN -> Outcome.WARN
        CheckStatus.PASS, CheckStatus.INFO, CheckStatus.NOT_APPLICABLE ->
            if (testsPassed || (requirement.mode == TestMode.AUTOMATED && statuses.min() == CheckStatus.PASS)) Outcome.PASS else Outcome.TO_VERIFY
    }
}
