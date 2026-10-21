package io.github.rthery.devicechecker.data

import io.github.rthery.devicechecker.checks.CheckResult

/** Run specs that select tests by group rather than by naming them. */
fun isBulkRun(spec: String?): Boolean = spec?.trim().let { it.isNullOrEmpty() || it == "checks" || it == "headless" || it == "all" }

fun testerMarkedIds(ids: Collection<String>, results: Map<String, CheckResult>): Set<String> =
    ids.filter { results[it]?.byTester == true }.toSet()
