package com.glasssign.app.plugins

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class PluginContractTest {
    private class Stub(override val descriptor: PluginDescriptor) : CheckinPlugin {
        override fun discoverTasks(account: PluginAccountRef, host: TrustedPluginHost) = emptyList<PluginTask>()
        override fun checkStatus(account: PluginAccountRef, task: PluginTask, host: TrustedPluginHost) =
            PluginOutcome(PluginResultStatus.NO_ACTIVITY, "no campaign")
        override fun claim(account: PluginAccountRef, task: PluginTask, host: TrustedPluginHost) =
            PluginOutcome(PluginResultStatus.FAILED, "not implemented")
    }

    @Test fun candidateIsUtilityNotGame() {
        assertEquals(PluginCategory.UTILITY, PluginCandidates.workbuddyCn.category)
        assertEquals(PluginCategory.UTILITY, PluginCandidates.qoderCn.category)
        assertEquals(PluginAuth.DEVICE_CODE, PluginCandidates.qoderCn.auth)
    }
    @Test fun duplicateIdsRejected() {
        try {
            PluginRegistry(listOf(Stub(PluginCandidates.workbuddyCn), Stub(PluginCandidates.workbuddyCn)))
            fail("duplicate should be rejected")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun unimplementedCandidatesNotAutoInstalled() {
        assertTrue(PluginRegistry(emptyList()).all().isEmpty())
    }
    @Test fun rejectsUntrustedUrl() {
        val d = PluginCandidates.qoderCn
        for (url in listOf("http://openapi.qoder.com.cn/", "https://evil.example/",
            "https://openapi.qoder.com.cn.evil.example/", "https://openapi.qoder.com.cn:8080/",
            "https://u:p@openapi.qoder.com.cn/")) {
            try {
                PluginNetworkPolicy.requireAllowed(d, url)
                fail("should reject $url")
            } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun onlyDeclaredOfficialHttpsHostAllowed() {
        PluginNetworkPolicy.requireAllowed(PluginCandidates.workbuddyCn,
            "https://www.codebuddy.cn/v2/billing/meter/checkin-activity-status")
    }
    @Test fun malformedManifestRejected() {
        try {
            PluginDescriptor("bad", "Bad", 1, PluginCategory.UTILITY,
                PluginAuth.AUTH_IMPORT, setOf("evil.example"))
            fail("invalid stable id")
        } catch (_: IllegalArgumentException) { }
    }
}
