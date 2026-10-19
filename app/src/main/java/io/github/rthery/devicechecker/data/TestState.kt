package io.github.rthery.devicechecker.data

import io.github.rthery.devicechecker.catalog.Rom
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.pack.Waiver

enum class TestState(val label: String) {
    PASS("Passed"),
    FAIL("Failed"),
    WARN("Check this"),
    NEEDS("Needs you"),
    NOT_RUN("Not run"),
    NA("N/A"),
    WAIVED("Waived"),
    RUNNING("Running"),
    QUEUED("Waiting"),
}

// These probes only ask for the corresponding functional test after detecting hardware.
// Keep stock comparisons, warnings and failures independent.
private val functionalConfirmation = mapOf(
    "wifi.present" to "network.wifi", "bluetooth.present" to "bluetooth.scan",
    "gnss.present" to "gnss.fix", "nfc.present" to "nfc.tag",
    "fingerprint.present" to "fingerprint.auth", "ir.present" to "ir.transmit",
    "camera.list" to "camera.capture", "audio.devices" to "audio.output",
)

fun stateOf(
    test: UnifiedTest,
    results: Map<String, CheckResult>,
    verdicts: Map<String, ManualVerdict>,
    rom: Rom?,
    run: RunProgress?,
    waiver: Waiver? = null,
): TestState {
    if (run != null && test.kind == TestKind.AUTO) {
        if (test.id == run.currentId) return TestState.RUNNING
        if (test.id in run.pendingIds) return TestState.QUEUED
    }
    if (!test.appliesTo(rom)) return TestState.NA
    if (waiver != null && verdicts[test.id] == null && results[test.id]?.byTester != true) return TestState.WAIVED
    if (test.isRequirementOnly) {
        return when (verdicts[test.id]?.verdict) {
            Verdict.PASS -> TestState.PASS
            Verdict.FAIL -> TestState.FAIL
            Verdict.NOT_APPLICABLE -> TestState.NA
            Verdict.UNTESTED, null -> TestState.NOT_RUN
        }
    }
    val result = results[test.id] ?: return TestState.NOT_RUN
    return when (result.status) {
        CheckStatus.PASS -> TestState.PASS
        CheckStatus.FAIL, CheckStatus.ERROR -> TestState.FAIL
        CheckStatus.WARN, CheckStatus.NEEDS_SHELL -> TestState.WARN
        CheckStatus.INFO -> if (functionalConfirmation[test.id]?.let { results[it]?.status == CheckStatus.PASS } == true) TestState.PASS else TestState.NEEDS
        CheckStatus.NOT_APPLICABLE -> TestState.NA
    }
}

/** Waiting on a person: data to compare, or a guided or manual test nobody ran yet. */
fun TestState.needsYou(kind: TestKind): Boolean =
    this == TestState.NEEDS || (this == TestState.NOT_RUN && (kind == TestKind.GUIDED || kind == TestKind.MANUAL))

/** One line under the title: the last summary, or what to do next. */
fun lineFor(test: UnifiedTest, state: TestState, result: CheckResult?, waiver: Waiver? = null): String = when (state) {
    TestState.WAIVED -> "Waived: ${waiver?.reason.orEmpty()}"
    TestState.RUNNING -> "Running..."
    TestState.QUEUED -> "Waiting"
    TestState.PASS -> if (result?.status == CheckStatus.INFO) "Confirmed by the follow-up test" else result?.summary ?: state.label
    TestState.NA -> result?.summary ?: test.scopeLabel ?: "Marked not applicable"
    TestState.NOT_RUN -> when (test.kind) {
        TestKind.AUTO -> "Not run yet"
        TestKind.GUIDED -> "Not run yet. Tap Start"
        TestKind.MANUAL -> if (test.isRequirementOnly) "Check it yourself, then mark it" else "Steps with shortcuts"
        TestKind.OFF_DEVICE -> "Check in the source tree or project"
    }
    else -> result?.summary ?: state.label
}
