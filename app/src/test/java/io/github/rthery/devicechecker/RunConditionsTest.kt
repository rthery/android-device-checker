package io.github.rthery.devicechecker

import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.checks.RunConditions
import io.github.rthery.devicechecker.data.DeviceInfo
import io.github.rthery.devicechecker.data.buildJsonReport
import io.github.rthery.devicechecker.data.toMarkdown
import io.github.rthery.devicechecker.profile.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunConditionsTest {
    @Test
    fun labelsRootAndShell() {
        assertEquals("Shizuku uid 0 (root), adbd root", RunConditions(0, true).label)
        assertEquals("Shizuku uid 2000, adbd not root", RunConditions(2000, false).label)
        assertEquals("", RunConditions().label)
    }

    @Test
    fun reportCarriesBuildTypeAndPerResultConditions() {
        val results = mapOf(
            "selinux" to CheckResult(CheckStatus.PASS, "Enforcing", conditions = RunConditions(0, true)),
            "su.absent" to CheckResult(CheckStatus.PASS, "3.10"),
        )
        val report = buildJsonReport(DeviceInfo(emptyList()), DeviceProfile(), results, emptyMap(), buildType = "userdebug", now = RunConditions(2000, false))
        val markdown = report.toMarkdown()
        assertTrue(markdown.contains("- Build type: userdebug"))
        assertTrue(markdown.contains("- Root state now: Shizuku uid 2000, adbd not root"))
        assertTrue(markdown.contains("Ran through Shizuku uid 0 (root), adbd root"))
        val entries = report.requirements.flatMap { it.entries }
        assertEquals(RunConditions(0, true), entries.first { it.id == "selinux" }.conditions)
        assertFalse(entries.filter { it.id == "su.absent" }.any { it.conditions != null })
    }
}
