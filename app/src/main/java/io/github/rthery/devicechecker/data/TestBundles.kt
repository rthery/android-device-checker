package io.github.rthery.devicechecker.data

import androidx.compose.runtime.Immutable

/** Presentation only: every member retains its own executable id, result, waiver and verdict. */
@Immutable
data class TestBundle(val id: String, val title: String, val category: Category, val tests: List<UnifiedTest>)

private data class BundleSpec(val id: String, val title: String, val category: Category, val ids: Set<String>)

private fun bundle(id: String, title: String, category: Category, vararg ids: String) = BundleSpec(id, title, category, ids.toSet())

private val bundles = listOf(
    bundle("build", "Build identity and patches", Category.SECURITY, "build.type", "build.fingerprint", "security.patch"),
    bundle("selinux", "SELinux", Category.SECURITY, "selinux", "selinux.denials"),
    bundle("binder", "Binder", Category.SECURITY, "binder.64bit", "binder.vndbinder"),
    bundle("encryption", "Encryption and keystore", Category.SECURITY, "crypto.status", "keystore.hardware"),
    bundle("verity", "Verified boot", Category.SECURITY, "verity", "sw.verity.vendor"),
    bundle("cpu", "CPU frequencies and governors", Category.KERNEL, "kernel.clocks", "kernel.governors"),
    bundle("cameras", "Cameras and video", Category.CAMERA, "camera.list", "camera.capture", "video.record", "camera.stress", "hw.camera.hal"),
    bundle("codecs", "Hardware codecs", Category.CAMERA, "codecs.hardware", "codecs.roundtrip"),
    bundle("playback", "Audio playback", Category.AUDIO, "audio.devices", "audio.output"),
    bundle("microphones", "Microphones and voice processing", Category.AUDIO, "audio.effects", "audio.input"),
    bundle("calls", "Calls and mobile data", Category.CONNECTIVITY, "telephony", "network.cellular", "call.audio"),
    bundle("wifi", "Wi-Fi", Category.CONNECTIVITY, "wifi.present", "network.wifi", "network.trafficstats", "wifi.mac", "tether.wifi"),
    bundle("bluetooth", "Bluetooth", Category.CONNECTIVITY, "bluetooth.present", "bluetooth.scan", "bluetooth.mac", "bluetooth.codecs", "tether.bluetooth"),
    bundle("gps", "GPS", Category.CONNECTIVITY, "gnss.present", "gnss.fix"),
    bundle("usb", "USB transfer and tethering", Category.CONNECTIVITY, "usb.mtp", "tether.usb"),
    bundle("display", "Display", Category.HARDWARE, "display.resolution", "display.density", "display.hdr", "display.extradim", "livedisplay", "hw.display.external"),
    bundle("fingerprint", "Fingerprint", Category.HARDWARE, "fingerprint.present", "fingerprint.auth"),
    bundle("ir", "IR blaster", Category.HARDWARE, "ir.present", "ir.transmit"),
    bundle("storage", "SD card", Category.HARDWARE, "sd.volumes", "sd.readwrite", "sd.adoptable"),
    bundle("sensors", "Sensors", Category.HARDWARE, "sensors.core", "sensors.list", "sensors.motion"),
    bundle("stability", "Stability and battery", Category.SYSTEM, "crashes.boot", "stability"),
    bundle("updates", "Updates and recovery", Category.UPDATES, "updater.present", "ota.update", "recovery.boot", "recovery.addons"),
    bundle("tree", "Device tree", Category.OFF_DEVICE, "sw.tree.codename", "sw.tree.makefile", "sw.tree.dependencies"),
    bundle("kernel-source", "Kernel source and drivers", Category.OFF_DEVICE, "sw.kernel.prebuilt", "sw.kernel.registers", "sw.kernel.ksm", "sw.kernel.hotplug"),
    bundle("blobs", "Proprietary blobs", Category.OFF_DEVICE, "sw.blobs.extract", "sw.blobs.sources"),
    bundle("vendor", "Vendor image", Category.OFF_DEVICE, "sw.vendor.assert", "sw.vendor.ab", "sw.vendor.modified"),
    bundle("documentation", "Device documentation", Category.OFF_DEVICE, "general.ownership", "general.exceptions", "hw.deviations", "sw.deviations", "qol.wiki"),
    bundle("project", "Project practices and licensing", Category.OFF_DEVICE, "qol.authorship", "qol.copyright", "qol.workflow", "qol.gitlab", "qol.licensing"),
)
private val bundleByTest = bundles.flatMap { spec -> spec.ids.map { it to spec } }.toMap()

fun bundleTests(tests: List<UnifiedTest>): List<TestBundle> = tests.groupBy { bundleByTest[it.id]?.id ?: it.id }.map { (id, members) ->
    val spec = bundleByTest[members.first().id]
    TestBundle("bundle.$id", if (members.size > 1) spec!!.title else members.first().title, spec?.category ?: members.first().category, members)
}

fun bundleState(tests: List<UnifiedTest>, states: Map<String, TestState>): TestState {
    val values = tests.map { states.getValue(it.id) }
    return listOf(TestState.RUNNING, TestState.QUEUED, TestState.FAIL, TestState.WARN, TestState.NEEDS, TestState.NOT_RUN)
        .firstOrNull { it in values }
        ?: if (TestState.PASS in values) TestState.PASS else if (values.all { it == TestState.WAIVED }) TestState.WAIVED else TestState.NA
}

/** Include every state so a collapsed category never hides unfinished or skipped checks. */
fun testSummary(tests: List<UnifiedTest>, states: Map<String, TestState>): String {
    val counts = tests.groupingBy { states.getValue(it.id) }.eachCount()
    val need = tests.count { states.getValue(it.id).needsYou(it.kind) }
    val notRun = tests.count { states.getValue(it.id) == TestState.NOT_RUN && !TestState.NOT_RUN.needsYou(it.kind) }
    return listOfNotNull(
        counts[TestState.RUNNING]?.let { "$it running" }, counts[TestState.QUEUED]?.let { "$it waiting" },
        counts[TestState.FAIL]?.let { "$it failed" }, counts[TestState.WARN]?.let { "$it to check" },
        "$need need you".takeIf { need > 0 }, "$notRun not run".takeIf { notRun > 0 },
        counts[TestState.PASS]?.let { "$it passed" }, counts[TestState.NA]?.let { "$it N/A" },
        counts[TestState.WAIVED]?.let { "$it waived" },
    ).joinToString(" · ")
}
