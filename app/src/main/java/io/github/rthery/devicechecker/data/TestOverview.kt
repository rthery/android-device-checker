package io.github.rthery.devicechecker.data

/** A hands-on test the tester still has to do or redo. Automatic results never count. */
fun TestState.isTask(kind: TestKind): Boolean =
    kind != TestKind.AUTO && (this == TestState.NEEDS || this == TestState.NOT_RUN || this == TestState.WARN)

/** An automatic result a person should look at; hands-on warnings are tasks instead. */
fun TestState.isToCheck(kind: TestKind): Boolean =
    kind == TestKind.AUTO && (this == TestState.WARN || this == TestState.NEEDS)

fun TestState.isDone(): Boolean = this == TestState.PASS || this == TestState.NA || this == TestState.WAIVED

/** [done] counts every finished test, failures included, for the progress line. */
data class TestOverview(val failed: Int, val toCheck: Int, val needYou: Int, val passed: Int, val done: Int, val total: Int)

fun overviewOf(tests: List<UnifiedTest>, states: Map<String, TestState>): TestOverview {
    fun count(predicate: (UnifiedTest, TestState) -> Boolean) = tests.count { predicate(it, states.getValue(it.id)) }
    return TestOverview(
        failed = count { _, state -> state == TestState.FAIL },
        toCheck = count { test, state -> state.isToCheck(test.kind) },
        needYou = count { test, state -> state.isTask(test.kind) },
        passed = count { _, state -> state == TestState.PASS },
        done = count { _, state -> state == TestState.FAIL || state.isDone() },
        total = tests.size,
    )
}

/** Hands-on tests in list order: by category, then the strongest requirement first. */
fun guidedQueue(tests: List<UnifiedTest>, states: Map<String, TestState>): List<UnifiedTest> =
    tests.filter { states.getValue(it.id).isTask(it.kind) }
        .sortedWith(compareBy<UnifiedTest>({ it.category.ordinal }, { it.level?.ordinal ?: Int.MAX_VALUE }))

private val VERSION_DATE = Regex("""^(\d+(?:\.\d+)*)\b.*?-(\d{8})-""")

/** The version in a "ROM family" report value such as "/e/OS (4.4.1-t-20261021-UNOFFICIAL-bullhead)", shortened. */
fun romVersionOf(romFamily: String): String? = Regex("""\((.+)\)$""").find(romFamily)?.groupValues?.get(1)?.let(::shortRomVersion)

/** "4.4.1-t-20261021-UNOFFICIAL-bullhead" reads as "4.4.1 (20261021)". */
fun shortRomVersion(version: String): String =
    VERSION_DATE.find(version)?.let { "${it.groupValues[1]} (${it.groupValues[2]})" } ?: version
