package io.github.rthery.devicechecker.checks

object CheckRegistry {
    val all: List<Check> = softwareChecks + kernelChecks + hardwareChecks + identityChecks + extraChecks + systemHealthChecks + codecRoundTripCheck
    val byId: Map<String, Check> = all.associateBy { it.id }

    init {
        check(byId.size == all.size) { "Duplicate check id" }
    }
}
