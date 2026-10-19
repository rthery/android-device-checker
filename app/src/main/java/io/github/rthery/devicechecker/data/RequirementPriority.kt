package io.github.rthery.devicechecker.data

import io.github.rthery.devicechecker.catalog.Level

/** Use the strongest covered requirement so required tests never appear as optional. */
enum class RequirementPriority(val label: String, val levels: String) {
    ALL("All priorities", ""),
    REQUIRED("Required", "MUST / MUST NOT"),
    RECOMMENDED("Recommended", "SHOULD / SHOULD NOT"),
    OPTIONAL("Optional", "MAY");

    fun accepts(test: UnifiedTest): Boolean = when (this) {
        ALL -> true
        REQUIRED -> test.level == Level.MUST || test.level == Level.MUST_NOT
        RECOMMENDED -> test.level == Level.SHOULD || test.level == Level.SHOULD_NOT
        OPTIONAL -> test.level == Level.MAY
    }
}
