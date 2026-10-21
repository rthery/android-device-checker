package io.github.rthery.devicechecker.data

import io.github.rthery.devicechecker.BuildConfig
import io.github.rthery.devicechecker.catalog.RequirementCatalog
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.profile.DeviceProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val OUTCOME_LEGEND = mapOf(
    Outcome.PASS to "Requirement met (automated check passed, interactive test passed, or tester verdict).",
    Outcome.FAIL to "Requirement not met.",
    Outcome.WARN to "A check needs a human look (missing stock value, partial result, nothing to test with).",
    Outcome.NEEDS_SHELL to "The app uid could not read the data. Run again with Shizuku connected.",
    Outcome.TO_VERIFY to "Data gathered, but a human must confirm (run the interactive test or set a verdict).",
    Outcome.UNTESTED to "Nothing has run for this requirement yet.",
    Outcome.NOT_APPLICABLE to "Does not apply (stock lacks the feature, a LineageOS-only item on another build, or tester said so).",
    Outcome.WAIVED to "Granted exception from an enabled device pack.",
    Outcome.NOT_ON_DEVICE to "About the source tree, wiki or maintainer. Cannot be checked on the device.",
)

@Serializable
data class ReportEntry(
    val id: String,
    val kind: String,
    val title: String,
    val status: String?,
    val summary: String?,
    val details: String?,
    val access: String?,
    val timestamp: String?,
    /** Paths relative to the reports folder, for adb pull. */
    val artifacts: List<String> = emptyList(),
)

@Serializable
data class ReportRequirement(
    val id: String,
    val section: String,
    val level: String,
    val mode: String,
    val text: String,
    val outcome: String,
    val lineageOnly: Boolean = false,
    /** ROM family the requirement is limited to. */
    val rom: String? = null,
    val note: String? = null,
    val verdict: String? = null,
    val verdictNote: String? = null,
    /** Device pack that added this requirement. */
    val source: String? = null,
    /** Built-in requirement this pack requirement stands in for. */
    val replaces: String? = null,
    val waiver: String? = null,
    val waivedChecks: List<String> = emptyList(),
    val waivedBy: String? = null,
    val entries: List<ReportEntry>,
)

@Serializable
data class ReportPack(val id: String, val name: String, val rom: String, val romVersion: String? = null)

@Serializable
data class JsonReport(
    val generatedAt: String,
    val app: String,
    val checklistSource: String,
    val checklistLicense: String,
    val legend: Map<String, String>,
    val device: Map<String, String>,
    val profile: DeviceProfile,
    val packs: List<ReportPack> = emptyList(),
    val summary: Map<String, Int>,
    /** What is left to do, with the adb command for each when there is one. */
    val nextSteps: List<NextStep> = emptyList(),
    val requirements: List<ReportRequirement>,
)

private val reportJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

private fun formatTime(millis: Long): String? =
    millis.takeIf { it > 0 }?.let { DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }

fun buildJsonReport(
    deviceInfo: DeviceInfo,
    profile: DeviceProfile,
    results: Map<String, CheckResult>,
    verdicts: Map<String, ManualVerdict>,
    catalog: TestCatalog = TestCatalog.builtIn,
    shizukuReady: Boolean = false,
    hasStock: Boolean = false,
): JsonReport {
    val requirements = catalog.requirements.map { requirement ->
        val waiver = catalog.waivers[requirement.id]
        val verdict = verdicts[requirement.id]
        fun entry(id: String, kind: String, title: String): ReportEntry {
            val result = results[id]
            return ReportEntry(
                id, kind, title, result?.status?.name, result?.summary, result?.details?.ifBlank { null },
                result?.access?.name, result?.timestamp?.let(::formatTime), result?.artifacts.orEmpty(),
            )
        }
        ReportRequirement(
            id = requirement.id,
            section = requirement.section.title,
            level = requirement.level.label,
            mode = requirement.mode.name,
            text = requirement.text,
            outcome = evaluate(requirement, results, verdict, deviceInfo.rom, waiver).name,
            lineageOnly = requirement.lineageOnly,
            rom = requirement.rom?.label,
            note = requirement.note,
            verdict = verdict?.verdict?.name,
            verdictNote = verdict?.note?.ifBlank { null },
            source = requirement.source,
            replaces = catalog.replacedBy(requirement.id)?.let { "${it.id}: ${it.text}" },
            waiver = waiver?.reason,
            waivedChecks = waiver?.checks.orEmpty(),
            waivedBy = waiver?.let { catalog.waivedBy(requirement.id) },
            entries = requirement.checkIds.map { entry(it, "check", catalog.checksById[it]?.title ?: it) } +
                requirement.testIds.map { entry(it, "test", catalog.interactiveById[it]?.title ?: it) },
        )
    }
    return JsonReport(
        generatedAt = formatTime(System.currentTimeMillis()).orEmpty(),
        app = "Android Device Checker ${BuildConfig.VERSION_NAME}",
        checklistSource = "${RequirementCatalog.SOURCE_TITLE} (${RequirementCatalog.SOURCE_URL}), paraphrased and adapted",
        checklistLicense = RequirementCatalog.SOURCE_LICENSE,
        legend = OUTCOME_LEGEND.mapKeys { it.key.name },
        device = deviceInfo.entries.toMap(),
        profile = profile,
        packs = catalog.packs.map { ReportPack(it.id, it.name, it.rom, it.romVersion) },
        summary = requirements.groupingBy { it.outcome }.eachCount(),
        nextSteps = nextSteps(
            catalog.all,
            catalog.all.associate { it.id to stateOf(it, results, verdicts, deviceInfo.rom, null, catalog.waiverFor(it)) },
            results, profile, shizukuReady, hasStock,
        ),
        requirements = requirements,
    )
}

fun JsonReport.toJson(): String = reportJson.encodeToString(this)

fun JsonReport.toMarkdown(): String = buildString {
    appendLine("# Device requirements report")
    appendLine()
    appendLine("Generated $generatedAt by $app.")
    appendLine("Checklist: $checklistSource, $checklistLicense.")
    appendLine()
    appendLine("## Device")
    appendLine()
    device.forEach { (key, value) -> appendLine("- $key: $value") }
    if (packs.isNotEmpty()) {
        appendLine()
        appendLine("Device packs: ${packs.joinToString { "${it.name} (`${it.id}`, ${it.rom}${it.romVersion?.let { version -> " $version" }.orEmpty()})" }}")
    }
    appendLine()
    if (nextSteps.isNotEmpty()) {
        appendLine("## Next steps")
        appendLine()
        nextSteps.forEach { step ->
            appendLine("- ${if (step.needsHuman) "[human] " else ""}${step.title} (`${step.id}`): ${step.why}")
            step.command?.let { appendLine("  `$it`") }
        }
        appendLine()
    }
    appendLine("## Summary")
    appendLine()
    summary.entries.sortedBy { Outcome.valueOf(it.key).ordinal }.forEach { (outcome, count) -> appendLine("- $outcome: $count") }
    appendLine()
    appendLine("Legend:")
    legend.forEach { (outcome, meaning) -> appendLine("- $outcome: $meaning") }

    requirements.groupBy { it.section }.forEach { (section, items) ->
        appendLine()
        appendLine("## $section")
        items.forEach { item ->
            appendLine()
            appendLine("### [${item.outcome}] ${item.level}: ${item.text}")
            appendLine()
            appendLine("id `${item.id}`, mode ${item.mode}${item.rom?.let { ", $it builds only" } ?: if (item.lineageOnly) ", LineageOS-based builds only" else ""}")
            item.source?.let { appendLine("From $it") }
            item.replaces?.let { appendLine("Replaces $it") }
            item.waiver?.let {
                val scope = if (item.waivedChecks.isEmpty()) "" else " for ${item.waivedChecks.joinToString()}"
                appendLine("Waived$scope by ${item.waivedBy ?: "a device pack"}: $it")
            }
            item.verdict?.let { appendLine("Tester verdict: $it${item.verdictNote?.let { note -> " ($note)" }.orEmpty()}") }
            item.entries.forEach { entry ->
                appendLine("- ${entry.kind} `${entry.id}` ${entry.title}: ${entry.status ?: "NOT RUN"}${entry.summary?.let { " - $it" }.orEmpty()}")
                entry.details?.let { details ->
                    appendLine("  ```")
                    details.lines().forEach { appendLine("  $it") }
                    appendLine("  ```")
                }
                if (entry.artifacts.isNotEmpty()) appendLine("  Artifacts: ${entry.artifacts.joinToString { "`$it`" }}")
            }
        }
    }
    appendLine()
    appendLine("## Stock profile")
    appendLine()
    appendLine("```json")
    appendLine(reportJson.encodeToString(profile))
    appendLine("```")
}
