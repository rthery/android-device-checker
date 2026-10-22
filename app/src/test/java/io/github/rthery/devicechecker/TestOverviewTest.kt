package io.github.rthery.devicechecker

import io.github.rthery.devicechecker.catalog.Level
import io.github.rthery.devicechecker.data.Category
import io.github.rthery.devicechecker.data.TestCatalog
import io.github.rthery.devicechecker.data.TestKind
import io.github.rthery.devicechecker.data.TestState
import io.github.rthery.devicechecker.data.UnifiedTest
import io.github.rthery.devicechecker.data.guidedQueue
import io.github.rthery.devicechecker.data.isTask
import io.github.rthery.devicechecker.data.isToCheck
import io.github.rthery.devicechecker.data.overviewOf
import io.github.rthery.devicechecker.data.romVersionOf
import io.github.rthery.devicechecker.data.shortRomVersion
import io.github.rthery.devicechecker.ui.parseStepLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestOverviewTest {
    private val example = TestCatalog.builtIn.all.first { it.requirements.isNotEmpty() }
    private fun test(id: String, kind: TestKind, category: Category = Category.KERNEL, level: Level = Level.MUST) =
        example.copy(id = id, kind = kind, category = category, requirements = listOf(example.requirements.first().copy(level = level)))

    @Test
    fun automaticResultsAreNeverTasksAndHandsOnWarningsAreNotToCheck() {
        assertFalse(TestState.NOT_RUN.isTask(TestKind.AUTO))
        assertFalse(TestState.NEEDS.isTask(TestKind.AUTO))
        assertTrue(TestState.NEEDS.isToCheck(TestKind.AUTO))
        assertTrue(TestState.WARN.isTask(TestKind.GUIDED))
        assertFalse(TestState.WARN.isToCheck(TestKind.MANUAL))
        assertTrue(TestState.NOT_RUN.isTask(TestKind.OFF_DEVICE))
        assertFalse(TestState.PASS.isTask(TestKind.MANUAL))
    }

    @Test
    fun overviewCountsEachTestInOneTileOnly() {
        val tests = listOf(
            test("a", TestKind.AUTO), test("b", TestKind.AUTO), test("c", TestKind.AUTO),
            test("d", TestKind.GUIDED), test("e", TestKind.MANUAL), test("f", TestKind.OFF_DEVICE),
        )
        val states = mapOf("a" to TestState.FAIL, "b" to TestState.WARN, "c" to TestState.PASS, "d" to TestState.WARN, "e" to TestState.NOT_RUN, "f" to TestState.WAIVED)
        val overview = overviewOf(tests, states)
        assertEquals(1, overview.failed)
        assertEquals(1, overview.toCheck)
        assertEquals(2, overview.needYou)
        assertEquals(1, overview.passed)
        assertEquals(3, overview.done)
        assertEquals(6, overview.total)
    }

    @Test
    fun guidedQueueFollowsCategoriesThenStrongestRequirement() {
        val tests = listOf(
            test("audio-should", TestKind.GUIDED, Category.AUDIO, Level.SHOULD),
            test("security", TestKind.MANUAL, Category.SECURITY),
            test("audio-must", TestKind.GUIDED, Category.AUDIO, Level.MUST),
            test("done", TestKind.GUIDED, Category.SECURITY),
            test("auto", TestKind.AUTO, Category.SECURITY),
        )
        val states = tests.associate { it.id to if (it.id == "done") TestState.PASS else TestState.NOT_RUN }
        assertEquals(listOf("security", "audio-must", "audio-should"), guidedQueue(tests, states).map(UnifiedTest::id))
    }

    @Test
    fun romVersionKeepsTheReleaseAndBuildDate() {
        assertEquals("4.4.1 (20261021)", shortRomVersion("4.4.1-t-20261021-UNOFFICIAL-bullhead"))
        assertEquals("20.0 (20261021)", shortRomVersion("20.0-20261021-UNOFFICIAL-bullhead"))
        assertEquals("custom", shortRomVersion("custom"))
        assertEquals("4.4.1 (20261022)", romVersionOf("/e/OS (4.4.1-t-20261022-UNOFFICIAL-bullhead)"))
        assertEquals(null, romVersionOf("/e/OS"))
    }

    @Test
    fun stepSummaryLinesBecomeAnswersAndOutputIsIgnored() {
        val details = "Works: Switch USB to file transfer.\n  mtp\nBroken: The computer shows the phone storage.\nCan't test: Repeat on every other USB port.\nNot answered yet: Copy a file."
        val lines = parseStepLines(details)
        assertEquals(listOf(TestState.PASS, TestState.FAIL, TestState.NA, TestState.NOT_RUN), lines.map { it.answer })
        assertEquals("Switch USB to file transfer.", lines.first().text)
        assertTrue(parseStepLines("free-form camera output").isEmpty())
    }
}
