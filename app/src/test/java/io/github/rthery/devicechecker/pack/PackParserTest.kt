package io.github.rthery.devicechecker.pack

import io.github.rthery.devicechecker.catalog.Level
import io.github.rthery.devicechecker.catalog.RequirementCatalog
import io.github.rthery.devicechecker.catalog.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class PackParserTest {

    private fun parse(text: String) = PackParser.parse(text, RequirementCatalog.all)

    private fun valid(text: String): DevicePack = when (val result = parse(text)) {
        is PackParseResult.Valid -> result.pack
        is PackParseResult.Invalid -> fail("Expected valid, got ${result.errors}") as Nothing
    }

    private fun errors(text: String): List<String> = when (val result = parse(text)) {
        is PackParseResult.Valid -> fail("Expected invalid") as Nothing
        is PackParseResult.Invalid -> result.errors
    }

    private fun pack(tests: String = "[]", extra: String = "") = """
        {"schemaVersion": 1, "id": "test-pack", "name": "Test", "rom": "TestOS", "devices": ["bullhead"], "tests": $tests $extra}
    """.trimIndent()

    @Test
    fun parsesEveryTestType() {
        val parsed = valid(
            pack(
                """[
                {"type": "prop", "id": "p", "title": "P", "category": "KERNEL", "level": "MUST", "prop": "ro.x", "equals": "1"},
                {"type": "file", "id": "f", "title": "F", "category": "SENSORS", "level": "SHOULD", "path": "/proc/version", "matches": "3\\.10"},
                {"type": "shell", "id": "s", "title": "S", "category": "FEATURES", "level": "MUST", "command": "dumpsys battery", "expect": "level"},
                {"type": "settings", "id": "i", "title": "I", "category": "DISPLAY", "level": "SHOULD", "intent": {"action": "android.settings.DISPLAY_SETTINGS"}, "question": "Q"},
                {"type": "guided", "id": "g", "title": "G", "category": "AUDIO", "level": "MUST", "intro": "Do it", "steps": [{"text": "a", "command": "getprop ro.x"}]}
                ]""",
            ),
        )
        assertEquals(listOf("p", "f", "s", "i", "g"), parsed.tests.map { it.id })
        assertTrue(parsed.tests[0] is PropTest)
        assertEquals(Section.SENSORS, parsed.tests[1].category)
        assertEquals(Level.SHOULD, parsed.tests[3].level)
        assertEquals("pack.test-pack.g", parsed.requirementId(parsed.tests[4]))
        assertEquals(listOf("Read: /proc/version", "Shizuku: dumpsys battery", "Open: android.settings.DISPLAY_SETTINGS", "Shizuku: getprop ro.x"), parsed.approvalItems())
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        val result = errors("""{"schemaVersion": 2, "id": "x"}""")
        assertTrue(result.single().contains("schemaVersion 2"))
        assertTrue(errors("""{"id": "x"}""").single().contains("Missing schemaVersion"))
        assertTrue(errors("not json").single().startsWith("Not a JSON object"))
    }

    @Test
    fun rejectsUnknownKeysAndTypes() {
        errors(pack(extra = """, "colour": "red""""))
        errors(pack("""[{"type": "script", "id": "x", "title": "X", "category": "KERNEL", "level": "MUST"}]"""))
        errors(pack("""[{"type": "prop", "id": "x", "title": "X", "category": "NOPE", "level": "MUST", "prop": "a", "equals": "b"}]"""))
    }

    @Test
    fun rejectsShellCommandsOutsideTheAllowlist() {
        val result = errors(pack("""[{"type": "shell", "id": "x", "title": "X", "category": "KERNEL", "level": "MUST", "command": "settings put global foo 1", "expect": "."}]"""))
        assertTrue(result.toString(), result.single().startsWith("test x: command not allowed"))
        val step = errors(pack("""[{"type": "guided", "id": "g", "title": "G", "category": "KERNEL", "level": "MUST", "intro": "i", "steps": [{"text": "t", "command": "reboot"}]}]"""))
        assertTrue(step.toString(), step.single().contains("step 1: command not allowed"))
    }

    @Test
    fun validatesFields() {
        assertTrue(errors(pack("""[{"type": "prop", "id": "x", "title": "X", "category": "KERNEL", "level": "MUST", "prop": "a"}]""")).single().contains("exactly one of equals or matches"))
        assertTrue(errors(pack("""[{"type": "file", "id": "x", "title": "X", "category": "KERNEL", "level": "MUST", "path": "/data/x", "contains": "a"}]""")).single().contains("path must be"))
        assertTrue(errors(pack("""[{"type": "file", "id": "x", "title": "X", "category": "KERNEL", "level": "MUST", "path": "/sys/../data", "contains": "a"}]""")).single().contains("path must be"))
        assertTrue(errors(pack("""[{"type": "shell", "id": "x", "title": "X", "category": "KERNEL", "level": "MUST", "command": "getprop", "expect": "("}]""")).single().contains("bad regex"))
        assertTrue(errors(pack("""[{"type": "settings", "id": "x", "title": "X", "category": "KERNEL", "level": "MUST", "intent": {"packageName": "a"}, "question": "q"}]""")).single().contains("packageName and className"))
        val duplicate = """{"type": "prop", "id": "x", "title": "X", "category": "KERNEL", "level": "MUST", "prop": "a", "equals": "b"}"""
        assertTrue(errors(pack("[$duplicate, $duplicate]")).single().contains("Duplicate test id"))
        assertTrue(errors(pack().replace("test-pack", "Bad Id")).single().contains("Pack id"))
        assertTrue(errors(pack().replace("[\"bullhead\"]", "[]")).single().contains("devices"))
    }

    @Test
    fun fileTestPathsCannotInjectOrEscape() {
        fun fileTest(path: String) = pack("""[{"type": "file", "id": "x", "title": "X", "category": "KERNEL", "level": "MUST", "path": "$path", "contains": "a"}]""")
        valid(fileTest("/proc/version"))
        valid(fileTest("/system/etc/gps_debug.conf"))
        listOf(
            "/proc/version; rm -rf /sdcard",
            "/sys/x \$(reboot)",
            "/sys/x `reboot`",
            "/proc/version /data/x",
            "/proc/self/root/data/x",
            "/proc/1/cwd/x",
            "/sys/class/*/x",
        ).forEach { path -> assertTrue(path, errors(fileTest(path)).single().contains("path")) }
    }

    @Test
    fun validatesProfileAgainstDeviceProfile() {
        valid(pack(extra = """, "profile": {"stockWidthPx": 1080, "stockHasNfc": true}"""))
        assertTrue(errors(pack(extra = """, "profile": {"stockWidth": 1080}""")).single().startsWith("profile:"))
        assertTrue(errors(pack(extra = """, "profile": {"stockWidthPx": "wide"}""")).single().startsWith("profile:"))
    }

    @Test
    fun validatesWaivers() {
        valid(pack(extra = """, "waivers": [{"requirement": "sw.browser", "reason": "Own browser"}]"""))
        valid(pack(extra = """, "waivers": [{"requirement": "hw.wifi.works", "checks": ["network.trafficstats"], "reason": "No eBPF"}]"""))
        assertTrue(errors(pack(extra = """, "waivers": [{"requirement": "hw.nope", "reason": "x"}]""")).single().contains("unknown requirement"))
        assertTrue(errors(pack(extra = """, "waivers": [{"requirement": "sw.browser", "reason": " "}]""")).single().contains("reason is empty"))
        assertTrue(errors(pack(extra = """, "waivers": [{"requirement": "hw.wifi.works", "checks": ["selinux"], "reason": "x"}]""")).single().contains("not one of its checks"))
        val ownTest = """[{"type": "prop", "id": "x", "title": "X", "category": "KERNEL", "level": "MUST", "prop": "a", "equals": "b"}]"""
        valid(pack(ownTest, """, "waivers": [{"requirement": "pack.test-pack.x", "reason": "Known"}]"""))
    }

    @Test
    fun validatesReplaces() {
        fun replacing(vararg ids: String, extra: String = "") = pack(ids.mapIndexed { index, id ->
            """{"type": "prop", "id": "t$index", "title": "T", "category": "FEATURES", "level": "MUST", "prop": "a", "equals": "b", "replaces": "$id"}"""
        }.joinToString(prefix = "[", postfix = "]"), extra)
        val parsed = valid(replacing("sw.browser"))
        assertTrue(parsed.approvalItems().first().startsWith("Replaces requirement sw.browser"))
        assertTrue(errors(replacing("hw.nope")).single().contains("unknown built-in requirement"))
        assertTrue(errors(replacing("sw.browser", "sw.browser")).single().contains("more than one test"))
        val waived = replacing("sw.browser", extra = """, "waivers": [{"requirement": "sw.browser", "reason": "r"}]""")
        assertTrue(errors(waived).single().contains("cannot also be waived"))
    }

    @Test
    fun validatesTheme() {
        valid(pack(extra = """, "theme": {"accentSeed": "#0086FF", "cornerScale": 1.5, "fontFamily": "sans-serif-condensed"}"""))
        assertTrue(errors(pack(extra = """, "theme": {"accentSeed": "blue"}""")).single().contains("accentSeed"))
        assertTrue(errors(pack(extra = """, "theme": {"cornerScale": 9}""")).single().contains("cornerScale"))
        assertTrue(errors(pack(extra = """, "theme": {"fontFamily": "../fonts/x.ttf"}""")).single().contains("fontFamily"))
        valid(pack(extra = """, "theme": {"icon": "eos"}"""))
        assertTrue(errors(pack(extra = """, "theme": {"icon": "https://example.com/icon.png"}""")).single().contains("theme.icon"))
    }

    @Test
    fun matchesDeviceAndProps() {
        val parsed = valid(pack(extra = """, "match": [{"prop": "ro.elegal.url", "matches": "e\\.foundation"}, {"prop": "ro.lineage.version", "equals": ""}]"""))
        val eos = mapOf("ro.elegal.url" to "https://e.foundation/legal")
        assertTrue(parsed.isFor("bullhead") { eos[it].orEmpty() })
        assertFalse(parsed.isFor("angler") { eos[it].orEmpty() })
        assertFalse(parsed.isFor("bullhead") { "" })
    }

    @Test
    fun hashChangesWithContent() {
        val first = parse(pack()) as PackParseResult.Valid
        val second = parse(pack().replace("TestOS", "TestOS 2")) as PackParseResult.Valid
        assertEquals(64, first.sha256.length)
        assertFalse(first.sha256 == second.sha256)
    }

    @Test
    fun samplePacksInDocsAreValid() {
        val dir = listOf(File("../docs/packs"), File("docs/packs")).first { it.isDirectory }
        val files = dir.listFiles { file -> file.name.endsWith(".json") }.orEmpty()
        assertTrue(files.size >= 2)
        files.forEach { file ->
            val result = parse(file.readText())
            assertTrue("${file.name}: $result", result is PackParseResult.Valid)
        }
    }
}
