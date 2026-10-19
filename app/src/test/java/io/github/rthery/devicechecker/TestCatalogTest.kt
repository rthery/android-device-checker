package io.github.rthery.devicechecker

import io.github.rthery.devicechecker.catalog.Rom
import io.github.rthery.devicechecker.catalog.RequirementCatalog
import io.github.rthery.devicechecker.checks.CHECK_HOW
import io.github.rthery.devicechecker.checks.CheckRegistry
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.data.Category
import io.github.rthery.devicechecker.data.Outcome
import io.github.rthery.devicechecker.data.TestCatalog
import io.github.rthery.devicechecker.data.TestKind
import io.github.rthery.devicechecker.data.TestState
import io.github.rthery.devicechecker.data.evaluate
import io.github.rthery.devicechecker.data.stateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestCatalogTest {

    @Test
    fun nfcHasOneVisibleTestAndKeepsLegacyProbeAvailable() {
        val catalog = TestCatalog.builtIn
        assertEquals(listOf("nfc.tag"), catalog.all.filter { it.requirements.any { r -> r.id == "hw.nfc" } }.map { it.id })
        assertTrue("nfc.present" in catalog.checksById)
        assertTrue("nfc.tag" in catalog.interactiveById)
    }

    @Test
    fun successfulTagReadCompletesNfcDespiteInformationalAdapterResult() {
        val catalog = TestCatalog.builtIn
        val results = mapOf(
            "nfc.present" to CheckResult(CheckStatus.INFO, "Adapter enabled"),
            "nfc.tag" to CheckResult(CheckStatus.PASS, "Tag read"),
        )
        assertEquals(TestState.PASS, stateOf(catalog.byId.getValue("nfc.tag"), results, emptyMap(), Rom.LINEAGE, null))
        assertEquals(Outcome.PASS, evaluate(RequirementCatalog.byId.getValue("hw.nfc"), results, null, Rom.LINEAGE))
    }

    @Test
    fun everyCheckSaysWhatItDoes() {
        val missing = CheckRegistry.all.map { it.id }.filter { CHECK_HOW[it].isNullOrEmpty() }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun idsAreUnique() {
        assertEquals(TestCatalog.builtIn.all.size, TestCatalog.builtIn.byId.size)
    }

    @Test
    fun everyRequirementIsCoveredByATest() {
        val covered = TestCatalog.builtIn.all.flatMap { test -> test.requirements.map { it.id } }.toSet()
        val missing = RequirementCatalog.all.map { it.id }.filterNot { it in covered }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun offDeviceTestsLiveInTheirOwnCategory() {
        val misplaced = TestCatalog.builtIn.all.filter { (it.kind == TestKind.OFF_DEVICE) != (it.category == Category.OFF_DEVICE) }
        assertTrue(misplaced.map { it.id }.toString(), misplaced.isEmpty())
    }

    @Test
    fun oneResultUpdatesEveryRequirementItCovers() {
        val test = TestCatalog.builtIn.byId.getValue("camera.stress")
        assertTrue(test.requirements.size > 1)
        val results = mapOf("camera.stress" to CheckResult(CheckStatus.FAIL, "crash"))
        assertEquals(TestState.FAIL, stateOf(test, results, emptyMap(), rom = Rom.LINEAGE, run = null))
        test.requirements.forEach { requirement ->
            assertEquals(requirement.id, Outcome.FAIL, evaluate(requirement, results, null, rom = Rom.LINEAGE))
        }
    }

    @Test
    fun testerMarkConfirmsAnAssistedCheck() {
        val requirement = RequirementCatalog.byId.getValue("sw.kernel.clocks")
        val info = mapOf("kernel.clocks" to CheckResult(CheckStatus.INFO, "compare"))
        assertEquals(Outcome.TO_VERIFY, evaluate(requirement, info, null, rom = Rom.LINEAGE))
        val marked = mapOf("kernel.clocks" to CheckResult(CheckStatus.PASS, "Marked passed by the tester", byTester = true))
        assertEquals(Outcome.PASS, evaluate(requirement, marked, null, rom = Rom.LINEAGE))
    }
}
