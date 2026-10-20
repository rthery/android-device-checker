package io.github.rthery.devicechecker.checks

import android.os.Build
import android.os.SystemClock
import io.github.rthery.devicechecker.shell.Shell
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Crash(val process: String, val kind: String, val reason: String, val frames: List<String>, val time: String) {
    fun describe() = "$time $process: $kind $reason${frames.takeIf { it.isNotEmpty() }?.joinToString("\n    ", prefix = "\n    ").orEmpty()}"
}

private val LOGCAT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
private val FATAL_SIGNAL = Regex("""Fatal signal (\d+) \((\w+)\).*pid \d+ \(([^)]+)\)""")
private val CMDLINE = Regex("""Cmdline: (.+)""")
private val ABORT = Regex("""Abort message: '(.+)'""")
private val FRAME = Regex("""#0[0-9] pc [0-9a-f]+\s+(\S+)(?: \((.*)\))?$""")
private val BUILD_ID = Regex(""" \(BuildId: [0-9a-f]+\)$""")
private val JAVA_PROCESS = Regex("""Process: ([\w.:]+)""")
private val JAVA_EXCEPTION = Regex("""AndroidRuntime: ([\w.$]+(?:Exception|Error)[^\n]*)""")
private const val MAX_FRAMES = 4

/**
 * Reads the crash log buffer through Shizuku (shell uid can read every process's crashes).
 * Without it the app can only see its own crashes, so the watcher reports itself as unavailable.
 */
class CrashWatcher(private val privilegedShell: Shell?) {

    val available: Boolean get() = privilegedShell != null

    fun mark(): Long = System.currentTimeMillis()

    suspend fun crashesSince(startMillis: Long): List<Crash> {
        val shell = privilegedShell ?: return emptyList()
        val since = LOGCAT_TIME.format(Instant.ofEpochMilli(startMillis))
        val output = shell.exec("logcat -b crash -d -v threadtime -T '$since'").output
        return parseCrashLog(output)
    }

    suspend fun crashesSinceBoot(): List<Crash> {
        val shell = privilegedShell ?: return emptyList()
        return parseCrashLog(shell.exec("logcat -b crash -d -v threadtime").output)
    }

    /** Dropbox keeps crashes across logcat clears, reboots and OTAs, so it holds entries from older builds too. */
    suspend fun dropbox(): List<DropboxEntry> {
        val shell = privilegedShell ?: return emptyList()
        val tags = DROPBOX_CRASH_TAGS.joinToString(" ")
        val output = shell.exec(
            "for t in $tags; do dumpsys dropbox --print \$t | grep -E '^20[0-9-]+ [0-9:]+ [A-Za-z_]+ \\(|^Cmdline:|^Process:|^Subject:|^Abort message:|^Build:|^Build fingerprint:'; done"
        ).output
        return parseDropbox(output)
    }

    companion object {
        /** Wall-clock time of boot; /proc/uptime is not readable by apps on every build. */
        fun bootTimeMillis(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

        /** Build.FINGERPRINT can differ from ro.build.fingerprint when a ROM spoofs it, and dropbox may log either. */
        fun currentFingerprints(propFingerprint: String): Set<String> = setOfNotNull(Build.FINGERPRINT, propFingerprint).filter { it.isNotBlank() }.toSet()
    }
}

val DROPBOX_CRASH_TAGS = listOf(
    "SYSTEM_TOMBSTONE", "system_app_crash", "system_app_native_crash", "data_app_crash", "data_app_native_crash",
    "system_server_crash", "system_server_native_crash", "system_app_anr", "data_app_anr", "SYSTEM_RESTART",
    "system_server_wtf", "system_app_wtf",
)

data class DropboxEntry(
    val timeMillis: Long,
    val time: String,
    val tag: String,
    val process: String,
    val reason: String,
    /** Fingerprint of the build that logged it, when the entry records one. */
    val build: String? = null,
) {
    val isWtf get() = tag.endsWith("_wtf")
}

/** Entries logged since [bootMillis] by the running build; entries without a fingerprint are judged by time alone. */
fun List<DropboxEntry>.fromCurrentBoot(bootMillis: Long, fingerprints: Set<String>): List<DropboxEntry> =
    filter { it.timeMillis >= bootMillis && (it.build == null || fingerprints.isEmpty() || it.build in fingerprints) }

private val DROPBOX_HEADER = Regex("""^(20\d\d-\d\d-\d\d \d\d:\d\d:\d\d) ([A-Za-z_]+) \(""")
private val DROPBOX_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

fun parseDropbox(output: String): List<DropboxEntry> {
    val entries = mutableListOf<DropboxEntry>()
    var header: MatchResult? = null
    var process = "?"
    var reason = ""
    var build: String? = null
    fun flush() {
        val h = header ?: return
        val millis = runCatching { Instant.from(DROPBOX_TIME.parse(h.groupValues[1])).toEpochMilli() }.getOrDefault(0)
        entries += DropboxEntry(millis, h.groupValues[1], h.groupValues[2], process, reason, build)
    }
    for (line in output.lines()) {
        val match = DROPBOX_HEADER.find(line)
        when {
            match != null -> {
                flush()
                header = match
                process = "?"
                reason = ""
                build = null
            }
            build == null && (line.startsWith("Build:") || line.startsWith("Build fingerprint:")) -> build = line.substringAfter(':').trim().trim('\'').ifEmpty { null }
            line.startsWith("Cmdline:") || line.startsWith("Process:") -> process = line.substringAfter(':').trim().substringBefore(',')
            line.startsWith("Abort message:") -> reason = line.substringAfter(':').trim().trim('\'')
            line.startsWith("Subject:") && reason.isEmpty() -> reason = line.substringAfter(':').trim()
        }
    }
    flush()
    return entries
}

/** Groups the native (tombstone) and Java crash blocks of `logcat -b crash` into one entry per crash. */
fun parseCrashLog(log: String): List<Crash> {
    val crashes = mutableListOf<Crash>()
    var current: MutableMap<String, Any>? = null

    fun flush() {
        val c = current ?: return
        @Suppress("UNCHECKED_CAST")
        crashes += Crash(
            process = c["process"] as String,
            kind = c["kind"] as String,
            reason = (c["reason"] as String?).orEmpty(),
            frames = (c["frames"] as MutableList<String>).take(MAX_FRAMES),
            time = c["time"] as String,
        )
        current = null
    }

    for (line in log.lines()) {
        val time = line.take(18)
        FATAL_SIGNAL.find(line)?.let { match ->
            flush()
            current = mutableMapOf("process" to match.groupValues[3], "kind" to "signal ${match.groupValues[1]} ${match.groupValues[2]}", "frames" to mutableListOf<String>(), "time" to time)
            return@let
        }
        if (line.contains("FATAL EXCEPTION")) {
            flush()
            current = mutableMapOf("process" to "?", "kind" to "java crash", "frames" to mutableListOf<String>(), "time" to time)
            continue
        }
        val c = current ?: continue
        CMDLINE.find(line)?.let { c["process"] = it.groupValues[1].trim() }
        JAVA_PROCESS.find(line)?.takeIf { c["kind"] == "java crash" }?.let { c["process"] = it.groupValues[1] }
        ABORT.find(line)?.let { c["reason"] = it.groupValues[1] }
        JAVA_EXCEPTION.find(line)?.takeIf { c["kind"] == "java crash" && c["reason"] == null && !line.contains("FATAL EXCEPTION") }?.let {
            c["reason"] = it.groupValues[1].trim()
        }
        FRAME.find(line.replace(BUILD_ID, ""))?.let { match ->
            @Suppress("UNCHECKED_CAST")
            (c["frames"] as MutableList<String>) += "${match.groupValues[1].substringAfterLast('/')} ${match.groupValues[2]}".trim()
        }
    }
    flush()
    return crashes
}

/** Downgrades a result to FAIL when processes crashed while it ran, and lists the crashes. */
fun CheckResult.withCrashes(crashes: List<Crash>): CheckResult {
    if (crashes.isEmpty()) return this
    val list = crashes.joinToString("\n") { it.describe() }
    val processes = crashes.map { it.process }.distinct().joinToString()
    return copy(
        status = CheckStatus.FAIL,
        summary = "$summary. Crashed during the test: $processes",
        details = listOf(details, "Crashes during the test:\n$list").filter { it.isNotBlank() }.joinToString("\n\n"),
    )
}
