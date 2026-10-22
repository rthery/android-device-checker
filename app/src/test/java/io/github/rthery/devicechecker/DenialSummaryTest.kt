package io.github.rthery.devicechecker

import io.github.rthery.devicechecker.checks.summarizeDenials
import org.junit.Assert.assertEquals
import org.junit.Test

class DenialSummaryTest {

    private val log = """
        I auditd  : avc:  denied  { find } for pid=936 uid=0 name=netd scontext=u:r:netmgrd:s0 tcontext=u:object_r:netd_service:s0 tclass=service_manager permissive=0
        I auditd  : avc:  denied  { find } for pid=936 uid=0 name=netd scontext=u:r:netmgrd:s0 tcontext=u:object_r:netd_service:s0 tclass=service_manager permissive=0
        E SELinux : avc:  denied  { find } for pid=5824 uid=10161 name=DockObserver scontext=u:r:untrusted_app:s0:c161,c256,c512,c768 tcontext=u:object_r:DockObserver_service:s0 tclass=service_manager permissive=0
        E SELinux : avc:  denied  { list } for pid=5824 uid=10161 scontext=u:r:untrusted_app_30:s0:c161,c256,c512,c768 tcontext=u:r:hwservicemanager:s0 tclass=hwservice_manager permissive=0
    """.trimIndent()

    @Test
    fun appDomainsAreKeptApartFromSystemDenials() {
        val (system, apps) = summarizeDenials(log)
        assertEquals(listOf("netmgrd -> netd_service:service_manager {find}" to 2), system)
        assertEquals(2, apps.size)
    }
}
