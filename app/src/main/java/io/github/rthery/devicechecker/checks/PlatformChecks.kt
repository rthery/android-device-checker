package io.github.rthery.devicechecker.checks

import android.hardware.display.DisplayManager
import android.view.Display

/** Kernel nodes of known software wake gesture drivers; their presence means the kernel emulates gestures. */
internal val SOFTWARE_WAKE_GESTURE_DIRS = listOf("/sys/android_touch", "/sys/android_touch2")

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
)
