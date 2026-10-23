package io.github.rthery.devicechecker.pack

import io.github.rthery.devicechecker.catalog.Rom
import io.github.rthery.devicechecker.catalog.RequirementCatalog
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.data.Category
import io.github.rthery.devicechecker.data.TestCatalog
import io.github.rthery.devicechecker.data.TestKind
import io.github.rthery.devicechecker.data.TestState
import io.github.rthery.devicechecker.data.stateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PackCatalogTest {

    private val eos: DevicePack = run {
        val dir = listOf(File("../docs/packs"), File("docs/packs")).first { it.isDirectory }
        (PackParser.parse(File(dir, "eos-bullhead.json").readText(), RequirementCatalog.all) as PackParseResult.Valid).pack
    }
    private val catalog = TestCatalog(listOf(eos))

    @Test
    fun packTestsJoinTheUnifiedList() {
        val hardware = catalog.byId.getValue("pack.eos-bullhead.hardware")
        assertEquals(TestKind.AUTO, hardware.kind)
        assertEquals(Category.SECURITY, hardware.category)
        assertEquals(eos.name, hardware.source)
        val led = catalog.byId.getValue("pack.eos-bullhead.led")
        assertEquals(TestKind.MANUAL, led.kind)
        assertEquals(eos.name, led.source)
        assertTrue("pack.eos-bullhead.hardware" in catalog.checksById)
        assertTrue("pack.eos-bullhead.led" in catalog.interactiveById)
        assertEquals(TestCatalog.builtIn.all.size + eos.tests.size, catalog.all.size)
        assertNull(TestCatalog.builtIn.byId["pack.eos-bullhead.led"])
    }

    @Test
    fun replacedRequirementAndItsOwnChecksDisappear() {
        val text = """
            {"schemaVersion": 1, "id": "other", "name": "Other", "rom": "OtherOS", "devices": ["bullhead"], "tests": [
              {"type": "prop", "id": "browser", "title": "Browser", "category": "FEATURES", "level": "MUST", "replaces": "sw.browser", "prop": "ro.x", "equals": "1"}
            ]}
        """.trimIndent()
        val pack = (PackParser.parse(text, RequirementCatalog.all) as PackParseResult.Valid).pack
        val replacing = TestCatalog(listOf(pack))
        assertNull(replacing.requirementsById["sw.browser"])
        assertNull(replacing.byId["browser.jelly"])
        assertNull(replacing.checksById["browser.jelly"])
        assertEquals("sw.browser", replacing.replacedBy("pack.other.browser")?.id)
        assertEquals(listOf("pack.other.browser"), replacing.byId.getValue("pack.other.browser").requirements.map { it.id })
        assertTrue("browser.jelly" in TestCatalog.builtIn.checksById)
    }

    @Test
    fun waiversReachTheTestState() {
        val wakeGestures = catalog.byId.getValue("kernel.wakegestures")
        val waiver = catalog.waiverFor(wakeGestures)
        assertEquals("sw.kernel.wakegestures", waiver?.requirement)
        val results = mapOf("kernel.wakegestures" to CheckResult(CheckStatus.FAIL, "software driver"))
        assertEquals(TestState.WAIVED, stateOf(wakeGestures, results, emptyMap(), rom = Rom.EOS, run = null, waiver = waiver))
        assertNull(catalog.waiverFor(catalog.byId.getValue("network.trafficstats")))
        assertEquals(eos.theme, catalog.theme)
    }
}
