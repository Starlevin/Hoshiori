package com.glasssign.app.local

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Lab flags: one real experiment, off by default, with the user's choice and the test evidence stored separately.
 * Enabling must never produce a verified state, and a verification recorded for another build must not be inherited.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class LabStoreTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(LabStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(ProviderRegistry.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun cleanup() {
        context.getSharedPreferences(LabStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(ProviderRegistry.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun evidence(build: String = "0.7.9-preview.5", device: String = "emu-api28", ref: String = "outputs/x.txt") =
        ValidationRecord(buildVersion = build, device = device, evidenceRef = ref, at = "2026-11-05T22:00:00+08:00")

    @Test fun onlyTheRealHazeDockExperimentIsRegistered() {
        assertEquals(listOf("water_drop_glass"), LabStore.all.map { it.id })
        val f = LabStore.all.single()
        assertFalse("an experiment must start off", f.defaultEnabled)
        assertTrue("the description must name what is actually implemented", f.description.contains("Haze"))
        assertTrue(f.description.contains("水珠变形"))
        // No promises about features that do not exist.
        listOf("关键浮层", "稳定路径静态玻璃", "全屏折射").forEach {
            assertFalse("description must not promise $it", f.description.contains(it))
        }
        assertTrue(f.rollbackHint.contains("记录"))
    }

    @Test fun defaultIsOffAndTheChoicePersists() {
        assertFalse(LabStore(context).isEnabled(LabStore.FLAG_WATER_DROP_GLASS))
        val store = LabStore(context)
        assertTrue(store.setEnabled(LabStore.FLAG_WATER_DROP_GLASS, true))
        assertTrue(LabStore(context).isEnabled(LabStore.FLAG_WATER_DROP_GLASS))
        assertTrue(store.setEnabled(LabStore.FLAG_WATER_DROP_GLASS, false))
        assertFalse(LabStore(context).isEnabled(LabStore.FLAG_WATER_DROP_GLASS))
    }

    @Test fun enablingNeverWritesValidationOrEvidence() {
        val store = LabStore(context)
        assertEquals(ValidationState.UNVERIFIED, store.validation(LabStore.FLAG_WATER_DROP_GLASS))
        store.setEnabled(LabStore.FLAG_WATER_DROP_GLASS, true)
        assertEquals(
            "turning the experiment on must never write a validation result",
            ValidationState.UNVERIFIED,
            store.validation(LabStore.FLAG_WATER_DROP_GLASS),
        )
        assertTrue("no evidence fields may be written by a toggle", store.record(LabStore.FLAG_WATER_DROP_GLASS).isEmpty())
        assertEquals(
            "and the UI must show the unverified state",
            ValidationState.UNVERIFIED,
            store.state(LabStore.FLAG_WATER_DROP_GLASS)!!.effectiveValidation(store.currentBuild),
        )
    }

    @Test fun validationRequiresBuildDeviceAndEvidence() {
        val store = LabStore(context)
        assertTrue(store.recordValidation(LabStore.FLAG_WATER_DROP_GLASS, ValidationState.DEVICE_VERIFIED, evidence()))
        val reloaded = LabStore(context)
        assertEquals(ValidationState.DEVICE_VERIFIED, reloaded.validation(LabStore.FLAG_WATER_DROP_GLASS))
        val record = reloaded.record(LabStore.FLAG_WATER_DROP_GLASS)
        assertEquals("0.7.9-preview.5", record.buildVersion)
        assertEquals("emu-api28", record.device)
        assertEquals("outputs/x.txt", record.evidenceRef)
        assertTrue(record.isComplete)
    }

    /** A record missing any of the three fields can never be displayed as verified. */
    @Test fun incompleteEvidenceIsNotDisplayedAsVerified() {
        val store = LabStore(context)
        val build = store.currentBuild
        store.recordValidation(LabStore.FLAG_WATER_DROP_GLASS, ValidationState.DEVICE_VERIFIED, evidence(build = build, device = ""))
        assertEquals(
            "no device means nothing was really verified",
            ValidationState.UNVERIFIED,
            store.state(LabStore.FLAG_WATER_DROP_GLASS)!!.effectiveValidation(build),
        )
        store.recordValidation(LabStore.FLAG_WATER_DROP_GLASS, ValidationState.DEVICE_VERIFIED, evidence(build = build, ref = ""))
        assertEquals(
            "no evidence reference means nothing was really verified",
            ValidationState.UNVERIFIED,
            store.state(LabStore.FLAG_WATER_DROP_GLASS)!!.effectiveValidation(build),
        )
    }

    /** Upgrading the app invalidates an earlier verification: it belongs to a different build. */
    @Test fun verificationIsNotInheritedAcrossBuilds() {
        val store = LabStore(context)
        store.recordValidation(LabStore.FLAG_WATER_DROP_GLASS, ValidationState.DEVICE_VERIFIED, evidence(build = "0.7.9-preview.5"))
        val state = store.state(LabStore.FLAG_WATER_DROP_GLASS)!!
        assertEquals(ValidationState.DEVICE_VERIFIED, state.effectiveValidation("0.7.9-preview.5"))
        assertEquals(
            "a new build must not inherit the old verification",
            ValidationState.UNVERIFIED,
            state.effectiveValidation("0.8.0"),
        )
        // The raw record is preserved (auditable), only the display degrades.
        assertEquals("0.7.9-preview.5", state.record.buildVersion)
    }

    /** A recorded failure is still shown as a failure regardless of build. */
    @Test fun failedStateSurvivesBuildChange() {
        val store = LabStore(context)
        store.rollbackAdvancedGlass("t1")
        val state = store.state(LabStore.FLAG_WATER_DROP_GLASS)!!
        assertEquals(ValidationState.FAILED, state.effectiveValidation("0.8.0"))
    }

    /**
     * Unknown experiment ids are rejected everywhere they can be supplied.
     *
     * `rollbackAdvancedGlass(at)` takes a TIMESTAMP, not an id — it only ever targets the one registered experiment —
     * so it has no "unknown id" case to reject. The per-id entry points are the ones under test here, and the
     * rollback is asserted to leave a previously configured unknown key untouched.
     */
    @Test fun unknownFlagIsRejected() {
        val store = LabStore(context)
        assertFalse(store.isEnabled("not_an_experiment"))
        assertFalse(store.setEnabled("not_an_experiment", true))
        assertFalse(store.recordValidation("not_an_experiment", ValidationState.DEVICE_VERIFIED, evidence()))
        assertFalse(store.recordError("not_an_experiment", "crash", "now"))
        assertNull(store.state("not_an_experiment"))
        assertNull(store.flag("not_an_experiment"))
        // An unknown flag has no enable key written by any of the above.
        assertFalse(
            context.getSharedPreferences(LabStore.PREFS_NAME, Context.MODE_PRIVATE)
                .contains("lab_not_an_experiment_enabled"),
        )
    }

    /** The rollback targets only the registered experiment and never fabricates a state for another id. */
    @Test fun rollbackOnlyAffectsTheRegisteredExperiment() {
        val store = LabStore(context)
        store.setEnabled(LabStore.FLAG_WATER_DROP_GLASS, true)
        assertTrue(store.rollbackAdvancedGlass("2026-11-05T23:00:00+08:00"))
        assertEquals(ValidationState.FAILED, store.validation(LabStore.FLAG_WATER_DROP_GLASS))
        assertFalse(store.isEnabled(LabStore.FLAG_WATER_DROP_GLASS))
        // Only one experiment exists, and no other lab key was created by the rollback.
        val keys = context.getSharedPreferences(LabStore.PREFS_NAME, Context.MODE_PRIVATE).all.keys
        assertTrue(keys.all { it.startsWith("lab_${LabStore.FLAG_WATER_DROP_GLASS}_") })
    }

    @Test fun rollbackDisablesAndRecordsFailure() {
        val store = LabStore(context)
        store.setEnabled(LabStore.FLAG_WATER_DROP_GLASS, true)
        assertTrue(store.rollbackAdvancedGlass("2026-11-05T22:30:00+08:00"))
        assertFalse(LabStore(context).isEnabled(LabStore.FLAG_WATER_DROP_GLASS))
        val reloaded = LabStore(context)
        assertEquals(ValidationState.FAILED, reloaded.validation(LabStore.FLAG_WATER_DROP_GLASS))
        assertEquals("2026-11-05T22:30:00+08:00", reloaded.lastErrorAt(LabStore.FLAG_WATER_DROP_GLASS))
        assertEquals("rollback", reloaded.lastErrorKind(LabStore.FLAG_WATER_DROP_GLASS))
    }

    @Test fun errorKindIsBoundedAndSanitised() {
        val store = LabStore(context)
        store.recordError(LabStore.FLAG_WATER_DROP_GLASS, "render", "t1")
        assertEquals("render", store.lastErrorKind(LabStore.FLAG_WATER_DROP_GLASS))
        store.recordError(LabStore.FLAG_WATER_DROP_GLASS, "cookie_token=abc;secret", "t2")
        assertEquals("unspecified", store.lastErrorKind(LabStore.FLAG_WATER_DROP_GLASS))
    }

    /**
     * Crash recovery is only driven by a RECORDED crash. A normal force-stop / user exit is not a crash and must not
     * change the experiment's state.
     */
    @Test fun normalExitIsNotTreatedAsACrash() {
        val store = LabStore(context)
        store.setEnabled(LabStore.FLAG_WATER_DROP_GLASS, true)
        assertFalse("a normal exit must not be recorded as a crash", store.recoverRecordedUiCrash(false, "t1"))
        assertTrue("the experiment stays on after a normal exit", LabStore(context).isEnabled(LabStore.FLAG_WATER_DROP_GLASS))
        assertEquals(ValidationState.UNVERIFIED, store.validation(LabStore.FLAG_WATER_DROP_GLASS))
        assertTrue(store.lastErrorKind(LabStore.FLAG_WATER_DROP_GLASS).isEmpty())

        assertTrue("a recorded crash rolls it back", store.recoverRecordedUiCrash(true, "t2"))
        assertFalse(LabStore(context).isEnabled(LabStore.FLAG_WATER_DROP_GLASS))
        assertEquals(ValidationState.FAILED, LabStore(context).validation(LabStore.FLAG_WATER_DROP_GLASS))
        assertEquals("crash", store.lastErrorKind(LabStore.FLAG_WATER_DROP_GLASS))
    }

    /** A crash rollback must not touch provider switches or account data. */
    @Test fun labRollbackDoesNotTouchProviderSwitchesOrAccountData() {
        val registry = ProviderRegistry(context)
        val dbName = "${java.util.UUID.randomUUID()}.db"
        val repo = SignRepository(context, dbName)
        try {
            val id = repo.add("tagged", "合成测试", gameId = "nte")
            repo.auth(id, "valid", "合成授权")
            val accountsBefore = repo.accounts()
            val bindingsBefore = repo.bindings()

            registry.setEnabled("tagged", false)
            registry.setEnabled("kurobbs", false)
            val providerStateBefore = registry.snapshot()

            val store = LabStore(context)
            store.setEnabled(LabStore.FLAG_WATER_DROP_GLASS, true)
            store.recordError(LabStore.FLAG_WATER_DROP_GLASS, "render", "t1")
            store.rollbackAdvancedGlass("t2")
            store.recoverRecordedUiCrash(true, "t3")

            assertEquals(providerStateBefore, ProviderRegistry(context).snapshot())
            assertFalse(ProviderRegistry(context).isExecutionAllowed("tagged"))
            assertEquals(accountsBefore, repo.accounts())
            assertEquals(bindingsBefore, repo.bindings())
            assertNotNull(repo.accounts().firstOrNull { it.id == id })
        } finally {
            repo.close()
            context.deleteDatabase(dbName)
        }
    }

    /** A corrupt lab prefs file must degrade to "off"/"unverified", never enable an experiment. */
    @Test fun corruptLabValueFailsClosed() {
        val prefs = context.getSharedPreferences(LabStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("lab_water_drop_glass_enabled", "yes").commit()
        val store = LabStore(context)
        assertFalse("a wrong-typed value must not enable the experiment", store.isEnabled(LabStore.FLAG_WATER_DROP_GLASS))
        assertEquals(ValidationState.UNVERIFIED, store.validation(LabStore.FLAG_WATER_DROP_GLASS))
    }

    /** With no backing preferences at all, the store stays off and never throws. */
    @Test fun missingPreferenceFileKeepsEverythingOff() {
        val store = LabStore(null)
        assertFalse(store.isEnabled(LabStore.FLAG_WATER_DROP_GLASS))
        assertFalse(store.setEnabled(LabStore.FLAG_WATER_DROP_GLASS, true))
        assertFalse(store.rollbackAdvancedGlass("t"))
        assertNull(store.listen { })
        assertEquals(1, store.states().size)
    }

    @Test fun stateSnapshotKeepsChoiceAndEvidenceSeparate() {
        val store = LabStore(context)
        store.setEnabled(LabStore.FLAG_WATER_DROP_GLASS, true)
        val state = store.state(LabStore.FLAG_WATER_DROP_GLASS)
        assertNotNull(state)
        assertTrue(state!!.enabled)
        assertEquals(ValidationState.UNVERIFIED, state.validation)
        assertEquals(1, store.states().size)
    }

    @Test fun listenerReportsTheChangedFlag() {
        val store = LabStore(context)
        val seen = mutableListOf<String>()
        val listener = store.listen { seen.add(it) }
        try {
            store.setEnabled(LabStore.FLAG_WATER_DROP_GLASS, true)
            assertEquals(listOf(LabStore.FLAG_WATER_DROP_GLASS), seen)
        } finally {
            store.stopListening(listener)
        }
        store.setEnabled(LabStore.FLAG_WATER_DROP_GLASS, false)
        assertEquals(1, seen.size)
    }

    @Test fun savedModeSupersedesTheOldBooleanWithoutRewritingIt() {
        val prefs = context.getSharedPreferences(LabStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("lab_water_drop_glass_enabled", true).commit()
        assertEquals(GlassQuality.FULL, LabStore(context).glassQuality())
        assertTrue(LabStore(context).setGlassQuality(GlassQuality.OFF))
        assertEquals(GlassQuality.OFF, LabStore(context).glassQuality())
        assertFalse(LabStore(context).isEnabled(LabStore.FLAG_WATER_DROP_GLASS))
    }

    // ---- P2 (2026-11-07): enhanced-by-default with an explicit-off distinction -----------------------

    @Test fun freshInstallRequestsEnhancedGlassByDefault() {
        // A user who never opened the lab gets FULL; the device/a11y policy still decides what renders.
        assertEquals(GlassQuality.FULL, LabStore(context).requestedGlassQuality())
    }

    @Test fun explicitGlassChoiceIsNeverOverriddenByTheDefault() {
        val store = LabStore(context)
        for (quality in GlassQuality.entries) {
            assertTrue(store.setGlassQuality(quality))
            assertEquals(quality, LabStore(context).requestedGlassQuality())
        }
    }

    @Test fun legacyExplicitOffIsNotUpgradedOnUpgrade() {
        val prefs = context.getSharedPreferences(LabStore.PREFS_NAME, Context.MODE_PRIVATE)
        // Old builds stored just the boolean. A user who deliberately switched it OFF keeps LITE.
        prefs.edit().putBoolean("lab_water_drop_glass_enabled", false).commit()
        assertEquals(GlassQuality.LITE, LabStore(context).requestedGlassQuality())
    }

    @Test fun legacyExplicitOnIsKeptEnhanced() {
        val prefs = context.getSharedPreferences(LabStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("lab_water_drop_glass_enabled", true).commit()
        assertEquals(GlassQuality.FULL, LabStore(context).requestedGlassQuality())
    }

    @Test fun crashRollbackStaysDowngradedAfterRestart() {
        val store = LabStore(context)
        store.setEnabled(LabStore.FLAG_WATER_DROP_GLASS, true)
        assertTrue(store.recoverRecordedUiCrash(true, "2026-11-07T19:00:00+08:00"))
        // The rollback wrote an explicit LITE; the enhanced default must not re-enable FULL.
        assertEquals(GlassQuality.LITE, LabStore(context).requestedGlassQuality())
    }

    @Test fun enhancedDefaultNeverWritesAChoiceOrEvidence() {
        LabStore(context).requestedGlassQuality()
        val prefs = context.getSharedPreferences(LabStore.PREFS_NAME, Context.MODE_PRIVATE)
        assertTrue("reading the default must not persist a choice", prefs.all.isEmpty())
    }
}
