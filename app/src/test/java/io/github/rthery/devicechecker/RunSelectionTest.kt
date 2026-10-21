package io.github.rthery.devicechecker

import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.data.isBulkRun
import io.github.rthery.devicechecker.data.testerMarkedIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSelectionTest {
    @Test
    fun groupSpecsAreBulk() {
        listOf(null, "", " ", "checks", "headless", "all", " all ").forEach { assertTrue("$it", isBulkRun(it)) }
    }

    @Test
    fun namedIdsAreNotBulk() {
        assertFalse(isBulkRun("camera.stress"))
        assertFalse(isBulkRun("selinux,kernel.governors"))
    }

    @Test
    fun onlyTesterResultsAreKept() {
        val results = mapOf(
            "a" to CheckResult(CheckStatus.PASS, "marked", byTester = true),
            "b" to CheckResult(CheckStatus.WARN, "auto"),
        )
        assertEquals(setOf("a"), testerMarkedIds(listOf("a", "b", "c"), results))
    }
}
