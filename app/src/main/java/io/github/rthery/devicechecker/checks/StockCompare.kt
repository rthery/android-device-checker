package io.github.rthery.devicechecker.checks

/**
 * Compares what this build exposes with an imported stock capture.
 * Anything stock had that the build lacks fails; extras are only listed.
 */
fun CheckContext.compareWithStock(what: String, stockItems: List<String>?, buildItems: List<String>, details: String, access: Access = Access.APP): CheckResult? {
    if (stock == null || stockItems.isNullOrEmpty()) return null
    val missing = stockItems.toSet() - buildItems.toSet()
    val extra = buildItems.toSet() - stockItems.toSet()
    val diff = buildString {
        if (missing.isNotEmpty()) appendLine("Missing compared with stock:\n" + missing.sorted().joinToString("\n") { "  $it" })
        if (extra.isNotEmpty()) appendLine("Not in stock:\n" + extra.sorted().joinToString("\n") { "  $it" })
        append("Stock capture: ${stock.device}, Android ${stock.android}")
    }
    val full = "$diff\n\n$details".trim()
    return if (missing.isEmpty()) pass("$what match stock${if (extra.isNotEmpty()) " (${extra.size} extra)" else ""}", full, access)
    else fail("${missing.size} $what missing compared with stock", full, access)
}
