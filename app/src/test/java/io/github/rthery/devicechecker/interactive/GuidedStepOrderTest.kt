package io.github.rthery.devicechecker.interactive

import io.github.rthery.devicechecker.interactive.StepAnswer.FAIL
import io.github.rthery.devicechecker.interactive.StepAnswer.PASS
import io.github.rthery.devicechecker.interactive.StepAnswer.SKIP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuidedStepOrderTest {
    @Test
    fun `fresh test starts at the first step`() = assertEquals(0, firstStepToAnswer(3, emptyMap()))

    @Test
    fun `resumed test starts at the first unanswered step`() = assertEquals(1, firstStepToAnswer(3, mapOf(0 to PASS, 2 to FAIL)))

    @Test
    fun `answered test with a broken last step opens it`() = assertEquals(2, firstStepToAnswer(3, mapOf(0 to PASS, 1 to SKIP, 2 to FAIL)))

    @Test
    fun `answered test without broken steps opens the first untestable one`() = assertEquals(1, firstStepToAnswer(3, mapOf(0 to PASS, 1 to SKIP, 2 to PASS)))

    @Test
    fun `all working opens the first step`() = assertEquals(0, firstStepToAnswer(2, mapOf(0 to PASS, 1 to PASS)))

    @Test
    fun `fresh run moves to the following step`() = assertEquals(1, nextStepToAnswer(0, 3, mapOf(0 to PASS)))

    @Test
    fun `redo skips steps already working`() = assertEquals(3, nextStepToAnswer(0, 4, mapOf(0 to PASS, 1 to PASS, 2 to PASS, 3 to FAIL)))

    @Test
    fun `redo of the last step finishes`() = assertNull(nextStepToAnswer(2, 3, mapOf(0 to PASS, 1 to PASS, 2 to PASS)))

    @Test
    fun `steps left unanswered before a jump come back`() = assertEquals(1, nextStepToAnswer(2, 3, mapOf(0 to PASS, 2 to PASS)))
}
