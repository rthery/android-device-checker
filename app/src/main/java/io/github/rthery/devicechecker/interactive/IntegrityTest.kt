package io.github.rthery.devicechecker.interactive

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.checks.CheckContext
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.checks.fail
import io.github.rthery.devicechecker.checks.pass
import io.github.rthery.devicechecker.checks.warn
import io.github.rthery.devicechecker.shell.Shell
import io.github.rthery.devicechecker.shell.ShellOutput
import io.github.rthery.devicechecker.ui.UiText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val INTEGRITY_APP = "gr.nikolasspyr.integritycheck"
private const val INTEGRITY_ACTIVITY = "$INTEGRITY_APP/.MainActivity"
private const val PLAY_STORE = "com.android.vending"
private const val UI_DUMP = "/data/local/tmp/devicechecker-ui.xml"
private const val POLL_MS = 2_000L
private const val SCREEN_TIMEOUT_MS = 15_000L
private const val VERDICT_TIMEOUT_MS = 60_000L

data class UiNode(val resourceId: String, val text: String, val description: String, val bounds: List<Int>?, val checked: Boolean = false) {
    val center: Pair<Int, Int>? get() = bounds?.let { (left, top, right, bottom) -> (left + right) / 2 to (top + bottom) / 2 }
}

private val NODE = Regex("<node((?:\\s+[\\w:-]+=(?:\"[^\"]*\"|'[^']*'))*)\\s*/?>")
private val ATTRIBUTE = Regex("([\\w:-]+)=(?:\"([^\"]*)\"|'([^']*)')")
private val BOUNDS = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]")
private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#\\d+|lt|gt|quot|apos|amp);")

private fun decodeXml(value: String): String = ENTITY.replace(value) { match ->
    when (val name = match.groupValues[1]) {
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        "amp" -> "&"
        else -> (if (name.startsWith("#x")) name.drop(2).toInt(16) else name.drop(1).toInt()).toChar().toString()
    }
}

/** Nodes of a `uiautomator dump`, in document order. */
fun parseUiDump(xml: String): List<UiNode> = NODE.findAll(xml).map { node ->
    val attributes = ATTRIBUTE.findAll(node.groupValues[1]).associate { it.groupValues[1] to decodeXml(it.groupValues[2] + it.groupValues[3]) }
    UiNode(
        resourceId = attributes["resource-id"].orEmpty(),
        text = attributes["text"].orEmpty(),
        description = attributes["content-desc"].orEmpty(),
        bounds = attributes["bounds"]?.let { BOUNDS.matchEntire(it) }?.groupValues?.drop(1)?.map(String::toInt),
        checked = attributes["checked"] == "true",
    )
}.toList()

private fun List<UiNode>.byId(id: String) = firstOrNull { it.resourceId == "$INTEGRITY_APP:id/$id" }

data class IntegrityVerdict(
    val device: List<String>,
    val appRecognition: String?,
    val licensing: String?,
    val playProtect: String?,
) {
    val meetsBasic: Boolean get() = "MEETS_BASIC_INTEGRITY" in device
}

private fun JsonElement?.at(vararg path: String): JsonElement? =
    path.fold(this) { element, key -> (element as? JsonObject)?.get(key) }

private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The decoded token JSON the integrity app shows; null when [json] is not one. */
fun parseIntegrityJson(json: String): IntegrityVerdict? {
    val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
    val deviceIntegrity = root["deviceIntegrity"] ?: return null
    return IntegrityVerdict(
        device = (deviceIntegrity.at("deviceRecognitionVerdict") as? JsonArray)?.mapNotNull { it.string() }.orEmpty(),
        appRecognition = root.at("appIntegrity", "appRecognitionVerdict").string(),
        licensing = root.at("accountDetails", "appLicensingVerdict").string(),
        playProtect = root.at("environmentDetails", "playProtectVerdict").string(),
    )
}

fun integrityResult(verdict: IntegrityVerdict, installer: String?, json: String): CheckResult {
    val details = buildString {
        appendLine("Device: ${verdict.device.ifEmpty { listOf("no verdict") }.joinToString()}")
        appendLine("App recognition: ${verdict.appRecognition ?: "?"}")
        appendLine("Licensing: ${verdict.licensing ?: "?"}")
        appendLine("Play Protect: ${verdict.playProtect ?: "?"}")
        appendLine("Installed by: ${installer ?: "unknown"}")
        if (installer != PLAY_STORE) appendLine("Not installed from the Play Store (App Lounge), so app recognition and licensing are not meaningful.")
        append(json)
    }
    return if (verdict.meetsBasic) pass("Meets basic integrity", details) else fail("Does not meet basic integrity", details)
}

/** Fallback when the JSON dialog cannot be read: the result icons only say Pass or Fail. */
fun integrityIconResult(basicIcon: String, installer: String?): CheckResult {
    val details = "Read from the result icon; the JSON response could not be read.\nInstalled by: ${installer ?: "unknown"}"
    return when (basicIcon) {
        "Pass" -> pass("Meets basic integrity", details)
        "Fail" -> fail("Does not meet basic integrity", details)
        else -> warn("Unknown basic integrity result: $basicIcon", details)
    }
}

private const val ADB_ROOT_NOTE = "adbd runs as root (service.adb.root=1). Play Integrity does not meet basic integrity while it does."

/** A fail while adbd runs as root says nothing about the build, so it becomes a warning. */
fun withAdbRootNote(result: CheckResult, adbRoot: Boolean): CheckResult = when {
    !adbRoot -> result
    result.status == CheckStatus.FAIL -> result.copy(
        status = CheckStatus.WARN,
        summary = "Fails while adbd runs as root: run adb unroot and test again",
        details = "$ADB_ROOT_NOTE\n${result.details}",
    )
    else -> result.copy(details = "$ADB_ROOT_NOTE\n${result.details}")
}

/** `pm list packages -i` line: `package:<name>  installer=<installer>`. */
fun parseInstaller(output: String): String? =
    Regex("installer=(\\S+)").find(output)?.groupValues?.get(1)?.takeIf { it != "null" }

/**
 * Drives Play Integrity API Checker through the shell uid: starts it fresh, taps CHECK,
 * reads the verdict JSON from its screen, then brings [returnComponent] back to the front.
 */
suspend fun runIntegrityCheck(shell: Shell, returnComponent: String?, log: (String) -> Unit = {}): CheckResult {
    if (!shell.exec("pm path $INTEGRITY_APP").output.startsWith("package:")) {
        return warn("Play Integrity API Checker is not installed", "Install it from App Lounge, then run the test again.")
    }
    val installer = parseInstaller(shell.exec("pm list packages -i $INTEGRITY_APP").output)
    log("Installed by ${installer ?: "unknown"}")
    val adbRoot = shell.exec("getprop service.adb.root").output.trim() == "1"
    if (adbRoot) log("adbd runs as root: basic integrity will fail")
    try {
        return withAdbRootNote(driveIntegrityApp(shell, installer, log), adbRoot)
    } finally {
        shell.exec("rm -f $UI_DUMP")
        returnComponent?.let { shell.exec("am start -n $it") }
    }
}

internal suspend fun Shell.dumpUi(): List<UiNode> {
    exec("input keyevent KEYCODE_WAKEUP")
    return parseUiDump(exec("uiautomator dump $UI_DUMP >/dev/null && cat $UI_DUMP").output)
}

internal suspend fun Shell.tap(node: UiNode): ShellOutput? = node.center?.let { (x, y) -> exec("input tap $x $y") }

internal suspend fun Shell.waitFor(timeoutMs: Long, found: (List<UiNode>) -> Boolean): List<UiNode>? {
    var waited = 0L
    while (waited <= timeoutMs) {
        val nodes = dumpUi()
        if (found(nodes)) return nodes
        delay(POLL_MS)
        waited += POLL_MS
    }
    return null
}

private suspend fun driveIntegrityApp(shell: Shell, installer: String?, log: (String) -> Unit): CheckResult {
    log("Starting Play Integrity API Checker")
    shell.exec("input keyevent KEYCODE_WAKEUP; am start -W -S -n $INTEGRITY_ACTIVITY")
    val start = shell.waitFor(SCREEN_TIMEOUT_MS) { it.byId("check_btn") != null }
        ?: return warn("Could not see Play Integrity API Checker's screen", "Unlock the phone and keep the screen on, then run again.")

    log("Requesting a verdict")
    shell.tap(start.byId("check_btn")!!)
    val done = shell.waitFor(VERDICT_TIMEOUT_MS) { nodes ->
        nodes.byId("basic_integrity_icon")?.description.let { it != null && it != "Unknown" } || nodes.byId("message") != null
    } ?: return warn("No verdict after ${VERDICT_TIMEOUT_MS / 1000} s", "Check the network and microG, then run again.")

    done.byId("message")?.let { error ->
        return warn("Play Integrity API Checker reported an error", error.text)
    }
    val basicIcon = done.byId("basic_integrity_icon")!!.description
    log("Basic integrity icon: $basicIcon")

    val jsonButton = done.byId("json_response") ?: return integrityIconResult(basicIcon, installer)
    shell.tap(jsonButton)
    val dialog = shell.waitFor(SCREEN_TIMEOUT_MS) { nodes -> nodes.byId("message")?.text?.trimStart()?.startsWith("{") == true }
    val json = dialog?.byId("message")?.text
    dialog?.firstOrNull { it.resourceId == "android:id/button1" }?.let { shell.tap(it) }
    val verdict = json?.let(::parseIntegrityJson) ?: return integrityIconResult(basicIcon, installer)
    return integrityResult(verdict, installer, json)
}

private val appLoungeLink = Shortcut(R.string.integrity_open_app_lounge) {
    listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$INTEGRITY_APP")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$INTEGRITY_APP")),
    )
}

private val manualSteps = listOf(
    Step(R.string.guided_integrity_basic_step_1, shortcut = appLoungeLink),
    Step(R.string.guided_integrity_basic_step_2),
)

val integrityTest = InteractiveTest(
    "integrity.basic", "Play Integrity basic",
    "Runs Play Integrity API Checker through Shizuku and reads its verdict. Without Shizuku, guides you through it.",
) { IntegrityTest(it) }

@Composable
private fun IntegrityTest(scope: TestScope) {
    val shell = scope.privilegedShell
    if (shell == null) {
        GuidedSteps(scope, "integrity.basic", UiText.Res(R.string.integrity_manual_intro), manualSteps)
        return
    }
    val coroutines = rememberCoroutineScope()
    val log = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CheckResult?>(null) }
    val returnComponent = remember {
        scope.activity.packageManager.getLaunchIntentForPackage(scope.activity.packageName)?.component?.flattenToShortString()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Instructions(stringResource(R.string.integrity_auto_intro))
        ShortcutButton(appLoungeLink)
        Button(enabled = !running, onClick = {
            log.clear()
            result = null
            running = true
            coroutines.launch {
                val finished = runCatching { runIntegrityCheck(shell, returnComponent) { log += it } }
                    .getOrElse { warn("The run stopped: ${it.message}") }
                log += "${finished.status}: ${finished.summary}"
                result = finished
                running = false
            }
        }) { Text(stringResource(if (running) R.string.integrity_running else R.string.integrity_run)) }
        if (log.isNotEmpty()) Mono(log.joinToString("\n"))
        result?.let { finished ->
            if (finished.details.isNotBlank()) Mono(finished.details)
            Button(onClick = { scope.finish(finished) }) { Text(stringResource(R.string.save_result)) }
        }
    }
}

/** Headless entry, from `--es run integrity.basic`. */
suspend fun CheckContext.integrityHeadless(): CheckResult {
    if (!hasPrivilegedShell) return needsShell("Needs Shizuku to drive Play Integrity API Checker")
    val shell = object : Shell {
        override suspend fun exec(command: String) = execPrivileged(command) ?: ShellOutput(-1, "Shizuku disconnected")
    }
    val returnComponent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component?.flattenToShortString()
    return runIntegrityCheck(shell, returnComponent)
}
