package com.glasssign.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derived page state and the enable switch.
 *
 * What these cases buy, given that a Compose screen cannot be rendered in a JVM test here:
 *  - The INITIAL state is asserted to be "nothing downloaded, nothing installed, switch off", so
 *    "the page does something automatically when opened" cannot be introduced without failing.
 *  - Enabling is asserted to be IMPOSSIBLE without a passing verification of the installed copy, so
 *    a stale screen cannot store `enabled=true` for an app that is gone or re-signed.
 *  - `canOpen` is asserted false whenever verification did not pass, including when it never ran —
 *    "no verdict" must not read as "allowed".
 *  - Disabling is asserted to be unconditional and to leave the other app alone (the only thing it
 *    can touch is one boolean in `plugins_v1`).
 */
class PluginStateTest {

    private val passed = VerifyReport.Passed
    private val signatureFailed = VerifyReport.Failed(
        listOf(VerifyFailure(VerifyField.SIGNATURE, PluginSpec.shortSha(PluginSpec.SIGNING_CERT_SHA256), "aa…bb"))
    )

    // --- default posture ------------------------------------------------------------------------

    @Test
    fun theInitialStateDownloadedAndInstalledNothing() {
        val initial = PluginUiState.INITIAL
        assertEquals(PluginStateName.NOT_DOWNLOADED, initial.state)
        assertFalse(initial.installed)
        assertFalse(initial.verifiedApk)
        assertFalse(initial.enabledStored)
        assertFalse("the switch is off by default", initial.enabledActive)
        assertFalse(initial.canInstall)
        assertFalse(initial.canOpen)
        assertFalse(initial.canCancel)
        assertFalse(initial.canCleanDownload)
        assertEquals("no download may start by itself", PluginState.Download.Idle, initial.download)
    }

    @Test
    fun aFreshPreferenceStoreReportsDisabledAndStartsNothing() {
        val store = MapKeyValueStore()
        val prefs = PluginPrefs(store)
        assertFalse(prefs.enabled())
        // Nothing was written by merely constructing the store/prefs: no "first run" side effect.
        assertTrue("constructing prefs must not write", store.snapshot().isEmpty())
        // reconcile() on a fresh install must not invent an enabled flag either.
        assertFalse(prefs.reconcile(null))
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun enablingIsRefusedWithoutAVerifiedInstall() {
        val store = MapKeyValueStore()
        val prefs = PluginPrefs(store)
        assertFalse("not installed -> no enable", prefs.setEnabled(true, null))
        assertFalse("signature mismatch -> no enable", prefs.setEnabled(true, signatureFailed))
        assertFalse(prefs.enabled())
        assertTrue("a refused enable must not write anything", store.snapshot().isEmpty())
    }

    @Test
    fun aRefusedEnableRequestCannotBeFakedByTheCaller() {
        // The gate lives in PluginPrefs, not in the Compose screen, so a stale/tampered UI state
        // still cannot store the flag.
        val prefs = PluginPrefs(MapKeyValueStore())
        assertEquals(false, prefs.enable(null))
        assertEquals(false, prefs.enable(signatureFailed))
        assertEquals(false, prefs.enabled())
    }

    // --- verify -> pending install ---------------------------------------------------------------

    @Test
    fun aVerifiedArtifactBecomesPendingInstallAndOffersInstall() {
        val state = PluginUiState.derive(
            installed = false,
            installedCheck = null,
            verifiedApk = true,
            download = PluginState.Download.Idle,
            enabledStored = false,
        )
        assertEquals(PluginStateName.VERIFIED_PENDING_INSTALL, state.state)
        assertTrue(state.canInstall)
        assertFalse("only the system installer may take it: nothing to open yet", state.canOpen)
        assertFalse("installing does not enable anything", state.enabledActive)
        assertTrue(state.canCleanDownload)
        assertEquals("已验证待安装", state.title)
    }

    // --- installed + verified -> open/enable ------------------------------------------------------

    @Test
    fun anInstalledVerifiedAppCanBeOpenedAndEnabled() {
        val off = PluginUiState.derive(true, passed, verifiedApk = false, download = PluginState.Download.Idle, enabledStored = false)
        assertEquals(PluginStateName.INSTALLED_NOT_ENABLED, off.state)
        assertTrue(off.canOpen)
        assertTrue(off.canToggleEnabled)
        assertFalse(off.enabledActive)

        val on = PluginUiState.derive(true, passed, verifiedApk = false, download = PluginState.Download.Idle, enabledStored = true)
        assertEquals(PluginStateName.ENABLED, on.state)
        assertTrue(on.enabledActive)
        assertTrue(on.title.contains("已启用"))
    }

    @Test
    fun enablingAndDisablingOnlyFlipsOneStoredValue() {
        val store = MapKeyValueStore()
        val prefs = PluginPrefs(store)
        assertTrue(prefs.setEnabled(true, passed))
        assertEquals(mapOf(PluginSpec.KEY_ENABLED to "true"), store.snapshot())
        assertFalse(prefs.setEnabled(false, passed))
        assertEquals(mapOf(PluginSpec.KEY_ENABLED to "false"), store.snapshot())
    }

    @Test
    fun aSwitchLeftOnIsReconciledAwayWhenVerificationStopsPassing() {
        val store = MapKeyValueStore()
        val prefs = PluginPrefs(store)
        prefs.setEnabled(true, passed)
        assertTrue(prefs.enabled())

        // The user uninstalled (or another installer replaced the app): the stored flag must yield.
        assertTrue("the stale flag is corrected", prefs.reconcile(null))
        assertFalse(prefs.enabled())
        assertEquals(mapOf(PluginSpec.KEY_ENABLED to "false"), store.snapshot())

        // A passing verification afterwards leaves the flag exactly as it is.
        assertTrue(PluginPrefs(MapKeyValueStore(mapOf(PluginSpec.KEY_ENABLED to "true"))).reconcile(passed).not())
    }

    // --- installed but unverifiable -> fail closed ------------------------------------------------

    @Test
    fun anUnverifiedInstallIsNeitherOpenableNorEnableable() {
        // stored=false: switching ON would be enabled, but verification fails, so the request is refused.
        val off = PluginUiState.derive(true, signatureFailed, verifiedApk = false, download = PluginState.Download.Idle, enabledStored = false)
        assertEquals(PluginStateName.INSTALLED_UNVERIFIED, off.state)
        assertFalse("a re-signed app must not be launched", off.canOpen)
        assertFalse("a re-signed app must not be considered enabled", off.enabledActive)
        assertFalse("switching ON must be refused while verification fails", off.canToggleEnabled)
        assertTrue("uninstalling via the system is still offered", off.canUninstallViaSystem)

        // stored=true: a stale ON written before the app was replaced. It must not be honoured, but
        // the switch must stay operable so the user can turn it off instead of being stuck.
        val stale = PluginUiState.derive(true, signatureFailed, verifiedApk = false, download = PluginState.Download.Idle, enabledStored = true)
        assertEquals(PluginStateName.INSTALLED_UNVERIFIED, stale.state)
        assertFalse("a stale ON is not an active enablement", stale.enabledActive)
        assertFalse("nor is it openable", stale.canOpen)
        assertTrue("but it can always be switched off", stale.canToggleEnabled)
    }

    @Test
    fun anInstallWhoseVerificationNeverRanIsNotOpenable() {
        // installed=true but installedCheck=null is the "PackageManager could not tell us anything"
        // case. It must collapse to the same refusal, never to "fine".

        val state = PluginUiState.derive(true, null, verifiedApk = false, download = PluginState.Download.Idle, enabledStored = false)
        assertEquals(PluginStateName.INSTALLED_UNVERIFIED, state.state)
        assertFalse(state.canOpen)
    }

    // --- download lifecycle ----------------------------------------------------------------------

    @Test
    fun progressIsMeasuredFromWrittenBytesNotFromTheHeader() {
        val running = PluginState.Download.Running(bytes = 4096, total = PluginSpec.SIZE_BYTES)
        assertEquals(0, running.percent) // 4096 of 847976 is still 0 %
        assertTrue(running.label.contains("已下载"))
        assertTrue(running.label.contains("KB")) // PluginSpec.readableBytes keeps the real unit

        val half = PluginState.Download.Running(PluginSpec.SIZE_BYTES / 2, PluginSpec.SIZE_BYTES)
        assertEquals(50, half.percent)

        val unknownSize = PluginState.Download.Running(bytes = 1, total = 0)
        assertEquals("no header -> no invented percentage", 0, unknownSize.percent)
        assertTrue(unknownSize.label.contains("正在连接"))
    }

    @Test
    fun aRunningDownloadBlocksEveryOtherActionAndSurvivesTheStatusCard() {
        val running = PluginState.Download.Running(10, PluginSpec.SIZE_BYTES)
        val state = PluginUiState.derive(false, null, verifiedApk = false, download = running, enabledStored = false)
        assertEquals(PluginStateName.DOWNLOADING, state.state)
        assertTrue(state.canCancel)
        assertFalse("no second transfer", state.canDownload)
        assertFalse(state.canInstall)
    }

    @Test
    fun aFailedDownloadOffersRetryAndShowsTheReason() {
        val state = PluginUiState.derive(
            installed = false,
            installedCheck = null,
            verifiedApk = false,
            download = PluginState.Download.Failed(PluginState.FAIL_VERIFY + "SHA-256 摘要不符"),
            enabledStored = false,
        )
        assertEquals(PluginStateName.DOWNLOAD_FAILED, state.state)
        assertTrue(state.canRetry)
        assertTrue(state.canDownload)
        assertFalse(state.canInstall)
        assertTrue("the reason must reach the user", state.subtitle.contains("SHA-256"))
        assertTrue(state.canCleanDownload)
    }

    @Test
    fun aBrokenInstallIsReportedAsInstalledButNotUsable() {
        val state = PluginUiState.derive(true, signatureFailed, verifiedApk = false, download = PluginState.Download.Idle, enabledStored = false)
        assertEquals(PluginStateName.INSTALLED_UNVERIFIED, state.state)
        assertTrue("the app IS installed, the UI must say so", state.installed)
        assertFalse(state.signatureOk)
        assertEquals("已安装但签名或版本不符", state.title)
        assertTrue("the reason must be visible", state.subtitle.contains("验签"))
    }
}
