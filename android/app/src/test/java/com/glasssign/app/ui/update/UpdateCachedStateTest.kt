package com.glasssign.app.ui.update

import org.junit.Assert.*
import org.junit.Test

class UpdateCachedStateTest {
    private val body="""[{"tag_name":"v0.8.0","html_url":"https://github.com/Starlevin/Hoshiori/releases/tag/v0.8.0","draft":false,"prerelease":false,"assets":[{"name":"Hoshiori-v0.8.0.apk","size":70000,"browser_download_url":"https://github.com/Starlevin/Hoshiori/releases/download/v0.8.0/Hoshiori-v0.8.0.apk","digest":"sha256:${"a".repeat(64)}"}]}]"""
    private fun vm(cachedVersion:String="0.7.9-preview.3"):UpdateViewModel = UpdateViewModel(
        build=UpdateBuildInfo("0.7.9-preview.3",21,"com.glasssign.app",false),
        configLoader={UpdateChannelConfig.EMPTY},
        service={error("A throttled check must not make a network request")},
        checkStore=MapUpdateCheckStore(UpdateCheckMemory(lastCheckMs=1000,cachedBody=body,
            cachedEndpoint=UpdateReleaseService.endpointFor(UpdateReleaseChannel.PREVIEW),cachedVersion=cachedVersion)),
        clock={2000})

    @Test fun restartingDuringTheCooldownShowsTheCachedAvailableResult() {
        val vm=vm()
        assertFalse(vm.autoCheck())
        assertTrue(vm.state.value.result is UpdateCheck.Available)
        assertTrue(vm.state.value.meta.fromCache)
        assertEquals(1000L,vm.state.value.meta.checkedAt)
        assertFalse(vm.autoCheck())
    }

    @Test fun aCacheFromAnOlderInstalledVersionIsNotReused() {
        val decision=UpdateAutoCheck.decide(UpdateReleaseChannel.PREVIEW,
            UpdateCheckMemory(lastCheckMs=1000,cachedVersion="0.7.8"),2000,currentVersion="0.7.9-preview.3")
        assertTrue(decision is UpdateAutoCheckDecision.Check)
        assertNull((decision as UpdateAutoCheckDecision.Check).etag)
    }

    @Test fun anUpgradeStillHonorsTheShortFailureCooldown() {
        val decision=UpdateAutoCheck.decide(UpdateReleaseChannel.PREVIEW,
            UpdateCheckMemory(lastCheckMs=1000,cachedVersion="0.7.8",lastAttemptFailed=true),
            2000,currentVersion="0.7.9-preview.3")
        assertTrue(decision is UpdateAutoCheckDecision.Throttled)
    }
}
