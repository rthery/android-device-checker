package io.github.rthery.devicechecker.pack

import io.github.rthery.devicechecker.catalog.Requirement
import io.github.rthery.devicechecker.profile.DeviceProfile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

sealed class PackParseResult {
    data class Valid(val pack: DevicePack, val sha256: String) : PackParseResult()
    data class Invalid(val errors: List<String>, val id: String? = null) : PackParseResult()
}

object PackParser {

    private val json = Json {
        ignoreUnknownKeys = false
        classDiscriminator = "type"
    }
    private val strictProfileJson = Json { ignoreUnknownKeys = false }

    private val ID = Regex("[a-z0-9][a-z0-9._-]*")
    private val PROP_NAME = Regex("[A-Za-z0-9._-]+")
    private val FILE_PATH = Regex("/[A-Za-z0-9._/,:@+-]+")
    private val FILE_ROOTS = listOf("/sys/", "/proc/", "/system/", "/system_ext/", "/product/", "/vendor/", "/odm/")
    private val COLOR = Regex("#[0-9A-Fa-f]{6}")
    private val FONT_NAME = Regex("[a-z0-9-]+")
    private val INTENT_NAME = Regex("[A-Za-z0-9._$]+")
    private const val MAX_CORNER_SCALE = 3f

    /** [builtIn] is the built-in requirement list, so waivers can be checked against real ids. */
    fun parse(text: String, builtIn: List<Requirement>): PackParseResult {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return PackParseResult.Invalid(listOf("Not a JSON object: ${e.message}"))
        }
        val version = root["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: return PackParseResult.Invalid(listOf("Missing schemaVersion"))
        if (version != PACK_SCHEMA_VERSION) {
            return PackParseResult.Invalid(listOf("schemaVersion $version is not supported (this app reads $PACK_SCHEMA_VERSION)"))
        }
        val pack = try {
            json.decodeFromJsonElement(DevicePack.serializer(), root)
        } catch (e: SerializationException) {
            return PackParseResult.Invalid(listOf(e.message ?: "Invalid pack"))
        } catch (e: IllegalArgumentException) {
            return PackParseResult.Invalid(listOf(e.message ?: "Invalid pack"))
        }
        val errors = validate(pack, builtIn)
        return if (errors.isEmpty()) PackParseResult.Valid(pack, sha256(text)) else PackParseResult.Invalid(errors, pack.id)
    }

    fun validate(pack: DevicePack, builtIn: List<Requirement>): List<String> = buildList {
        if (!ID.matches(pack.id)) add("Pack id \"${pack.id}\" must match ${ID.pattern}")
        if (pack.name.isBlank()) add("name is empty")
        if (pack.rom.isBlank()) add("rom is empty")
        if (pack.devices.isEmpty() || pack.devices.any(String::isBlank)) add("devices must list at least one codename")
        pack.match.forEach { condition -> addAll(validateCondition("match ${condition.prop}", condition.prop, condition.equals, condition.matches)) }

        pack.profile?.let { profile ->
            runCatching { strictProfileJson.decodeFromJsonElement(DeviceProfile.serializer(), profile) }
                .onFailure { add("profile: ${it.message}") }
        }

        val testIds = pack.tests.map { it.id }
        testIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { add("Duplicate test id \"$it\"") }
        pack.tests.forEach { test -> addAll(validateTest(test).map { "test ${test.id}: $it" }) }

        val builtInIds = builtIn.map { it.id }.toSet()
        val replaced = pack.tests.mapNotNull { it.replaces }
        replaced.filterNot { it in builtInIds }.forEach { add("replaces: unknown built-in requirement \"$it\"") }
        replaced.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { add("replaces: \"$it\" is replaced by more than one test") }
        pack.waivers.map { it.requirement }.filter { it in replaced }.forEach { add("waiver $it: a replaced requirement cannot also be waived") }

        val entriesByRequirement = builtIn.associate { it.id to (it.checkIds + it.testIds).toSet() } +
            pack.tests.associate { pack.requirementId(it) to setOf(pack.requirementId(it)) }
        pack.waivers.forEach { waiver ->
            val entries = entriesByRequirement[waiver.requirement]
            when {
                entries == null -> add("waiver: unknown requirement \"${waiver.requirement}\"")
                waiver.reason.isBlank() -> add("waiver ${waiver.requirement}: reason is empty")
                else -> waiver.checks.filterNot { it in entries }.forEach { add("waiver ${waiver.requirement}: \"$it\" is not one of its checks or tests") }
            }
        }

        pack.theme?.let { theme ->
            theme.accentSeed?.let { if (!COLOR.matches(it)) add("theme.accentSeed must be #RRGGBB") }
            theme.cornerScale?.let { if (it < 0f || it > MAX_CORNER_SCALE) add("theme.cornerScale must be between 0 and $MAX_CORNER_SCALE") }
            theme.fontFamily?.let { if (!FONT_NAME.matches(it)) add("theme.fontFamily must be a system font family name") }
            theme.icon?.let { if (it !in LauncherIcon.keys) add("theme.icon must be one of ${LauncherIcon.keys.joinToString()}") }
        }
    }

    private fun validateTest(test: PackTest): List<String> = buildList {
        if (!ID.matches(test.id)) add("id must match ${ID.pattern}")
        if (test.title.isBlank()) add("title is empty")
        when (test) {
            is PropTest -> addAll(validateCondition("prop", test.prop, test.equals, test.matches))
            is FileTest -> {
                val kernel = test.path.startsWith("/sys/") || test.path.startsWith("/proc/")
                if (!FILE_PATH.matches(test.path) || test.path.split('/').any { it == ".." } || FILE_ROOTS.none(test.path::startsWith)) {
                    add("path must be absolute, without .., under ${FILE_ROOTS.joinToString()}")
                } else if (kernel && !CommandAllowlist.isSafeKernelPath(test.path)) {
                    add("path cannot go through /proc/self, /proc/<pid> or root, cwd, exe, fd links")
                }
                if ((test.contains == null) == (test.matches == null)) add("set exactly one of contains or matches")
                test.matches?.let { addAll(regexError(it)) }
            }
            is ShellTest -> {
                CommandAllowlist.check(test.command)?.let { add("command not allowed: $it") }
                addAll(regexError(test.expect))
            }
            is SettingsTest -> {
                addAll(validateIntent(test.intent))
                if (test.question.isBlank()) add("question is empty")
            }
            is GuidedTest -> {
                if (test.steps.isEmpty()) add("steps is empty")
                test.steps.forEachIndexed { index, step ->
                    if (step.text.isBlank()) add("step ${index + 1}: text is empty")
                    step.command?.let { command -> CommandAllowlist.check(command)?.let { add("step ${index + 1}: command not allowed: $it") } }
                    step.shortcut?.let { addAll(validateIntent(it).map { error -> "step ${index + 1}: $error" }) }
                }
            }
        }
    }

    private fun validateCondition(label: String, prop: String, equals: String?, matches: String?): List<String> = buildList {
        if (!PROP_NAME.matches(prop)) add("$label: bad property name")
        if ((equals == null) == (matches == null)) add("$label: set exactly one of equals or matches")
        matches?.let { addAll(regexError(it)) }
    }

    private fun validateIntent(intent: IntentSpec): List<String> = buildList {
        val byComponent = intent.packageName != null || intent.className != null
        when {
            byComponent && (intent.packageName == null || intent.className == null) -> add("intent needs both packageName and className")
            !byComponent && intent.action == null -> add("intent needs an action or a component")
        }
        listOfNotNull(intent.action, intent.packageName, intent.className).forEach {
            if (!INTENT_NAME.matches(it)) add("intent name \"$it\" has unexpected characters")
        }
    }

    private fun regexError(pattern: String): List<String> =
        runCatching { Regex(pattern) }.exceptionOrNull()?.let { listOf("bad regex \"$pattern\": ${it.message}") } ?: emptyList()

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
