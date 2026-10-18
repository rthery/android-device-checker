package io.github.rthery.devicechecker.data

import io.github.rthery.devicechecker.BuildConfig
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.interactive.HeadlessRegistry
import io.github.rthery.devicechecker.profile.DeviceProfile
import kotlinx.serialization.Serializable

private const val PACKAGE = BuildConfig.APPLICATION_ID
private const val RECEIVER = "adb shell am broadcast -n $PACKAGE/.AdbCommandReceiver -a $PACKAGE"
private const val ACTIVITY = "adb shell am start -n $PACKAGE/.AdbLinkActivity"

@Serializable
data class NextStep(
    /** Test id, or "setup" for things that unblock many tests. */
    val id: String,
    val title: String,
    val why: String,
    /** The adb command to run, when one exists. */
    val command: String? = null,
    /** True when a person has to do or judge something. */
    val needsHuman: Boolean,
)

/** What is left, most useful first: setup that unblocks many tests, then failures to look at, then work for a person. */
fun nextSteps(
    tests: List<UnifiedTest>,
    states: Map<String, TestState>,
    results: Map<String, CheckResult>,
    profile: DeviceProfile,
    shizukuReady: Boolean,
    hasStock: Boolean,
): List<NextStep> {
    val steps = mutableListOf<NextStep>()
    if (!shizukuReady) {
        steps += NextStep("setup", "Connect Shizuku", "Shell-level reads, the crash watcher and automatic permission grants are off without it.", needsHuman = true)
    }
    val missingStock = listOfNotNull(
        "resolution".takeIf { profile.stockWidthPx == null || profile.stockHeightPx == null },
        "density".takeIf { profile.stockDensityDpi == null },
        "Wi-Fi MAC".takeIf { profile.stockWifiMac.isBlank() && profile.stockHasWifi },
        "Bluetooth MAC".takeIf { profile.stockBluetoothMac.isBlank() && profile.stockHasBluetooth },
    )
    if (!hasStock) {
        steps += NextStep(
            "setup", "Import a stock capture",
            "Tests compare with stock values${if (missingStock.isNotEmpty()) "; missing now: ${missingStock.joinToString()}" else ""}. On the stock ROM run CAPTURE_STOCK and adb pull reports/stock.json, then push it here and import it.",
            "$RECEIVER.IMPORT_STOCK", needsHuman = false,
        )
    }
    val headless = HeadlessRegistry.byId.keys
    val notRunAuto = tests.filter { it.kind == TestKind.AUTO && states[it.id] == TestState.NOT_RUN }
    if (notRunAuto.isNotEmpty()) {
        steps += NextStep("setup", "Run the automatic checks", "${notRunAuto.size} automatic tests have no result yet.", "$RECEIVER.RUN_CHECKS", needsHuman = false)
    }
    val notRunHeadless = tests.filter { it.id in headless && states[it.id] == TestState.NOT_RUN }
    if (notRunHeadless.isNotEmpty()) {
        steps += NextStep(
            "setup", "Run the headless tests", "${notRunHeadless.joinToString { it.id }} can run without anyone at the screen.",
            "$ACTIVITY --es run ${notRunHeadless.joinToString(",") { it.id }}", needsHuman = false,
        )
    }
    tests.forEach { test ->
        val state = states[test.id] ?: return@forEach
        val result = results[test.id]
        val setResult = "$RECEIVER.SET_RESULT --es test ${test.id} --es verdict <pass|fail|not_applicable>"
        when {
            state == TestState.FAIL -> steps += NextStep(test.id, test.title, "Failed: ${result?.summary ?: "marked failed"}", null, needsHuman = false)
            state == TestState.WARN -> steps += NextStep(
                test.id, test.title, "Needs a look: ${result?.summary.orEmpty()}",
                if (test.kind == TestKind.AUTO) "$RECEIVER.RUN_CHECKS --es ids ${test.id}" else null, needsHuman = false,
            )
            state == TestState.NEEDS && result?.artifacts?.isNotEmpty() == true -> steps += NextStep(
                test.id, test.title, "Review ${result.artifacts.joinToString()} then set the verdict. ${result.summary}", setResult, needsHuman = true,
            )
            state == TestState.NEEDS -> steps += NextStep(test.id, test.title, "Compare with stock or confirm: ${result?.summary.orEmpty()}", setResult, needsHuman = true)
            state == TestState.NOT_RUN && test.kind == TestKind.GUIDED && test.id !in headless -> steps += NextStep(
                test.id, test.title, "Needs a person at the phone: ${test.how.firstOrNull().orEmpty()}", "$ACTIVITY --es test ${test.id}", needsHuman = true,
            )
            state == TestState.NOT_RUN && test.kind == TestKind.MANUAL -> steps += NextStep(
                test.id, test.title, "Manual check: ${test.how.firstOrNull().orEmpty()}",
                if (test.isRequirementOnly) setResult else "$ACTIVITY --es test ${test.id}", needsHuman = true,
            )
        }
    }
    return steps
}
