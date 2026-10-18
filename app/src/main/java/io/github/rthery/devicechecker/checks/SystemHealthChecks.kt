package io.github.rthery.devicechecker.checks

private const val CRASH_LOOP_THRESHOLD = 3
private const val WTF_FLOOD_PER_HOUR = 30

/** A native crash is filed as both a tombstone and an app native crash a moment apart. */
private const val SAME_CRASH_WINDOW_MS = 5_000L
private const val TOP_DENIALS = 12
private val VNDBINDER_WAIT = Regex("""Waiting for service '([^']+)' on '/dev/vndbinder'""")
private val AVC_DENIAL = Regex("""avc:\s+denied\s+\{([^}]+)\}.*scontext=u:r:([\w.]+):s0.*tcontext=u:(?:object_)?r:([\w.]+):s0.*tclass=(\w+)""")

val systemHealthChecks = listOf(
    Check("crashes.boot", "Crashes since boot") {
        val watcher = crashWatcher
        if (!watcher.available) return@Check needsShell("Reading other processes' crashes needs Shizuku.")
        val boot = CrashWatcher.bootTimeMillis()
        val entries = watcher.dropboxSince(boot)
        val crashes = entries.filterNot { it.isWtf }.sortedBy { it.timeMillis }.fold(mutableListOf<DropboxEntry>()) { kept, entry ->
            val duplicate = kept.lastOrNull { it.process == entry.process }?.let { entry.timeMillis - it.timeMillis <= SAME_CRASH_WINDOW_MS } == true
            if (!duplicate) kept += entry
            kept
        }
        val wtfs = entries.filter { it.isWtf }
        val restarts = entries.count { it.tag == "SYSTEM_RESTART" }
        val byProcess = crashes.groupBy { it.process }.toList().sortedByDescending { it.second.size }
        val wtfByOrigin = wtfs.groupingBy { "${it.process} ${it.reason}".trim() }.eachCount().toList().sortedByDescending { it.second }
        val hoursUp = ((System.currentTimeMillis() - boot) / 3_600_000.0).coerceAtLeast(0.1)
        val details = buildString {
            appendLine("Since boot (${"%.1f".format(hoursUp)} h), from dropbox:")
            byProcess.forEach { (process, list) ->
                appendLine("${list.size}x $process [${list.map { it.tag }.distinct().joinToString()}]")
                list.map { it.reason }.filter { it.isNotBlank() }.distinct().take(2).forEach { appendLine("    $it") }
            }
            if (wtfByOrigin.isNotEmpty()) {
                appendLine("wtf reports:")
                wtfByOrigin.take(5).forEach { (origin, count) -> appendLine("${count}x $origin (${"%.0f".format(count / hoursUp)}/h)") }
            }
        }.trim()
        val loops = byProcess.filter { it.second.size >= CRASH_LOOP_THRESHOLD }.map { it.first }
        val floods = wtfByOrigin.filter { it.second / hoursUp >= WTF_FLOOD_PER_HOUR }.map { it.first }
        when {
            restarts > 0 -> fail("The Android runtime restarted $restarts time(s) since boot", details, Access.SHIZUKU)
            loops.isNotEmpty() -> fail("Crash loop: ${loops.joinToString()}", details, Access.SHIZUKU)
            crashes.isNotEmpty() -> warn("${crashes.size} crash(es) since boot in ${byProcess.size} process(es)", details, Access.SHIZUKU)
            floods.isNotEmpty() -> warn("No crashes, but repeated wtf reports: ${floods.joinToString()}", details, Access.SHIZUKU)
            else -> pass("No crashes since boot", details.ifBlank { "Dropbox has no crash entries since boot." }, Access.SHIZUKU)
        }
    },

    Check("binder.vndbinder", "vndbinder and service lookups") {
        val nodes = read("ls -l /dev/vndbinder /dev/hwbinder /dev/binder 2>&1; echo resolved=\$(readlink -f /dev/vndbinder); [ -e /dev/vndbinder ] && echo vndbinder=present || echo vndbinder=missing; cat /sys/module/binder/parameters/devices 2>/dev/null; true") { it.contains("vndbinder=") }
        val output = nodes.output ?: return@Check needsShell()
        val log = read("logcat -d -b main,system | grep -E \"on '/dev/vndbinder'|didn't start. Returning NULL\" | tail -200") { true }.output.orEmpty()
        val waitedFor = VNDBINDER_WAIT.findAll(log).map { it.groupValues[1] }.groupingBy { it }.eachCount()
        val details = output + "\n\nServices waited for on /dev/vndbinder (current log):\n" +
            waitedFor.entries.joinToString("\n") { "${it.value}x ${it.key}" }.ifEmpty { "none" }
        val treble = prop("ro.treble.enabled") == "true"
        when {
            output.contains("vndbinder=missing") && !treble && waitedFor.isEmpty() ->
                pass("No /dev/vndbinder, and none is needed: this is not a Treble build", details, nodes.access)
            output.contains("vndbinder=missing") -> fail("/dev/vndbinder does not exist: vendor services cannot register", details, nodes.access)
            waitedFor.isNotEmpty() -> warn(
                "Vendor processes wait for ${waitedFor.size} service(s) on vndbinder",
                "Each entry is a vendor process looking up a framework service on the wrong binder, or an undeclared optional HAL.\n$details",
                nodes.access,
            )
            else -> pass("vndbinder present, no failed lookups in the current log", details, nodes.access)
        }
    },

    Check("selinux.denials", "SELinux denials") {
        val read = read("logcat -d -b main,system,events,crash | grep 'avc:  denied' | tail -2000") { true }
        val log = read.output ?: return@Check needsShell()
        val denials = AVC_DENIAL.findAll(log).map { "${it.groupValues[2]} -> ${it.groupValues[3]}:${it.groupValues[4]} {${it.groupValues[1].trim()}}" }
            .groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
        val permissive = log.lines().count { it.contains("permissive=1") }
        val details = denials.take(TOP_DENIALS).joinToString("\n") { "${it.second}x ${it.first}" } +
            if (denials.size > TOP_DENIALS) "\n... ${denials.size - TOP_DENIALS} more distinct denials" else ""
        when {
            denials.isEmpty() -> pass("No SELinux denials in the current log", access = read.access)
            permissive > 0 -> warn("${denials.size} distinct denials allowed only because the domain is permissive. They break an enforcing build.", details, read.access)
            else -> warn("${denials.size} distinct denials in the current log", details, read.access)
        }
    },
)
