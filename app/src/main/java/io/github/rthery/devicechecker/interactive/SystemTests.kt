package io.github.rthery.devicechecker.interactive

import io.github.rthery.devicechecker.checks.CheckContext
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.fail
import io.github.rthery.devicechecker.checks.pass
import io.github.rthery.devicechecker.checks.warn
import io.github.rthery.devicechecker.shell.Shell
import io.github.rthery.devicechecker.shell.ShellOutput
import kotlinx.coroutines.delay

private const val MICROG_PACKAGE = "com.google.android.gms"
private const val MICROG_SETTINGS = "$MICROG_PACKAGE/org.microg.gms.ui.SettingsActivity"
private const val PRIVACY_PACKAGE = "foundation.e.advancedprivacy"
private const val UI_TIMEOUT_MS = 10_000L
private const val SCROLLS_MAX = 8
private const val TOGGLE_ROUNDS = 3
private const val TOGGLE_SETTLE_MS = 1_500L

private fun CheckContext.privileged(): Shell = object : Shell {
    override suspend fun exec(command: String) = execPrivileged(command) ?: ShellOutput(-1, "Shizuku disconnected")
}

private fun CheckContext.returnToApp(shell: Shell): suspend () -> Unit = {
    context.packageManager.getLaunchIntentForPackage(context.packageName)?.component?.flattenToShortString()?.let { shell.exec("am start -n $it") }
}

private suspend fun Shell.scrollDown() = exec("input swipe 540 1500 540 500 300")

/** Scrolls until a node with exactly [text] shows, then returns it. */
private suspend fun Shell.findText(text: String): UiNode? {
    repeat(SCROLLS_MAX) {
        dumpUi().firstOrNull { it.text == text }?.let { return it }
        scrollDown()
        delay(500)
    }
    return null
}

private suspend fun Shell.resumedActivity(): String =
    exec("dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'").output.trim()

/** Opens microG's self-check and reads every row: each must be ticked. */
suspend fun CheckContext.microgSelfCheckHeadless(): CheckResult {
    if (!hasPrivilegedShell) return needsShell("Needs Shizuku to read microG's self-check")
    val shell = privileged()
    if (!shell.exec("pm path $MICROG_PACKAGE").output.startsWith("package:")) return fail("microG is not installed")
    try {
        shell.exec("input keyevent KEYCODE_WAKEUP; am start -W -S -n $MICROG_SETTINGS")
        val main = shell.waitFor(UI_TIMEOUT_MS) { nodes -> nodes.any { it.text == "Self-Check" } }
            ?: return warn("Could not see microG settings", "Unlock the phone and keep the screen on, then run again.")
        val exposure = main.indexOfFirst { it.text == "Exposure Notifications" }.takeIf { it >= 0 }?.let { main.getOrNull(it + 1)?.text }
        shell.tap(main.first { it.text == "Self-Check" })
        shell.waitFor(UI_TIMEOUT_MS) { nodes -> nodes.any { it.resourceId.endsWith(":id/self_check_name") } }
            ?: return warn("The self-check screen did not open")
        val rows = linkedMapOf<String, Boolean>()
        for (scroll in 0 until SCROLLS_MAX) {
            val nodes = shell.dumpUi()
            val before = rows.size
            nodes.forEachIndexed { index, node ->
                if (!node.resourceId.endsWith(":id/self_check_name")) return@forEachIndexed
                val result = nodes.drop(index + 1).firstOrNull { it.resourceId.endsWith(":id/self_check_result") } ?: return@forEachIndexed
                rows.putIfAbsent(node.text.trimEnd(':'), result.checked)
            }
            if (rows.size == before && before > 0) break
            shell.scrollDown()
            delay(500)
        }
        if (rows.isEmpty()) return warn("Could not read the self-check rows")
        val failed = rows.filterValues { !it }.keys
        val details = rows.entries.joinToString("\n") { "${if (it.value) "OK" else "NOT OK"}: ${it.key}" } +
            "\nExposure Notifications: ${exposure ?: "not offered in this build"}"
        return when {
            failed.isNotEmpty() -> fail("${failed.size} self-check item(s) not ticked: ${failed.joinToString()}", details)
            exposure != null && !exposure.startsWith("Off") -> fail("Exposure Notifications is not off by default", details)
            else -> pass("All ${rows.size} self-check items ticked, Exposure Notifications off", details)
        }
    } finally {
        returnToApp(shell)()
    }
}

/** Finds Advanced Privacy in Settings, opens it and checks its dashboard is on top. */
suspend fun CheckContext.privacyDashboardHeadless(): CheckResult {
    if (!hasPrivilegedShell) return needsShell("Needs Shizuku to drive Settings")
    val shell = privileged()
    if (!shell.exec("pm path $PRIVACY_PACKAGE").output.startsWith("package:")) return fail("Advanced Privacy is not installed")
    try {
        shell.exec("input keyevent KEYCODE_WAKEUP; am start -W -S -a android.settings.SETTINGS")
        val entry = shell.findText("Advanced Privacy") ?: return fail("Settings has no Advanced Privacy entry")
        shell.tap(entry)
        var top = ""
        repeat((UI_TIMEOUT_MS / 1_000).toInt()) {
            delay(1_000)
            top = shell.resumedActivity()
            if (PRIVACY_PACKAGE in top) return pass("Settings opens the Advanced Privacy dashboard", top)
        }
        return fail("Tapping Advanced Privacy in Settings did not open it", top)
    } finally {
        returnToApp(shell)()
    }
}

/** Toggles Extra Dim and Night Light through their settings and reads the controller state back each time. */
suspend fun CheckContext.extraDimHeadless(): CheckResult {
    if (!hasPrivilegedShell) return needsShell("Needs Shizuku to toggle Extra Dim")
    val shell = privileged()
    val modes = listOf(
        Triple("Extra Dim", "reduce_bright_colors_activated", "Reduce bright colors:"),
        Triple("Night Light", "night_display_activated", "Night display:"),
    )
    val lines = mutableListOf<String>()
    var stuck = false
    for ((name, setting, section) in modes) {
        val original = shell.exec("settings get secure $setting").output.trim()
        try {
            repeat(TOGGLE_ROUNDS) { round ->
                for (wanted in listOf(true, false)) {
                    shell.exec("settings put secure $setting ${if (wanted) 1 else 0}")
                    delay(TOGGLE_SETTLE_MS)
                    val state = shell.exec("dumpsys color_display | grep -A1 '$section' | grep -m1 Activated").output.trim()
                    val ok = state == "Activated: $wanted"
                    if (!ok) stuck = true
                    lines += "$name round ${round + 1} ${if (wanted) "on" else "off"}: ${state.ifEmpty { "no state" }}${if (ok) "" else " (expected $wanted)"}"
                }
            }
        } finally {
            shell.exec(if (original == "null" || original.isEmpty()) "settings delete secure $setting" else "settings put secure $setting $original")
        }
    }
    val details = lines.joinToString("\n")
    return if (stuck) fail("Extra Dim or Night Light did not follow its setting", details)
    else pass("Extra Dim and Night Light turn on and off $TOGGLE_ROUNDS times each", details)
}
