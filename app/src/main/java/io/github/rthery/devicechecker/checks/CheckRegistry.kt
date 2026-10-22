package io.github.rthery.devicechecker.checks

import io.github.rthery.devicechecker.interactive.audioLoopbackCheck

object CheckRegistry {
    val all: List<Check> = softwareChecks + eosChecks + kernelChecks + hardwareChecks + identityChecks + extraChecks + systemHealthChecks + platformChecks + audioLoopbackCheck + codecRoundTripCheck
    val byId: Map<String, Check> = all.associateBy { it.id }

    init {
        check(byId.size == all.size) { "Duplicate check id" }
    }
}
