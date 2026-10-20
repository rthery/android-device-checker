package io.github.rthery.devicechecker.data

import androidx.annotation.StringRes
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.catalog.Level
import io.github.rthery.devicechecker.catalog.Requirement
import io.github.rthery.devicechecker.catalog.RequirementCatalog
import io.github.rthery.devicechecker.catalog.Rom
import io.github.rthery.devicechecker.catalog.Section
import io.github.rthery.devicechecker.catalog.TestMode
import io.github.rthery.devicechecker.checks.CHECK_HOW
import io.github.rthery.devicechecker.checks.Check
import io.github.rthery.devicechecker.checks.CheckRegistry
import io.github.rthery.devicechecker.interactive.InteractiveTest
import io.github.rthery.devicechecker.interactive.TestRegistry
import io.github.rthery.devicechecker.pack.DevicePack
import io.github.rthery.devicechecker.pack.PackContent
import io.github.rthery.devicechecker.pack.ThemeHints
import io.github.rthery.devicechecker.pack.Waiver

enum class TestKind(@StringRes val labelRes: Int) {
    AUTO(R.string.tests_kind_auto),
    GUIDED(R.string.tests_kind_guided),
    MANUAL(R.string.tests_kind_manual),
    OFF_DEVICE(R.string.tests_kind_off_device),
}

enum class Category(val title: String, @StringRes val titleRes: Int) {
    SECURITY("Security and build", R.string.tests_category_security),
    KERNEL("Kernel", R.string.tests_category_kernel),
    CAMERA("Cameras and codecs", R.string.tests_category_camera),
    AUDIO("Audio", R.string.tests_category_audio),
    CONNECTIVITY("Connectivity", R.string.tests_category_connectivity),
    HARDWARE("Display, sensors and storage", R.string.tests_category_hardware),
    SYSTEM("System and stability", R.string.tests_category_system),
    UPDATES("Updates and recovery", R.string.tests_category_updates),
    OFF_DEVICE("Not testable on the phone", R.string.tests_category_off_device),
}

fun Section.category(): Category = when (this) {
    Section.ENCRYPTION, Section.DEVICE_TREE, Section.SECURITY, Section.BLOBS -> Category.SECURITY
    Section.KERNEL -> Category.KERNEL
    Section.CAMERA, Section.CODECS -> Category.CAMERA
    Section.AUDIO -> Category.AUDIO
    Section.RIL, Section.WIFI, Section.USB, Section.GPS, Section.BLUETOOTH, Section.NFC -> Category.CONNECTIVITY
    Section.DISPLAY, Section.FINGERPRINT, Section.IR, Section.SD_CARD, Section.SENSORS, Section.BATTERY, Section.ACCESSORIES -> Category.HARDWARE
    Section.FEATURES, Section.GENERAL, Section.PROJECT -> Category.SYSTEM
    Section.UPDATES, Section.VENDOR -> Category.UPDATES
}

/**
 * One thing the tester can run, follow or mark. A test result is stored once under [id],
 * so every requirement it covers sees the same result.
 */
data class UnifiedTest(
    val id: String,
    val title: String,
    val kind: TestKind,
    val category: Category,
    val requirements: List<Requirement>,
    val how: List<String>,
    /** Name of the device pack that added it; null for built-in tests. */
    val source: String? = null,
) {
    /** The strongest level among the covered requirements (MUST before SHOULD). */
    val level: Level? get() = requirements.minByOrNull { it.level.ordinal }?.level

    val lineageOnly: Boolean get() = requirements.isNotEmpty() && requirements.all { it.lineageOnly }

    /** False when every covered requirement is for another ROM family. */
    fun appliesTo(buildRom: Rom?): Boolean = requirements.isEmpty() || requirements.any { it.appliesTo(buildRom) }

    /** Set when every covered requirement is limited the same way. */
    val scopeLabel: String? get() = requirements.map { it.scopeLabel }.distinct().singleOrNull()

    /** Requirement-only tests store their verdict under the requirement id. */
    val isRequirementOnly: Boolean get() = kind == TestKind.OFF_DEVICE || (kind == TestKind.MANUAL && id in RequirementCatalog.byId)
}

private val REQUIREMENT_TITLES = mapOf(
    "general.ownership" to "Maintainer owns the device",
    "general.exceptions" to "Exceptions documented",
    "hw.camera.hal" to "Every Camera HAL version",
    "hw.display.external" to "External display output",
    "hw.accessories" to "Proprietary accessories",
    "hw.deviations" to "Hardware deviations documented",
    "sw.tree.codename" to "Unique codename",
    "sw.tree.makefile" to "Device tree makefile",
    "sw.tree.dependencies" to "lineage.dependencies",
    "sw.pie" to "No non-PIE binaries",
    "sw.kernel.prebuilt" to "Kernel built from source",
    "sw.kernel.wakegestures" to "No software wake gestures",
    "sw.kernel.registers" to "No extra register tweaks",
    "sw.kernel.ksm" to "No custom KSM drivers",
    "sw.kernel.hotplug" to "Vendor hotplug drivers",
    "sw.verity.vendor" to "Vendor verity",
    "sw.blobs.extract" to "Blob extraction script",
    "sw.blobs.sources" to "Blob sources noted",
    "sw.firmware" to "Firmware version asserts",
    "sw.vendor.assert" to "Vendor version asserts",
    "sw.vendor.ab" to "A/B vendor image",
    "sw.vendor.modified" to "No modified vendor images",
    "sw.vendor.gsi" to "AOSP GSI sanity check",
    "sw.deviations" to "Software deviations documented",
    "qol.authorship" to "Commit authorship",
    "qol.copyright" to "Copyright headers",
    "qol.workflow" to "No force pushes",
    "qol.gitlab" to "Bug tracker triage",
    "qol.licensing" to "Contribution licenses",
    "qol.wiki" to "Wiki page",
)

/** Built-in tests plus those of the enabled device packs, with the packs' waivers. */
class TestCatalog(val packs: List<DevicePack> = emptyList()) {

    private val content = PackContent.from(packs)

    private val builtInRequirements = RequirementCatalog.all.filter { it.id !in content.replaced }

    val requirements: List<Requirement> = builtInRequirements + content.requirements
    val checks: List<Check> = (CheckRegistry.all + content.checks).filter { isStillUsed(it.id) }
    val interactive: List<InteractiveTest> = (TestRegistry.all + content.tests).filter { isStillUsed(it.id) }
    val waivers: Map<String, Waiver> get() = content.waivers

    /** The last enabled pack with theme hints wins. */
    val theme: ThemeHints? = packs.lastOrNull { it.theme != null }?.theme

    val requirementsById: Map<String, Requirement> = requirements.associateBy { it.id }
    val checksById: Map<String, Check> = checks.associateBy { it.id }
    val interactiveById: Map<String, InteractiveTest> = interactive.associateBy { it.id }

    val all: List<UnifiedTest> = build()
    val byId: Map<String, UnifiedTest> = all.associateBy { it.id }

    /** The waiver covering this test, when every requirement it covers waives it. */
    fun waiverFor(test: UnifiedTest): Waiver? {
        val applying = test.requirements.map { requirement ->
            content.waivers[requirement.id]?.takeIf { it.checks.isEmpty() || test.id in it.checks } ?: return null
        }
        return applying.firstOrNull()
    }

    fun waivedBy(requirementId: String): String? = content.waivedBy[requirementId]

    /** The built-in requirement a pack requirement stands in for. */
    fun replacedBy(packRequirementId: String): Requirement? =
        content.replaced.entries.firstOrNull { it.value == packRequirementId }?.let { RequirementCatalog.byId[it.key] }

    /** False for a built-in check or test whose every requirement was replaced by a pack. */
    private fun isStillUsed(id: String): Boolean {
        val covering = RequirementCatalog.all.filter { id in it.checkIds || id in it.testIds }
        return covering.isEmpty() || covering.any { it.id !in content.replaced }
    }

    private fun build(): List<UnifiedTest> {
        fun categoryOf(covered: List<Requirement>) = covered.firstOrNull()?.section?.category() ?: Category.SYSTEM

        // NFC's guided test already checks the adapter before enabling tag reading.
        // Keep the old probe available to adb and reports, without a duplicate UI entry.
        val checkTests = checks.filterNot { it.id == "nfc.present" && "nfc.tag" in interactiveById }.map { check ->
            val covered = requirements.filter { check.id in it.checkIds }
            val how = CHECK_HOW[check.id] ?: content.how[check.id].orEmpty()
            UnifiedTest(check.id, check.title, TestKind.AUTO, categoryOf(covered), covered, how, covered.firstNotNullOfOrNull { it.source })
        }
        val tests = interactive.map { test ->
            val covered = requirements.filter { test.id in it.testIds }
            val kind = if (test.manual) TestKind.MANUAL else TestKind.GUIDED
            UnifiedTest(test.id, test.title, kind, categoryOf(covered), covered, content.how[test.id] ?: listOf(test.summary), test.source)
        }
        val requirementOnly = builtInRequirements.filter { it.checkIds.isEmpty() && it.testIds.isEmpty() }.map { requirement ->
            val offDevice = requirement.mode == TestMode.OUT_OF_SCOPE
            UnifiedTest(
                id = requirement.id,
                title = titleFor(requirement),
                kind = if (offDevice) TestKind.OFF_DEVICE else TestKind.MANUAL,
                category = if (offDevice) Category.OFF_DEVICE else requirement.section.category(),
                requirements = listOf(requirement),
                how = if (offDevice) listOf("Nothing on the phone can check this. Record your verdict after checking the source tree or the project.")
                else listOf("No in-app test yet. Check it yourself and record your verdict."),
            )
        }
        return checkTests + tests + requirementOnly
    }

    companion object {
        val builtIn: TestCatalog by lazy { TestCatalog() }

        fun titleFor(requirement: Requirement): String = REQUIREMENT_TITLES[requirement.id] ?: requirement.text
    }
}
