package io.github.rthery.devicechecker

import io.github.rthery.devicechecker.catalog.Level
import io.github.rthery.devicechecker.data.RequirementPriority
import io.github.rthery.devicechecker.data.TestCatalog
import io.github.rthery.devicechecker.data.TestKind
import io.github.rthery.devicechecker.data.bundleTests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequirementPriorityTest {
    private val example = TestCatalog.builtIn.all.first { it.requirements.isNotEmpty() }
    private fun test(vararg levels: Level) = example.copy(requirements = levels.map { example.requirements.first().copy(level = it) })

    @Test
    fun requiredIncludesPositiveAndNegativeRequirements() {
        assertTrue(RequirementPriority.REQUIRED.accepts(test(Level.MUST)))
        assertTrue(RequirementPriority.REQUIRED.accepts(test(Level.MUST_NOT)))
        assertFalse(RequirementPriority.REQUIRED.accepts(test(Level.SHOULD)))
        assertFalse(RequirementPriority.REQUIRED.accepts(test(Level.MAY)))
    }

    @Test
    fun recommendationsAndOptionalRequirementsHaveSeparateFilters() {
        assertTrue(RequirementPriority.RECOMMENDED.accepts(test(Level.SHOULD)))
        assertTrue(RequirementPriority.RECOMMENDED.accepts(test(Level.SHOULD_NOT)))
        assertFalse(RequirementPriority.RECOMMENDED.accepts(test(Level.MAY)))
        assertTrue(RequirementPriority.OPTIONAL.accepts(test(Level.MAY)))
        assertFalse(RequirementPriority.OPTIONAL.accepts(test(Level.SHOULD_NOT)))
    }

    @Test
    fun sharedCheckUsesItsStrongestRequirement() {
        val mixed = test(Level.SHOULD, Level.MUST_NOT, Level.MAY)
        assertTrue(RequirementPriority.REQUIRED.accepts(mixed))
        assertFalse(RequirementPriority.RECOMMENDED.accepts(mixed))
        assertFalse(RequirementPriority.OPTIONAL.accepts(mixed))
        assertTrue(RequirementPriority.ALL.accepts(test()))
    }

    @Test
    fun filteredBundleMembersAndRunTargetsRespectPriority() {
        val selected = TestCatalog.builtIn.all.filter(RequirementPriority.RECOMMENDED::accepts)
        val groups = bundleTests(selected)
        val members = groups.flatMap { it.tests }
        assertEquals(selected.map { it.id }.toSet(), members.map { it.id }.toSet())
        assertTrue(members.all(RequirementPriority.RECOMMENDED::accepts))
        assertTrue(members.none { it.level == Level.MUST || it.level == Level.MUST_NOT })
        val automatic = selected.filter { it.kind == TestKind.AUTO }
        assertTrue(automatic.isNotEmpty())
        assertTrue(automatic.all(RequirementPriority.RECOMMENDED::accepts))
    }
}
