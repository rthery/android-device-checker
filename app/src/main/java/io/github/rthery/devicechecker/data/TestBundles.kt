package io.github.rthery.devicechecker.data

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.ui.UiText

/** Presentation only: every member retains its own executable id, result, waiver and verdict. */
@Immutable
data class TestBundle(val id: String, val title: UiText, val category: Category, val tests: List<UnifiedTest>)

private data class BundleSpec(val id: String, @StringRes val title: Int, val category: Category, val ids: Set<String>)

private fun bundle(id: String, @StringRes title: Int, category: Category, vararg ids: String) = BundleSpec(id, title, category, ids.toSet())

private val bundles = listOf(
    bundle("build", R.string.tests_bundle_build, Category.SECURITY, "build.type", "build.fingerprint", "security.patch"),
    bundle("selinux", R.string.tests_bundle_selinux, Category.SECURITY, "selinux", "selinux.denials"),
    bundle("binder", R.string.tests_bundle_binder, Category.SECURITY, "binder.64bit", "binder.vndbinder"),
    bundle("encryption", R.string.tests_bundle_encryption, Category.SECURITY, "crypto.status", "keystore.hardware"),
    bundle("verity", R.string.tests_bundle_verity, Category.SECURITY, "verity", "sw.verity.vendor", "eos.verifiedboot"),
    bundle("eos-defaults", R.string.tests_bundle_eos_defaults, Category.SECURITY, "eos.dns", "integrity.basic"),
    bundle("cpu", R.string.tests_bundle_cpu, Category.KERNEL, "kernel.clocks", "kernel.governors"),
    bundle("cameras", R.string.tests_bundle_cameras, Category.CAMERA, "camera.list", "camera.capture", "video.record", "camera.stress", "hw.camera.hal"),
    bundle("codecs", R.string.tests_bundle_codecs, Category.CAMERA, "codecs.hardware", "codecs.roundtrip"),
    bundle("playback", R.string.tests_bundle_playback, Category.AUDIO, "audio.devices", "audio.output"),
    bundle("microphones", R.string.tests_bundle_microphones, Category.AUDIO, "audio.effects", "audio.input"),
    bundle("calls", R.string.tests_bundle_calls, Category.CONNECTIVITY, "telephony", "network.cellular", "call.audio", "messaging", "ims.calls", "sim.multi"),
    bundle("wifi", R.string.tests_bundle_wifi, Category.CONNECTIVITY, "wifi.present", "network.wifi", "network.trafficstats", "wifi.mac", "tether.wifi"),
    bundle("bluetooth", R.string.tests_bundle_bluetooth, Category.CONNECTIVITY, "bluetooth.present", "bluetooth.scan", "bluetooth.mac", "bluetooth.codecs", "tether.bluetooth"),
    bundle("gps", R.string.tests_bundle_gps, Category.CONNECTIVITY, "gnss.present", "gnss.fix"),
    bundle("usb", R.string.tests_bundle_usb, Category.CONNECTIVITY, "usb.mtp", "tether.usb", "usb.otg"),
    bundle("display", R.string.tests_bundle_display, Category.HARDWARE, "display.resolution", "display.density", "display.hdr", "display.extradim", "livedisplay", "hw.display.external", "touch.multi"),
    bundle("fingerprint", R.string.tests_bundle_fingerprint, Category.HARDWARE, "fingerprint.present", "fingerprint.auth"),
    bundle("ir", R.string.tests_bundle_ir, Category.HARDWARE, "ir.present", "ir.transmit"),
    bundle("storage", R.string.tests_bundle_storage, Category.HARDWARE, "sd.volumes", "sd.readwrite", "sd.adoptable"),
    bundle("sensors", R.string.tests_bundle_sensors, Category.HARDWARE, "sensors.core", "sensors.list", "sensors.motion"),
    bundle("battery", R.string.tests_bundle_battery, Category.HARDWARE, "battery.capacity", "charging.offmode", "charging.cables"),
    bundle("eos-apps", R.string.tests_bundle_eos_apps, Category.SYSTEM, "eos.apps", "eos.browser", "eos.microg", "microg.selfcheck", "privacy.dashboard"),
    bundle("stability", R.string.tests_bundle_stability, Category.SYSTEM, "crashes.boot", "stability"),
    bundle("updates", R.string.tests_bundle_updates, Category.UPDATES, "updater.present", "ota.update", "recovery.boot", "recovery.addons"),
    bundle("tree", R.string.tests_bundle_tree, Category.OFF_DEVICE, "sw.tree.codename", "sw.tree.makefile", "sw.tree.dependencies"),
    bundle("kernel-source", R.string.tests_bundle_kernel_source, Category.OFF_DEVICE, "sw.kernel.prebuilt", "sw.kernel.registers", "sw.kernel.ksm", "sw.kernel.hotplug"),
    bundle("blobs", R.string.tests_bundle_blobs, Category.OFF_DEVICE, "sw.blobs.extract", "sw.blobs.sources"),
    bundle("vendor", R.string.tests_bundle_vendor, Category.OFF_DEVICE, "sw.vendor.assert", "sw.vendor.ab", "sw.vendor.modified"),
    bundle("documentation", R.string.tests_bundle_documentation, Category.OFF_DEVICE, "general.ownership", "general.exceptions", "hw.deviations", "sw.deviations", "qol.wiki"),
    bundle("project", R.string.tests_bundle_project, Category.OFF_DEVICE, "qol.authorship", "qol.copyright", "qol.workflow", "qol.gitlab", "qol.licensing"),
)
private val bundleByTest = bundles.flatMap { spec -> spec.ids.map { it to spec } }.toMap()

fun bundleTests(tests: List<UnifiedTest>): List<TestBundle> = tests.groupBy { bundleByTest[it.id]?.id ?: it.id }.map { (id, members) ->
    val spec = bundleByTest[members.first().id]
    TestBundle("bundle.$id", if (members.size > 1) UiText.Res(spec!!.title) else UiText.Raw(members.first().title), spec?.category ?: members.first().category, members)
}

fun bundleState(tests: List<UnifiedTest>, states: Map<String, TestState>): TestState {
    val values = tests.map { states.getValue(it.id) }
    return listOf(TestState.RUNNING, TestState.QUEUED, TestState.FAIL, TestState.WARN, TestState.NEEDS, TestState.NOT_RUN)
        .firstOrNull { it in values }
        ?: if (TestState.PASS in values) TestState.PASS else if (values.all { it == TestState.WAIVED }) TestState.WAIVED else TestState.NA
}

enum class SummaryPart(@PluralsRes val res: Int) {
    RUNNING(R.plurals.tests_summary_running), WAITING(R.plurals.tests_summary_waiting),
    FAILED(R.plurals.tests_summary_failed), TO_CHECK(R.plurals.tests_summary_to_check),
    NEED_YOU(R.plurals.tests_summary_need_you), NOT_RUN(R.plurals.tests_summary_not_run),
    PASSED(R.plurals.tests_summary_passed), NA(R.plurals.tests_summary_na),
    WAIVED(R.plurals.tests_summary_waived),
}

/** Include every state so a collapsed category never hides unfinished or skipped checks. */
fun testSummary(tests: List<UnifiedTest>, states: Map<String, TestState>): List<Pair<SummaryPart, Int>> {
    val counts = tests.groupingBy { states.getValue(it.id) }.eachCount()
    val need = tests.count { states.getValue(it.id).needsYou(it.kind) }
    val notRun = tests.count { states.getValue(it.id) == TestState.NOT_RUN && !TestState.NOT_RUN.needsYou(it.kind) }
    return listOfNotNull(
        counts[TestState.RUNNING]?.let { SummaryPart.RUNNING to it }, counts[TestState.QUEUED]?.let { SummaryPart.WAITING to it },
        counts[TestState.FAIL]?.let { SummaryPart.FAILED to it }, counts[TestState.WARN]?.let { SummaryPart.TO_CHECK to it },
        (SummaryPart.NEED_YOU to need).takeIf { need > 0 }, (SummaryPart.NOT_RUN to notRun).takeIf { notRun > 0 },
        counts[TestState.PASS]?.let { SummaryPart.PASSED to it }, counts[TestState.NA]?.let { SummaryPart.NA to it },
        counts[TestState.WAIVED]?.let { SummaryPart.WAIVED to it },
    )
}
