package io.github.rthery.devicechecker.checks

import android.hardware.display.DisplayManager
import android.view.Display

/** Kernel nodes of known software wake gesture drivers; their presence means the kernel emulates gestures. */
internal val SOFTWARE_WAKE_GESTURE_DIRS = listOf("/sys/android_touch", "/sys/android_touch2")

internal const val PIE_SCAN_COMMAND =
    "for d in /system/bin /system/xbin /vendor/bin /odm/bin /product/bin /system_ext/bin /apex/*/bin; do [ -d \$d ] && readlink -f \$d; done | " +
        "sort -u | while read d; do file \$d/* \$d/*/* 2>/dev/null; done"

internal data class ElfFile(val path: String, val executable: Boolean, val static: Boolean) {
    val isVendor: Boolean get() = listOf("/vendor/", "/system/vendor/", "/odm/").any { path.startsWith(it) }
}

/** Reads toybox `file` output: ET_EXEC prints "ELF executable", PIE (ET_DYN) prints "ELF shared object". */
internal fun parseElfTypes(output: String): List<ElfFile> = output.lines().mapNotNull { line ->
    val path = line.substringBefore(':').trim()
    val type = line.substringAfter(':', "").trim()
    if (!type.startsWith("ELF ")) return@mapNotNull null
    ElfFile(path, executable = type.startsWith("ELF executable"), static = ", static" in type)
}

private const val CONFIG_READABLE = "zcat /proc/config.gz >/dev/null 2>&1 && echo config=readable"

internal const val KSM_COMMAND =
    "$CONFIG_READABLE; zcat /proc/config.gz 2>/dev/null | grep -E '^CONFIG_[UP]?KSM='; " +
        "for n in uksm pksm; do [ -e /sys/kernel/mm/\$n ] && echo node=/sys/kernel/mm/\$n; done; true"

internal val CUSTOM_KSM_CONFIGS = setOf("CONFIG_UKSM", "CONFIG_PKSM")

internal const val HOTPLUG_COMMAND =
    "$CONFIG_READABLE; zcat /proc/config.gz 2>/dev/null | " +
        "grep -E '^CONFIG_[A-Z0-9_]*(PLUG|ASMP|SLEEPER|DECISION|MPDEC|STATE_HELPER|CORE_CTL|BCL_CTL|THERMAL_MONITOR|MSM_PERFORMANCE)[A-Z0-9_]*=[ym]'; " +
        "echo modules=\$(ls /sys/module 2>/dev/null)"

/** Community hotplug drivers seen in custom kernels; the charter only allows OEM or SoC vendor ones. */
internal val THIRD_PARTY_HOTPLUG_CONFIGS = setOf(
    "CONFIG_INTELLI_PLUG", "CONFIG_ALUCARD_HOTPLUG", "CONFIG_MSM_HOTPLUG", "CONFIG_MAKO_HOTPLUG", "CONFIG_BRICKED_HOTPLUG",
    "CONFIG_AUTOSMP", "CONFIG_ASMP", "CONFIG_LAZYPLUG", "CONFIG_THUNDERPLUG", "CONFIG_BLU_PLUG", "CONFIG_MSM_SLEEPER",
    "CONFIG_ZEN_DECISION", "CONFIG_STATE_HELPER", "CONFIG_DYNAMIC_HOTPLUG", "CONFIG_MSM_MPDEC",
)
internal val THIRD_PARTY_HOTPLUG_MODULES = setOf(
    "intelli_plug", "alucard_hotplug", "msm_hotplug", "mako_hotplug", "bricked_hotplug", "autosmp", "asmp", "lazyplug",
    "thunderplug", "blu_plug", "msm_sleeper", "zen_decision", "state_helper", "msm_mpdecision",
)
internal val VENDOR_HOTPLUG = mapOf(
    "CONFIG_SCHED_CORE_CTL" to "core_ctl",
    "CONFIG_THERMAL_MONITOR" to "msm_thermal core control",
    "CONFIG_MSM_BCL_CTL" to "BCL",
    "CONFIG_MSM_PERFORMANCE" to "msm_performance",
)

/** Tuning nodes of custom-kernel drivers that write codec, haptic or regulator registers stock never touches. */
internal val REGISTER_TWEAK_NODES = mapOf(
    "/sys/devices/virtual/misc/soundcontrol" to "sound control (codec gain registers)",
    "/sys/kernel/sound_control" to "sound control (codec gain registers)",
    "/sys/kernel/sound_control_3" to "faux sound control (codec gain registers)",
    "/sys/devices/virtual/timed_output/vibrator/vmax_mv_strong" to "vibration strength (haptic vmax register)",
    "/sys/devices/system/cpu/cpu0/cpufreq/UV_mV_table" to "CPU undervolting",
    "/sys/devices/system/cpu/cpufreq/vdd_table/vdd_levels" to "CPU undervolting",
)

internal const val REGISTER_TWEAK_COMMAND = "echo scanned; for n in %s; do [ -e \$n ] && echo found=\$n; done; true"

internal class VendorLayout(val ab: Boolean, val separateVendor: Boolean, val details: String)

internal suspend fun CheckContext.vendorLayout(): VendorLayout {
    val mount = readFileAsApp("/proc/self/mounts").orEmpty().lines().firstOrNull { it.split(' ').getOrNull(1) == "/vendor" }
    val slot = prop("ro.boot.slot_suffix")
    val abUpdate = prop("ro.build.ab_update")
    return VendorLayout(
        ab = slot.isNotEmpty() || abUpdate == "true",
        separateVendor = mount != null,
        details = "ro.boot.slot_suffix=${slot.ifEmpty { "unset" }}, ro.build.ab_update=${abUpdate.ifEmpty { "unset" }}\n/vendor mount: ${mount ?: "none"}",
    )
}

val platformChecks = listOf(
    Check("kernel.wakegestures", "Software wake gestures") {
        val listing = read("for d in ${SOFTWARE_WAKE_GESTURE_DIRS.joinToString(" ")}; do [ -d \$d ] && for f in \$d/*; do echo \"\$f=\$(cat \$f 2>/dev/null)\"; done; done; true") { output ->
            output.lines().filter { "=" in it }.none { it.substringAfter('=').isBlank() }
        }
        val nodes = listing.output ?: return@Check fail("The kernel ships a software wake gesture driver; its state is unreadable without Shizuku")
        val lines = nodes.lines().filter { "=" in it }
        if (lines.isEmpty()) return@Check pass("No software wake gesture driver in the kernel", "Looked in ${SOFTWARE_WAKE_GESTURE_DIRS.joinToString()}")
        val details = lines.joinToString("\n")
        val enabled = lines.filter { line ->
            val name = line.substringBefore('=').substringAfterLast('/')
            name in setOf("doubletap2wake", "sweep2wake", "sweep2sleep", "wake_gestures", "camera_gesture") && line.substringAfter('=').trim().let { it.isNotEmpty() && it != "0" }
        }
        when {
            profile.stockHasWakeGestures -> info("Wake gesture nodes present, and stock firmware supports gestures. Check they use the firmware.", details)
            enabled.isNotEmpty() -> fail("Software wake gestures are on without firmware support: ${enabled.joinToString { it.substringBefore('=').substringAfterLast('/') }}", details)
            else -> fail("The kernel ships a software wake gesture driver (all off)", details)
        }
    },

    Check("verity.vendor", "Vendor verity") {
        val mounts = readFileAsApp("/proc/self/mounts").orEmpty()
        val vendor = mounts.lines().firstOrNull { it.split(' ').getOrNull(1) == "/vendor" }
            ?: return@Check notApplicable("No separate vendor partition: /vendor lives on the system partition")
        val device = vendor.substringBefore(' ')
        val fstab = read("cat /vendor/etc/fstab.* 2>/dev/null").output.orEmpty()
        val entry = fstab.lines().firstOrNull { line -> line.trim().split(Regex("\\s+")).getOrNull(1) == "/vendor" }.orEmpty()
        val details = "Mount: $vendor\nfstab: ${entry.ifEmpty { "no /vendor entry readable" }}"
        when {
            device.startsWith("/dev/block/dm-") -> pass("/vendor is mounted through device-mapper", details)
            Regex("\\b(avb|verify)\\b").containsMatchIn(entry) -> pass("The fstab enables verity on /vendor (inactive on this userdebug build)", details)
            entry.isEmpty() -> warn("Could not read the /vendor fstab entry", details)
            else -> warn("Nothing enables verity on /vendor", details)
        }
    },

    Check("treble.gsi", "Treble GSI support") {
        val treble = prop("ro.treble.enabled")
        if (treble != "true") return@Check notApplicable("Not a Treble device (ro.treble.enabled=${treble.ifEmpty { "unset" }}), so no GSI can boot")
        info("Treble device. Boot an AOSP GSI and check basic hardware.", "ro.vndk.version=${prop("ro.vndk.version")}")
    },

    Check("display.builtin", "Built-in display") {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null && display.flags and Display.FLAG_PRESENTATION == 0) notApplicable("The device has a built-in display (${display.name})")
        else info("No built-in display. Connect the stock outputs (for example HDMI) and check them.")
    },

    Check("accessories", "Proprietary accessories") {
        if (!profile.stockHasAccessories) notApplicable("The profile lists no proprietary accessories for this device")
        else info("Connect each proprietary accessory and check it works.")
    },

    Check("binaries.pie", "Position-independent binaries") {
        val scan = read(PIE_SCAN_COMMAND) { output -> output.lines().any { it.startsWith("/system/bin/") && "ELF" in it } }
        val output = scan.output ?: return@Check needsShell(manualCommand = PIE_SCAN_COMMAND)
        val elf = parseElfTypes(output)
        val nonPie = elf.filter { it.executable }
        val (platformStatic, flagged) = nonPie.partition { it.static && !it.isVendor }
        val details = buildString {
            append("${elf.size} ELF files scanned, ${nonPie.size} non-PIE")
            flagged.forEach { append("\n${it.path}${if (it.static) " (static)" else ""}") }
            if (platformStatic.isNotEmpty()) append("\nStatic platform binaries AOSP builds without PIE, ignored: ${platformStatic.joinToString { it.path }}")
        }
        val dynamic = flagged.filterNot { it.static }
        when {
            dynamic.isNotEmpty() -> fail("Non-PIE dynamic binaries: ${dynamic.joinToString { it.path.substringAfterLast('/') }}", details, scan.access)
            flagged.isNotEmpty() -> warn("Static non-PIE vendor binaries: ${flagged.joinToString { it.path.substringAfterLast('/') }}", details, scan.access)
            else -> pass("Every dynamic binary is PIE (${elf.size} scanned)", details, scan.access)
        }
    },

    Check("kernel.ksm", "KSM driver") {
        val read = read(KSM_COMMAND) { it.contains("config=readable") }
        val output = read.output ?: return@Check needsShell(manualCommand = KSM_COMMAND)
        val config = output.lines().filter { it.startsWith("CONFIG_") }
        val custom = config.map { it.substringBefore('=') }.filter { it in CUSTOM_KSM_CONFIGS } +
            output.lines().filter { it.startsWith("node=") }.map { it.removePrefix("node=") }
        val details = output.trim()
        when {
            custom.isNotEmpty() -> fail("Custom KSM driver: ${custom.distinct().joinToString()}", details, read.access)
            "CONFIG_KSM=y" in config -> pass("Upstream KSM only", details, read.access)
            else -> pass("No KSM in the kernel", details, read.access)
        }
    },

    Check("kernel.hotplug", "CPU hotplug drivers") {
        val read = read(HOTPLUG_COMMAND) { it.contains("config=readable") }
        val output = read.output ?: return@Check needsShell(manualCommand = HOTPLUG_COMMAND)
        val configs = output.lines().filter { it.startsWith("CONFIG_") }.map { it.substringBefore('=') }.toSet()
        val modules = output.lines().firstOrNull { it.startsWith("modules=") }?.removePrefix("modules=")?.split(' ')?.toSet().orEmpty()
        val thirdParty = (configs.filter { it in THIRD_PARTY_HOTPLUG_CONFIGS } + modules.filter { it in THIRD_PARTY_HOTPLUG_MODULES }).distinct()
        val vendor = VENDOR_HOTPLUG.filter { (config, _) -> config in configs }.values
        val details = "Kernel config:\n${output.lines().filter { it.startsWith("CONFIG_") }.joinToString("\n").ifEmpty { "unreadable" }}"
        when {
            thirdParty.isNotEmpty() -> fail("Third-party hotplug drivers: ${thirdParty.joinToString()}", details, read.access)
            vendor.isEmpty() -> pass("No third-party hotplug driver; only the kernel's own CPU hotplug", details, read.access)
            else -> pass("No third-party hotplug driver. Vendor hotplug: ${vendor.joinToString()}", details, read.access)
        }
    },

    Check("kernel.registers", "Hardware register tweaks") {
        val command = REGISTER_TWEAK_COMMAND.format(REGISTER_TWEAK_NODES.keys.joinToString(" "))
        // SELinux hides some sysfs folders from the app, which would read as "not found", so prefer Shizuku.
        val privileged = execPrivileged(command)?.takeIf { it.succeeded && "scanned" in it.output }
        val read = privileged?.let { ShellRead(it.output, Access.SHIZUKU) } ?: read(command) { it.contains("scanned") }
        val output = read.output ?: return@Check needsShell(manualCommand = command)
        val found = output.lines().filter { it.startsWith("found=") }.map { it.removePrefix("found=") }
        if (found.isEmpty()) {
            return@Check info("No known tweak node. Review the kernel's changes to stock for register writes stock does not do.", "Looked for ${REGISTER_TWEAK_NODES.keys.joinToString()}", read.access)
        }
        val details = found.joinToString("\n") { "$it: ${REGISTER_TWEAK_NODES[it]}" }
        fail("Register tweaks stock does not have: ${found.mapNotNull { REGISTER_TWEAK_NODES[it] }.distinct().joinToString()}", details, read.access)
    },

    Check("vendor.image", "Prebuilt vendor image") {
        val layout = vendorLayout()
        if (!layout.separateVendor) return@Check notApplicable("No vendor partition in use: /vendor is built into the system image")
        info("The build mounts its own /vendor. Check the install guide does not ask for a modified prebuilt vendor image.", layout.details)
    },

    Check("vendor.assert", "Vendor version assert") {
        val layout = vendorLayout()
        when {
            layout.ab -> notApplicable("A/B device: the A/B vendor rule applies instead")
            !layout.separateVendor -> notApplicable("No vendor partition in use: /vendor is built into the system image")
            else -> info("Non-A/B device with a vendor partition. Check the OTA's updater-script asserts the vendor version.", layout.details)
        }
    },

    Check("vendor.ab", "A/B vendor image") {
        val layout = vendorLayout()
        when {
            !layout.ab -> notApplicable("Not an A/B device")
            !layout.separateVendor -> notApplicable("A/B device without a vendor partition")
            else -> info("A/B device with a vendor partition. Check the build makes a vendor image, or the wiki documents firmware and slot sync.", layout.details)
        }
    },
)
