package io.github.rthery.devicechecker.pack

import io.github.rthery.devicechecker.catalog.Level
import io.github.rthery.devicechecker.catalog.Section
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

const val PACK_SCHEMA_VERSION = 1

/** A declarative file a ROM maintainer ships so the app adapts to their device and ROM. No code runs from it. */
@Serializable
data class DevicePack(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val rom: String,
    val romVersion: String? = null,
    val maintainer: String? = null,
    /** Values of ro.product.device this pack is for. */
    val devices: List<String>,
    /** Extra conditions, all of which must hold, to tell apart ROMs on the same device. */
    val match: List<PropCondition> = emptyList(),
    /** Partial DeviceProfile JSON, merged into the stock profile when the pack is enabled. */
    val profile: JsonObject? = null,
    val tests: List<PackTest> = emptyList(),
    val waivers: List<Waiver> = emptyList(),
    val theme: ThemeHints? = null,
)

@Serializable
data class PropCondition(val prop: String, val equals: String? = null, val matches: String? = null)

@Serializable
sealed class PackTest {
    abstract val id: String
    abstract val title: String
    abstract val category: Section
    abstract val level: Level
    abstract val description: String?

    /** A built-in requirement id this test stands in for; the built-in one and its own checks are hidden. */
    abstract val replaces: String?
}

@Serializable
@SerialName("prop")
data class PropTest(
    override val id: String,
    override val title: String,
    override val category: Section,
    override val level: Level,
    override val description: String? = null,
    override val replaces: String? = null,
    val prop: String,
    val equals: String? = null,
    val matches: String? = null,
) : PackTest()

@Serializable
@SerialName("file")
data class FileTest(
    override val id: String,
    override val title: String,
    override val category: Section,
    override val level: Level,
    override val description: String? = null,
    override val replaces: String? = null,
    val path: String,
    val contains: String? = null,
    val matches: String? = null,
) : PackTest()

@Serializable
@SerialName("shell")
data class ShellTest(
    override val id: String,
    override val title: String,
    override val category: Section,
    override val level: Level,
    override val description: String? = null,
    override val replaces: String? = null,
    val command: String,
    /** Regex searched in the output; a match passes. */
    val expect: String,
) : PackTest()

@Serializable
@SerialName("settings")
data class SettingsTest(
    override val id: String,
    override val title: String,
    override val category: Section,
    override val level: Level,
    override val description: String? = null,
    override val replaces: String? = null,
    val intent: IntentSpec,
    val question: String,
) : PackTest()

@Serializable
@SerialName("guided")
data class GuidedTest(
    override val id: String,
    override val title: String,
    override val category: Section,
    override val level: Level,
    override val description: String? = null,
    override val replaces: String? = null,
    val intro: String,
    val steps: List<PackStep>,
) : PackTest()

/** Opens an activity by action or by component. No data or extras, so it can only show a screen. */
@Serializable
data class IntentSpec(
    val label: String? = null,
    val action: String? = null,
    val packageName: String? = null,
    val className: String? = null,
)

@Serializable
data class PackStep(val text: String, val shortcut: IntentSpec? = null, val command: String? = null)

/** A granted exception. With [checks], only those results are ignored; without, the whole requirement is waived. */
@Serializable
data class Waiver(
    val requirement: String,
    val reason: String,
    val checks: List<String> = emptyList(),
    val reference: String? = null,
)

@Serializable
data class ThemeHints(
    /** #RRGGBB */
    val accentSeed: String? = null,
    val cornerScale: Float? = null,
    /** A system font family name, such as sans-serif-condensed. */
    val fontFamily: String? = null,
    /** One of the launcher icons bundled in the app: aosp, lineage or eos. */
    val icon: String? = null,
)

fun packRequirementId(packId: String, testId: String) = "pack.$packId.$testId"

fun DevicePack.requirementId(test: PackTest) = packRequirementId(id, test.id)

fun DevicePack.isFor(device: String, prop: (String) -> String): Boolean =
    device in devices && match.all { it.holds(prop(it.prop)) }

fun PropCondition.holds(value: String): Boolean = when {
    equals != null -> value == equals
    matches != null -> Regex(matches).containsMatchIn(value)
    else -> false
}

/** Everything the pack can make the app run or open, shown to the user before enabling it. */
fun DevicePack.approvalItems(): List<String> = buildList {
    tests.forEach { test ->
        test.replaces?.let { add("Replaces requirement $it with: ${test.title}") }
        when (test) {
            is ShellTest -> add("Shizuku: ${test.command}")
            is FileTest -> add("Read: ${test.path}")
            is SettingsTest -> add("Open: ${test.intent.describe()}")
            is GuidedTest -> test.steps.forEach { step ->
                step.command?.let { add("Shizuku: $it") }
                step.shortcut?.let { add("Open: ${it.describe()}") }
            }
            is PropTest -> Unit
        }
    }
}.distinct()

fun IntentSpec.describe(): String = when {
    packageName != null && className != null -> "$packageName/$className"
    else -> action.orEmpty()
}
