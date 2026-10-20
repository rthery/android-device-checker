package io.github.rthery.devicechecker.checks

import android.os.Build

private val APPROVED_GOVERNORS = setOf(
    "conservative", "energy_aware", "energy_step", "interactive", "ondemand", "performance",
    "powersave", "sched", "sched_pixel", "schedutil", "userspace", "walt",
)

private val APPROVED_IO_SCHEDULERS = setOf("bfq", "cfq", "deadline", "kyber", "mq-deadline", "noop", "row", "ssg")

private val IGNORED_BLOCK_DEVICES = listOf("loop", "ram", "zram", "dm-")

/** Per-policy directories on newer kernels, per-CPU directories on older ones (3.x). */
private const val CPUFREQ_DIRS =
    "dirs=\$(ls -d /sys/devices/system/cpu/cpufreq/policy* 2>/dev/null); [ -z \"\$dirs\" ] && dirs=\$(ls -d /sys/devices/system/cpu/cpu[0-9]*/cpufreq)"

/** Skips unreadable files and also names common disks, for policies that block listing /sys/block. */
internal const val IO_SCHEDULER_COMMAND =
    "for f in /sys/block/*/queue/scheduler \$(for d in mmcblk0 mmcblk1 sda sdb sdc sdd sde sdf sdg sdh; do echo /sys/block/\$d/queue/scheduler; done); do " +
        "[ -r \"\$f\" ] || continue; d=\${f#/sys/block/}; echo \"\${d%%/*}: \$(cat \$f)\"; done | sort -u"

internal fun hasSchedulerLines(output: String) = output.lines().any { SCHEDULER_LINE.matches(it.trim()) }

/** The kernel marks the active scheduler in brackets, which error messages never have. */
private val SCHEDULER_LINE = Regex("^[A-Za-z0-9_-]+: ([\\w-]+ )*\\[[\\w-]+]( [\\w-]+)*$")

private const val EXFAT_MAINLINE_MAJOR = 5
private const val EXFAT_MAINLINE_MINOR = 7

private const val ANDROID_16_SDK = 36
private val EBPF_BACKPORT_FLOOR = KernelVersion(4, 14)
private val EBPF_NATIVE = KernelVersion(5, 4)

internal data class KernelVersion(val major: Int, val minor: Int) : Comparable<KernelVersion> {
    override fun compareTo(other: KernelVersion) = compareValuesBy(this, other, { it.major }, { it.minor })
    override fun toString() = "$major.$minor"

    companion object {
        fun parse(release: String): KernelVersion? =
            Regex("^(\\d+)\\.(\\d+)").find(release)?.destructured?.let { (a, b) -> KernelVersion(a.toInt(), b.toInt()) }
    }
}

val kernelChecks = listOf(
    Check("kernel.governors", "CPU governors") {
        val read = read("$CPUFREQ_DIRS; for d in \$dirs; do cat \$d/scaling_available_governors; done") { it.isNotBlank() }
        val output = read.output ?: return@Check needsShell()
        val governors = output.split(Regex("\\s+")).filter { it.isNotBlank() }.toSortedSet()
        val unapproved = governors - APPROVED_GOVERNORS
        if (unapproved.isEmpty()) pass("All governors approved: ${governors.joinToString()}", access = read.access)
        else fail("Unapproved governors: ${unapproved.joinToString()}", "Available: ${governors.joinToString()}", read.access)
    },

    Check("kernel.ioschedulers", "I/O schedulers") {
        val read = read(IO_SCHEDULER_COMMAND, ::hasSchedulerLines)
        val output = read.output ?: return@Check needsShell(manualCommand = "cat /sys/block/*/queue/scheduler")
        val perDevice = output.lines()
            .filter(::hasSchedulerLines)
            .mapNotNull { line -> line.split(":", limit = 2).takeIf { it.size == 2 } }
            .filter { (device, _) -> IGNORED_BLOCK_DEVICES.none { device.startsWith(it) } }
            .associate { (device, value) -> device to value.split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.trim('[', ']') } }
        val schedulers = perDevice.values.flatten().toSortedSet()
        val unapproved = schedulers - APPROVED_IO_SCHEDULERS - "none"
        val details = perDevice.entries.joinToString("\n") { (device, list) -> "$device: ${list.joinToString()}" }
        when {
            unapproved.isNotEmpty() -> fail("Unapproved schedulers: ${unapproved.joinToString()}", details, read.access)
            "none" in schedulers -> warn(
                "Only approved schedulers, plus 'none'",
                "'none' is the blk-mq equivalent of noop but is not named in the charter.\n$details",
                read.access,
            )
            else -> pass("All schedulers approved: ${schedulers.joinToString()}", details, read.access)
        }
    },

    Check("kernel.clocks", "CPU frequency tables") {
        val read = read(
            "$CPUFREQ_DIRS; for d in \$dirs; do echo \"\$d min=\$(cat \$d/cpuinfo_min_freq) max=\$(cat \$d/cpuinfo_max_freq)\"; done"
        ) { it.contains("max=") }
        val output = read.output ?: return@Check needsShell()
        val lines = output.lines().map { it.trim().removePrefix("/sys/devices/system/cpu/") }.filter { it.isNotBlank() }
        compareWithStock("CPU frequency ranges", stock?.cpuFrequencies, lines, output, read.access)
            ?: info("Compare these with stock (kHz), or import a stock capture", output, read.access)
    },

    Check("kernel.exfat", "exFAT support") {
        val filesystems = readFileAsApp("/proc/filesystems") ?: read("cat /proc/filesystems").output ?: return@Check needsShell()
        val hasExfat = filesystems.lines().any { it.trim().endsWith("exfat") }
        val kernel = System.getProperty("os.version").orEmpty()
        val mainlineCapable = (KernelVersion.parse(kernel) ?: KernelVersion(0, 0)) >= KernelVersion(EXFAT_MAINLINE_MAJOR, EXFAT_MAINLINE_MINOR)
        when {
            !hasExfat -> pass("exFAT is not built in, which the charter allows", "Kernel $kernel")
            mainlineCapable -> pass("exFAT on kernel $kernel", "Allowed if it is the mainline or vendor driver.")
            profile.stockHasExfat -> pass("exFAT on kernel $kernel, stock had exFAT")
            else -> fail("exFAT on kernel $kernel but stock did not support it")
        }
    },

    Check("kernel.release", "Kernel for the LineageOS release") {
        val release = System.getProperty("os.version").orEmpty()
        val version = KernelVersion.parse(release) ?: return@Check warn("Cannot read the kernel version", release)
        val android = "Android ${Build.VERSION.RELEASE}"
        val details = "Kernel $release on $android"
        when {
            Build.VERSION.SDK_INT < ANDROID_16_SDK -> info("Kernel $version on $android: no published kernel floor for this release", details)
            version < EBPF_BACKPORT_FLOOR -> warn(
                "Kernel $version on $android: LineageOS takes 4.4 and 4.9 devices only as 22.2 submissions",
                "$details\n23.x and later need $EBPF_BACKPORT_FLOOR or later with eBPF backports equivalent to $EBPF_NATIVE.",
            )
            version < EBPF_NATIVE -> info(
                "Kernel $version on $android: accepted only with full eBPF backports",
                "$details\nCheck the kernel tree has eBPF backported to match $EBPF_NATIVE and builds without GCC.",
            )
            else -> pass("Kernel $version meets the $android floor ($EBPF_NATIVE)", details)
        }
    },
)
