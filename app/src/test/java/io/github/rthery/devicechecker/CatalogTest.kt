package io.github.rthery.devicechecker

import io.github.rthery.devicechecker.catalog.Rom
import io.github.rthery.devicechecker.catalog.RequirementCatalog
import io.github.rthery.devicechecker.catalog.TestMode
import io.github.rthery.devicechecker.checks.CheckRegistry
import io.github.rthery.devicechecker.interactive.TestRegistry
import io.github.rthery.devicechecker.data.Outcome
import io.github.rthery.devicechecker.data.evaluate
import io.github.rthery.devicechecker.data.TestCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {

    @Test
    fun everyReferencedCheckExists() {
        val missing = RequirementCatalog.all.flatMap { it.checkIds }.filterNot { it in CheckRegistry.byId }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun everyCheckIsReferenced() {
        val referenced = RequirementCatalog.all.flatMap { it.checkIds }.toSet()
        val orphans = CheckRegistry.all.map { it.id }.filterNot { it in referenced }
        assertEquals(emptyList<String>(), orphans)
    }

    @Test
    fun everyReferencedTestExists() {
        val missing = RequirementCatalog.all.flatMap { it.testIds }.filterNot { it in TestRegistry.byId }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun everyTestIsReferenced() {
        val referenced = RequirementCatalog.all.flatMap { it.testIds }.toSet()
        val orphans = TestRegistry.all.map { it.id }.filterNot { it in referenced }
        assertEquals(emptyList<String>(), orphans)
    }

    @Test
    fun checkAndTestIdsDoNotCollide() {
        val shared = CheckRegistry.byId.keys intersect TestRegistry.byId.keys
        assertEquals(emptySet<String>(), shared)
    }

    @Test
    fun romScopedRequirementsAreNotApplicableElsewhere() {
        val updater = RequirementCatalog.byId.getValue("sw.updater")
        assertEquals(Outcome.NOT_APPLICABLE, evaluate(updater, emptyMap(), null, rom = null))
        assertEquals(Outcome.NOT_APPLICABLE, evaluate(updater, emptyMap(), null, rom = Rom.EOS))
        assertEquals(Outcome.UNTESTED, evaluate(updater, emptyMap(), null, rom = Rom.LINEAGE))
        val eosApps = RequirementCatalog.byId.getValue("eos.apps")
        assertEquals(Outcome.NOT_APPLICABLE, evaluate(eosApps, emptyMap(), null, rom = Rom.LINEAGE))
        assertEquals(Outcome.UNTESTED, evaluate(eosApps, emptyMap(), null, rom = Rom.EOS))
    }

    @Test
    fun lineageBasedRequirementsApplyToEveryLineageFamily() {
        val liveDisplay = RequirementCatalog.byId.getValue("sw.livedisplay")
        assertEquals(Outcome.NOT_APPLICABLE, evaluate(liveDisplay, emptyMap(), null, rom = null))
        assertEquals(Outcome.UNTESTED, evaluate(liveDisplay, emptyMap(), null, rom = Rom.LINEAGE))
        assertEquals(Outcome.UNTESTED, evaluate(liveDisplay, emptyMap(), null, rom = Rom.EOS))
    }

    @Test
    fun sharedTestStaysApplicableWhileOneRequirementApplies() {
        val recovery = TestCatalog.builtIn.byId.getValue("recovery.boot")
        assertTrue(recovery.appliesTo(Rom.LINEAGE))
        assertTrue(recovery.appliesTo(Rom.EOS))
        assertFalse(recovery.appliesTo(null))
        assertFalse(TestCatalog.builtIn.byId.getValue("recovery.addons").appliesTo(Rom.EOS))
    }

    @Test
    fun automatedRequirementsHaveChecks() {
        val withoutChecks = RequirementCatalog.all.filter { it.mode == TestMode.AUTOMATED && it.checkIds.isEmpty() }
        assertTrue(withoutChecks.map { it.id }.toString(), withoutChecks.isEmpty())
    }
}
