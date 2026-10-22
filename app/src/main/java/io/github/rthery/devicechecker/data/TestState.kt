package io.github.rthery.devicechecker.data

import androidx.annotation.StringRes
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.catalog.Rom
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.pack.Waiver
import io.github.rthery.devicechecker.ui.UiText

enum class TestState(@StringRes val labelRes: Int) {
    PASS(R.string.tests_state_pass),
    FAIL(R.string.tests_state_fail),
    WARN(R.string.tests_state_warn),
    NEEDS(R.string.tests_state_needs),
    NOT_RUN(R.string.tests_state_not_run),
    NA(R.string.tests_state_na),
    WAIVED(R.string.tests_state_waived),
    RUNNING(R.string.tests_state_running),
    QUEUED(R.string.tests_state_queued),
}

// These probes only ask for the corresponding functional test after detecting hardware.
// Keep stock comparisons, warnings and failures independent.
private val functionalConfirmation = mapOf(
    "wifi.present" to "network.wifi", "bluetooth.present" to "bluetooth.scan",
    "gnss.present" to "gnss.fix", "nfc.present" to "nfc.tag",
    "fingerprint.present" to "fingerprint.auth", "ir.present" to "ir.transmit",
    "camera.list" to "camera.capture", "audio.devices" to "audio.loopback",
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
fun lineFor(test: UnifiedTest, state: TestState, result: CheckResult?, waiver: Waiver? = null): UiText {
    val summary = result?.summary?.let(UiText::Raw)
    return when (state) {
        TestState.WAIVED -> UiText.Res(R.string.tests_line_waived, listOf(waiver?.reason.orEmpty()))
        TestState.RUNNING -> UiText.Res(R.string.tests_line_running)
        TestState.QUEUED -> UiText.Res(R.string.tests_line_queued)
        TestState.PASS -> if (result?.status == CheckStatus.INFO) UiText.Res(R.string.tests_line_confirmed) else summary ?: UiText.Res(state.labelRes)
        TestState.NA -> summary ?: test.scopeLabel?.let(UiText::Raw) ?: UiText.Res(R.string.tests_line_marked_na)
        TestState.NOT_RUN -> UiText.Res(
            when (test.kind) {
                TestKind.AUTO -> R.string.tests_line_not_run_auto
                TestKind.GUIDED -> R.string.tests_line_not_run_guided
                TestKind.MANUAL -> if (test.isRequirementOnly) R.string.tests_line_not_run_requirement else R.string.tests_line_not_run_manual
                TestKind.OFF_DEVICE -> R.string.tests_line_not_run_off_device
            },
        )
        else -> summary ?: UiText.Res(state.labelRes)
    }
}
