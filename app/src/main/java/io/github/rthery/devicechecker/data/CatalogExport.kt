package io.github.rthery.devicechecker.data

import io.github.rthery.devicechecker.BuildConfig
import io.github.rthery.devicechecker.interactive.HeadlessRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val PACKAGE = BuildConfig.APPLICATION_ID
private const val RECEIVER = "adb shell am broadcast -n $PACKAGE/.AdbCommandReceiver -a $PACKAGE"
private const val ACTIVITY = "adb shell am start -n $PACKAGE/.AdbLinkActivity"

/** Headless tests that still need a person or an agent to judge the files they leave. */
private val NEEDS_JUDGEMENT = setOf("camera.capture", "video.record", "audio.input")

@Serializable
data class CatalogRequirement(val id: String, val level: String, val text: String)

@Serializable
data class CatalogTest(
    val id: String,
    val title: String,
    val kind: String,
    val category: String,
    val level: String?,
    val lineageOnly: Boolean,
    val source: String?,
    /** True when the app can run it with no one at the screen. */
    val headless: Boolean,
    /** True when a person (or an agent looking at artifacts) must give the verdict. */
    val needsHuman: Boolean,
    val how: List<String>,
    val requirements: List<CatalogRequirement>,
    val run: String?,
    val open: String?,
    val setResult: String,
)

@Serializable
data class Catalog(
    val app: String,
    val packageName: String,
    val reportsDir: String,
    val files: Map<String, String>,
    val commands: Map<String, String>,
    val verdicts: List<String>,
    val tests: List<CatalogTest>,
)

private val catalogJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

fun buildCatalog(tests: List<UnifiedTest>, reportsDir: String): Catalog {
    val headless = HeadlessRegistry.byId.keys
    return Catalog(
        app = "Android Device Checker ${BuildConfig.VERSION_NAME}",
        packageName = PACKAGE,
        reportsDir = reportsDir,
        files = mapOf(
            "report.json" to "Every requirement with its outcome and the result of each test that covers it",
            "report.md" to "The same report as Markdown",
            "catalog.json" to "This file: every test and how to drive it over adb",
            "status.json" to "running, done or stopped, with the test in progress",
            "history/" to "One report snapshot per run",
            "artifacts/<test id>/" to "Photos, clips and recordings to review",
            "stock.json" to "Stock capture written by CAPTURE_STOCK",
        ),
        commands = mapOf(
            "runChecks" to "$RECEIVER.RUN_CHECKS [--es ids <id,id>]",
            "runInForeground" to "$ACTIVITY --es run <checks|headless|all|id,id> [--ei iterations N] [--ei gnss_timeout S]",
            "openTest" to "$ACTIVITY --es test <id>",
            "openTab" to "$ACTIVITY --es tab <tests|report|device>",
            "setResult" to "adb shell \"am broadcast -n $PACKAGE/.AdbCommandReceiver -a $PACKAGE.SET_RESULT --es test <id> --es verdict <pass|fail|not_applicable|untested> --es note '<text>'\"",
            "setProfile" to "adb shell \"am broadcast -n $PACKAGE/.AdbCommandReceiver -a $PACKAGE.SET_PROFILE --es json '<partial profile json>'\"",
            "captureStock" to "$RECEIVER.CAPTURE_STOCK",
            "importStock" to "adb push stock.json $reportsDir/../import/stock.json && $RECEIVER.IMPORT_STOCK",
            "importPack" to "adb push pack.json $reportsDir/../import/pack.json && $RECEIVER.IMPORT_PACK",
            "export" to "$RECEIVER.EXPORT",
            "pull" to "adb pull $reportsDir",
            "grantPermissions" to "adb shell pm grant $PACKAGE android.permission.CAMERA (also RECORD_AUDIO, ACCESS_FINE_LOCATION, BLUETOOTH_SCAN, BLUETOOTH_CONNECT); with Shizuku connected the app grants them itself",
        ),
        verdicts = Verdict.entries.map { it.name.lowercase() },
        tests = tests.map { test ->
            val isHeadless = test.kind == TestKind.AUTO || test.id in headless
            CatalogTest(
                id = test.id,
                title = test.title,
                kind = test.kind.name,
                category = test.category.title,
                level = test.level?.label,
                lineageOnly = test.lineageOnly,
                source = test.source,
                headless = isHeadless,
                needsHuman = !isHeadless || test.id in NEEDS_JUDGEMENT,
                how = test.how,
                requirements = test.requirements.map { CatalogRequirement(it.id, it.level.label, it.text) },
                run = when {
                    test.kind == TestKind.AUTO -> "$RECEIVER.RUN_CHECKS --es ids ${test.id}"
                    test.id in headless -> "$ACTIVITY --es run ${test.id}"
                    else -> null
                },
                open = if (test.kind == TestKind.GUIDED || (test.kind == TestKind.MANUAL && !test.isRequirementOnly)) "$ACTIVITY --es test ${test.id}" else null,
                setResult = "adb shell \"am broadcast -n $PACKAGE/.AdbCommandReceiver -a $PACKAGE.SET_RESULT --es test ${test.id} --es verdict <pass|fail|not_applicable> --es note '<text>'\"",
            )
        },
    )
}

fun Catalog.toJson(): String = catalogJson.encodeToString(this)
