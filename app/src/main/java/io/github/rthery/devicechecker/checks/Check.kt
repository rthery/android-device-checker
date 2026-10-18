package io.github.rthery.devicechecker.checks

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import io.github.rthery.devicechecker.data.StockSnapshot
import io.github.rthery.devicechecker.profile.DeviceProfile
import io.github.rthery.devicechecker.shell.AppShell
import io.github.rthery.devicechecker.shell.Shell
import io.github.rthery.devicechecker.shell.ShellOutput
import kotlinx.serialization.Serializable
import java.io.File

/** Ordered from most to least severe, so the worst status of several checks is the minimum. */
enum class CheckStatus { FAIL, ERROR, NEEDS_SHELL, WARN, PASS, INFO, NOT_APPLICABLE }

enum class Access { APP, SHIZUKU, NONE }

@Serializable
data class CheckResult(
    val status: CheckStatus,
    val summary: String,
    val details: String = "",
    val access: Access = Access.APP,
    val timestamp: Long = 0,
    val byTester: Boolean = false,
    /** Files the test left for review, relative to the reports folder. */
    val artifacts: List<String> = emptyList(),
)

class Check(val id: String, val title: String, val run: suspend CheckContext.() -> CheckResult)

data class ShellRead(val output: String?, val access: Access)

class CheckContext(
    val context: Context,
    val profile: DeviceProfile,
    private val privilegedShell: Shell?,
    val lineageBased: Boolean,
    /** Root of the reports folder; tests write review files under artifacts/<test id>/. */
    val reportsDir: File? = null,
    /** Numeric knobs from the caller, such as iterations or gnss_timeout. */
    val options: Map<String, Int> = emptyMap(),
    /** Stock capture to compare against, when one was imported. */
    val stock: StockSnapshot? = null,
) {
    val hasPrivilegedShell: Boolean get() = privilegedShell != null

    val crashWatcher = CrashWatcher(privilegedShell)

    suspend fun prop(name: String): String = AppShell.exec("getprop $name").output.trim()

    /** Runs as the app first, then through Shizuku when the app uid is not allowed to. */
    suspend fun read(command: String, accept: (String) -> Boolean = { true }): ShellRead {
        val asApp = AppShell.exec(command)
        if (asApp.succeeded && !asApp.output.isPermissionError() && accept(asApp.output)) {
            return ShellRead(asApp.output, Access.APP)
        }
        val privileged = privilegedShell ?: return ShellRead(null, Access.NONE)
        val asShell = privileged.exec(command)
        return if (asShell.succeeded && accept(asShell.output)) ShellRead(asShell.output, Access.SHIZUKU) else ShellRead(null, Access.NONE)
    }

    /** Shadows the top-level [needsShell]: with Shizuku connected, asking for it again would not help. */
    fun needsShell(summary: String = NEEDS_SHELL_SUMMARY): CheckResult =
        if (privilegedShell == null) CheckResult(CheckStatus.NEEDS_SHELL, summary, access = Access.NONE)
        else warn("Not readable even through Shizuku: this build's SELinux policy blocks adb shell from it", access = Access.NONE)

    /** Shizuku only; null when it is not connected. */
    suspend fun execPrivileged(command: String): ShellOutput? = privilegedShell?.exec(command)

    fun readFileAsApp(path: String): String? = runCatching { File(path).readText().trim() }.getOrNull()

    /** A fresh artifacts folder for one test, emptied of the previous run's files. */
    fun artifactsFor(testId: String): File? = reportsDir?.let { root ->
        File(root, "artifacts/$testId").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    fun relative(file: File): String = reportsDir?.let { file.relativeTo(it).path } ?: file.path

    /**
     * Returns the runtime permissions still missing. With Shizuku the app grants its own,
     * which is how a headless run gets the camera or microphone without a tap.
     */
    suspend fun ensurePermissions(permissions: List<String>): List<String> {
        fun missing() = permissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val before = missing()
        if (before.isEmpty() || privilegedShell == null) return before
        before.forEach { privilegedShell.exec("pm grant ${context.packageName} $it") }
        return missing()
    }

    /** Android withholds camera, microphone and sensors from background apps since 9. */
    fun isForeground(): Boolean {
        val process = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        return process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun String.isPermissionError() = contains("Permission denied", ignoreCase = true)
}

fun pass(summary: String, details: String = "", access: Access = Access.APP) = CheckResult(CheckStatus.PASS, summary, details, access)
fun fail(summary: String, details: String = "", access: Access = Access.APP) = CheckResult(CheckStatus.FAIL, summary, details, access)
fun warn(summary: String, details: String = "", access: Access = Access.APP) = CheckResult(CheckStatus.WARN, summary, details, access)
fun info(summary: String, details: String = "", access: Access = Access.APP) = CheckResult(CheckStatus.INFO, summary, details, access)
fun notApplicable(summary: String) = CheckResult(CheckStatus.NOT_APPLICABLE, summary)
private const val NEEDS_SHELL_SUMMARY = "The app uid cannot read this. Connect Shizuku and run again."

fun needsShell(summary: String = NEEDS_SHELL_SUMMARY) =
    CheckResult(CheckStatus.NEEDS_SHELL, summary, access = Access.NONE)
