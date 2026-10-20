package io.github.rthery.devicechecker

import io.github.rthery.devicechecker.checks.isMicrog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrogDetectionTest {

    /** Permissions declared by GmsCore 0.3.17.252432 as shipped in /e/OS 4.4.1, labelled "App Compatibility Services". */
    private val eosGmsCore = mapOf(
        "org.microg.gms.EXTENDED_ACCESS" to "com.google.android.gms",
        "org.microg.gms.PROVISION" to "com.google.android.gms",
        "org.microg.gms.STATUS_BROADCAST" to "com.google.android.gms",
    )

    @Test
    fun microgIsFoundWhateverItsLabel() {
        assertTrue(isMicrog(eosGmsCore::get))
    }

    @Test
    fun googlePlayServicesIsNotMicrog() {
        assertFalse(isMicrog { null })
    }

    @Test
    fun microgPermissionFromAnotherPackageDoesNotCount() {
        assertFalse(isMicrog { "org.example.fake" })
    }
}
