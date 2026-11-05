package com.glasssign.app.ui

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Launcher icon choice: storage, the follow/forced mapping and the alias switch.
 *
 * Uses an isolated Robolectric application and a fake [LauncherComponentSwitcher]; it never reads the real
 * user data and never touches a device launcher.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class LauncherIconThemesTest {
    private lateinit var context: Context
    private lateinit var store: LauncherIconStore

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(LauncherIconStore.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        store = LauncherIconStore.open(context)
    }

    // ---------------------------------------------------------------- storage

    @Test
    fun freshInstallFollowsTheAppTheme() {
        assertEquals(LauncherIconMode.FOLLOW, store.read())
    }

    @Test
    fun storedChoicesSurviveAReread() {
        for (mode in LauncherIconMode.entries) {
            store.save(mode)
            assertEquals(mode, LauncherIconStore.open(context).read())
        }
    }

    @Test
    fun unknownStoredTextFallsBackToFollow() {
        for (bad in listOf("", "DARK", "always-dark", "1", "follow ", "light\n")) {
            store.preferences.edit().putString(LauncherIconStore.KEY, bad).commit()
            assertEquals("stored \"$bad\"", LauncherIconMode.FOLLOW, store.read())
        }
        // A non-string value (e.g. an old build that stored a boolean) must not throw either.
        store.preferences.edit().remove(LauncherIconStore.KEY).commit()
        context.getSharedPreferences(LauncherIconStore.FILE, Context.MODE_PRIVATE)
            .edit().putBoolean("unrelated_boolean", true).commit()
        assertEquals(LauncherIconMode.FOLLOW, store.read())
    }

    @Test
    fun iconChoiceIsIndependentOfTheAppearanceSettings() {
        // The appearance writer must never reach the launcher preference file, and vice versa.
        store.save(LauncherIconMode.DARK)
        AppearanceController(context).save(AppearanceSettings(mode = "light"))
        assertEquals(LauncherIconMode.DARK, LauncherIconStore.open(context).read())

        AppearanceController(context).save(AppearanceSettings())
        store.save(LauncherIconMode.FOLLOW)
        assertEquals("system", AppearanceController(context).read().mode)
        assertFalse(
            "appearance reset must not write launcher keys",
            context.getSharedPreferences(LauncherIconStore.FILE, Context.MODE_PRIVATE)
                .getAll().keys.any { it != LauncherIconStore.KEY },
        )
    }

    @Test
    fun preferenceChangeIsDeliveredToAListener() {
        var seen: String? = null
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> seen = key }
        store.register(listener)
        try {
            // commit() (not apply()) so the listener runs on this thread inside Robolectric.
            store.preferences.edit().putString(LauncherIconStore.KEY, LauncherIconMode.LIGHT.key).commit()
        } finally {
            store.unregister(listener)
        }
        assertEquals(LauncherIconStore.KEY, seen)
    }

    // ---------------------------------------------------------------- mapping

    @Test
    fun followTracksThePageTheme() {
        assertEquals(LauncherEntry.DARK, LauncherIconPlan.targetOf(LauncherIconMode.FOLLOW, "dark"))
        assertEquals(LauncherEntry.LIGHT, LauncherIconPlan.targetOf(LauncherIconMode.FOLLOW, "light"))
        assertEquals(LauncherEntry.SYSTEM, LauncherIconPlan.targetOf(LauncherIconMode.FOLLOW, "system"))
        // Unknown page modes are treated as "system", not as a crash.
        assertEquals(LauncherEntry.SYSTEM, LauncherIconPlan.targetOf(LauncherIconMode.FOLLOW, "sepia"))
    }

    @Test
    fun forcedChoicesIgnoreThePageTheme() {
        for (pageMode in listOf("system", "light", "dark", "sepia")) {
            assertEquals(LauncherEntry.DARK, LauncherIconPlan.targetOf(LauncherIconMode.DARK, pageMode))
            assertEquals(LauncherEntry.LIGHT, LauncherIconPlan.targetOf(LauncherIconMode.LIGHT, pageMode))
        }
    }

    @Test
    fun effectiveDarkMatchesWhatThePageShows() {
        assertTrue(LauncherIconPlan.effectiveDark(LauncherIconMode.DARK, "light", false))
        assertFalse(LauncherIconPlan.effectiveDark(LauncherIconMode.LIGHT, "dark", true))
        assertTrue(LauncherIconPlan.effectiveDark(LauncherIconMode.FOLLOW, "system", true))
        assertFalse(LauncherIconPlan.effectiveDark(LauncherIconMode.FOLLOW, "system", false))
        assertTrue(LauncherIconPlan.effectiveDark(LauncherIconMode.FOLLOW, "dark", false))
        assertFalse(LauncherIconPlan.effectiveDark(LauncherIconMode.FOLLOW, "light", true))
    }

    @Test
    fun planEnablesTheTargetFirstAndDisablesEverythingElse() {
        val plan = LauncherIconPlan.of(LauncherIconMode.DARK, "light", false, current = LauncherEntry.SYSTEM)
        assertEquals(LauncherEntry.DARK, plan.enable.first())
        assertEquals(listOf(LauncherEntry.DARK),plan.enable)
        assertEquals(listOf(LauncherEntry.SYSTEM, LauncherEntry.LIGHT), plan.disable)
        assertEquals(mapOf(LauncherEntry.SYSTEM to false, LauncherEntry.DARK to true, LauncherEntry.LIGHT to false), plan.finalStates)
        assertFalse(plan.isSettled)
    }

    @Test
    fun planIsSettledWhenTheTargetIsAlreadyEnabled() {
        val plan = LauncherIconPlan.of(LauncherIconMode.FOLLOW, "system", true, current = LauncherEntry.SYSTEM)
        assertTrue(plan.isSettled)
        assertTrue(plan.enable.first() == plan.target)
    }

    @Test
    fun fallbackIsTheManifestDefaultState() {
        val plan = LauncherIconPlan.of(LauncherIconMode.LIGHT, "dark", true, current = null)
        assertEquals(
            mapOf(LauncherEntry.SYSTEM to true, LauncherEntry.DARK to false, LauncherEntry.LIGHT to false),
            plan.fallback,
        )
        assertEquals(1, plan.fallback.values.count { it })
    }

    @Test
    fun everyAliasEntryHasAManifestIconAndMatchesTheManifestDeclaration() {
        assertEquals(
            listOf("com.glasssign.app.LauncherSystem", "com.glasssign.app.LauncherDark", "com.glasssign.app.LauncherLight"),
            LauncherEntry.all.map { it.className },
        )
        assertEquals(listOf("mipmap/ic_launcher", "mipmap/ic_launcher_dark", "mipmap/ic_launcher_light"),
            LauncherEntry.all.map { it.iconResource })
        assertEquals(1, LauncherEntry.all.count { it.exportedByDefault })
    }

    @Test
    fun aliasSwitchIsAtomicOnlyFromApi33() {
        assertFalse(atomicAliasSwitch(26))
        assertFalse(atomicAliasSwitch(32))
        assertTrue(atomicAliasSwitch(33))
        assertTrue(atomicAliasSwitch(35))
    }

    @Test
    fun manifestDeclaresOneLauncherAliasPerModeWithTheExpectedIcons() {
        // Read the real source manifest instead of a hand-built fixture: if the aliases or the
        // `activity-alias` binding drift, this fails even when every unit test above still passes.
        val manifest = findManifest()
        val aliases = Regex("<activity-alias\\b[^>]*>.*?</activity-alias>", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest).toList()
        assertEquals("three launcher aliases", 3, aliases.size)
        fun attr(block: String, name: String) =
            Regex("android:$name=\"([^\"]*)\"").find(block)?.groupValues?.get(1)
        val byName = aliases.associate { attr(it.value, "name")!! to it.value }
        assertEquals(
            setOf(".LauncherSystem", ".LauncherDark", ".LauncherLight"),
            byName.keys,
        )
        // Every alias targets the real activity and declares the same label.
        byName.values.forEach { block ->
            assertEquals(".MainActivity", attr(block, "targetActivity"))
            assertEquals("@string/app_name", attr(block, "label"))
            assertTrue(block.contains("android.intent.category.LAUNCHER"))
        }
        assertEquals("@mipmap/ic_launcher", attr(byName[".LauncherSystem"]!!, "icon"))
        assertEquals("@mipmap/ic_launcher_dark", attr(byName[".LauncherDark"]!!, "icon"))
        assertEquals("@mipmap/ic_launcher_light", attr(byName[".LauncherLight"]!!, "icon"))
        // The app entry point itself must not keep a MAIN/LAUNCHER filter: two launcher entries would
        // appear at once, and the alias switch could never leave exactly one.
        val mainActivity = Regex("<activity\\s+[^>]*\\.MainActivity[^>]*>", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest).toList()
        assertEquals(1, mainActivity.size)
        assertFalse(mainActivity.first().value.contains("android.intent.category.LAUNCHER"))
        assertTrue(mainActivity.first().value.contains("android:exported=\"true\""))
        // Exactly one alias is enabled at install time.
        assertEquals(1, byName.values.count { attr(it, "enabled") == "true" })
        assertEquals(2, byName.values.count { attr(it, "enabled") == "false" })
    }

    private fun findManifest(): String {
        // Gradle runs this JVM test with the module dir as working directory.
        val candidates = listOf(
            java.io.File("src/main/AndroidManifest.xml"),
            java.io.File("app/src/main/AndroidManifest.xml"),
            java.io.File("android/app/src/main/AndroidManifest.xml"),
        )
        val manifest = candidates.firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found from ${java.io.File(".").absolutePath}")
        return manifest.readText()
    }

    // ---------------------------------------------------------------- switching

    @Test
    fun legacySwitchEnablesTheTargetBeforeDisablingTheOthers() {
        val switcher = FakeSwitcher(current = LauncherEntry.SYSTEM, atomicSupported = false)
        val manager = LauncherIconManager(switcher, atomicSwitch = false)
        val outcome = manager.apply(LauncherIconMode.DARK, "light", false)!!
        assertTrue(outcome.applied)
        assertEquals(LauncherEntry.DARK, outcome.target)
        // Exactly one enabled afterwards, and the first write was the enable of the target.
        assertEquals(setOf(LauncherEntry.DARK), switcher.enabledSet())
        assertEquals("enable:DARK", switcher.writes.first())
        assertTrue(switcher.writes.drop(1).all { it.startsWith("disable:") })
        assertFalse(switcher.usedAtomic)
    }

    @Test
    fun api33SwitchesTheWholeSetInOneCall() {
        val switcher = FakeSwitcher(current = LauncherEntry.SYSTEM, atomicSupported = true)
        val manager = LauncherIconManager(switcher, atomicSwitch = true)
        manager.apply(LauncherIconMode.LIGHT, "system", false)
        assertEquals(setOf(LauncherEntry.LIGHT), switcher.enabledSet())
        assertTrue(switcher.usedAtomic)
        assertEquals(1, switcher.batchCalls)
        assertEquals(
            mapOf(LauncherEntry.SYSTEM to false, LauncherEntry.DARK to false, LauncherEntry.LIGHT to true),
            switcher.lastBatch,
        )
    }

    @Test
    fun identicalSelectionNeverReachesTheComponentManagerAgain() {
        val switcher = FakeSwitcher(current = LauncherEntry.SYSTEM, atomicSupported = false)
        val manager = LauncherIconManager(switcher, atomicSwitch = false)
        assertTrue(manager.apply(LauncherIconMode.DARK, "system", false)!!.applied)
        val writes = switcher.writes.toList()
        // Same choice after an unrelated repaint / theme repaint: cached, no binder traffic.
        assertNull(manager.apply(LauncherIconMode.DARK, "system", false))
        assertEquals(writes, switcher.writes)
        // Same choice but the page theme moved: for a forced choice the target does not change.
        assertTrue(manager.apply(LauncherIconMode.DARK, "light", false)!!.alreadySettled)
        assertEquals(writes,switcher.writes)
    }

    @Test fun reopeningRepairsTwoEnabledEntriesAfterAnInterruptedLegacySwitch() {
        val switcher=FakeSwitcher(current=LauncherEntry.SYSTEM,atomicSupported=true)
        switcher.setEnabled(mapOf(LauncherEntry.SYSTEM to true,LauncherEntry.DARK to true,LauncherEntry.LIGHT to false),true)
        val manager=LauncherIconManager(switcher,atomicSwitch=true)
        assertTrue(manager.apply(LauncherIconMode.FOLLOW,"system",true)!!.applied)
        assertEquals(setOf(LauncherEntry.SYSTEM),switcher.enabledSet())
    }

    @Test fun corruptPreferenceTypeFallsBackToFollow() {
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences(LauncherIconStore.FILE,Context.MODE_PRIVATE)
        prefs.edit().putInt(LauncherIconStore.KEY,42).commit()
        assertEquals(LauncherIconMode.FOLLOW,LauncherIconStore(prefs).read())
    }

    @Test
    fun followReactsToAPageThemeFlip() {
        val switcher = FakeSwitcher(current = LauncherEntry.SYSTEM, atomicSupported = false)
        val manager = LauncherIconManager(switcher, atomicSwitch = false)
        assertTrue(manager.apply(LauncherIconMode.FOLLOW, "system", false)!!.alreadySettled)
        // The user forces a dark page theme while the icon follows the app theme.
        val outcome = manager.apply(LauncherIconMode.FOLLOW, "dark", false)!!
        assertTrue(outcome.applied)
        assertEquals(LauncherEntry.DARK, outcome.target)
        assertEquals(setOf(LauncherEntry.DARK), switcher.enabledSet())
    }

    @Test
    fun alreadyCorrectDeviceStateIsSettledWithoutAnyWrite() {
        val switcher = FakeSwitcher(current = LauncherEntry.LIGHT, atomicSupported = false)
        val manager = LauncherIconManager(switcher, atomicSwitch = false)
        val outcome = manager.apply(LauncherIconMode.LIGHT, "system", true)!!
        assertTrue(outcome.alreadySettled)
        assertTrue(switcher.writes.isEmpty())
    }

    @Test
    fun failedSwitchRestoresTheDefaultEntryInsteadOfLeavingNone() {
        val switcher = FakeSwitcher(current = LauncherEntry.SYSTEM, atomicSupported = true, failOn = "batch")
        val manager = LauncherIconManager(switcher, atomicSwitch = true)
        val outcome = manager.apply(LauncherIconMode.DARK, "system", false)!!
        assertFalse(outcome.applied)
        // The fallback ran and left exactly one launcher entry: the manifest default.
        assertEquals(setOf(LauncherEntry.SYSTEM), switcher.enabledSet())
        // A failed choice is not cached, so the next attempt tries again (and fails the same way, not silently).
        val retry = manager.apply(LauncherIconMode.DARK, "system", false)
        assertEquals(false, retry?.applied)
    }

    @Test
    fun legacyPartialFailureFallsBackToTheSingleDefaultAlias() {
        val switcher = FakeSwitcher(current = LauncherEntry.SYSTEM, atomicSupported = false, failOn = "disable")
        val manager = LauncherIconManager(switcher, atomicSwitch = false)
        val outcome = manager.apply(LauncherIconMode.LIGHT, "system", false)!!
        assertFalse(outcome.applied)
        assertEquals("exactly one launcher entry must survive", 1, switcher.enabledSet().size)
    }

    @Test
    fun restoreDefaultDropsTheCacheSoTheNextApplyReReadsTheDevice() {
        val switcher = FakeSwitcher(current = LauncherEntry.SYSTEM, atomicSupported = false)
        val manager = LauncherIconManager(switcher, atomicSwitch = false)
        assertTrue(manager.apply(LauncherIconMode.LIGHT, "system", false)!!.applied)
        // Same call again is cached; after restoreDefault the device state changed, so it must not be.
        assertNull(manager.apply(LauncherIconMode.LIGHT, "system", false))
        manager.restoreDefault()
        assertEquals(setOf(LauncherEntry.SYSTEM), switcher.enabledSet())
        assertTrue(manager.apply(LauncherIconMode.LIGHT, "system", false)!!.applied)
        assertEquals(setOf(LauncherEntry.LIGHT), switcher.enabledSet())
    }

    /** In-memory package manager double; records call order so the legacy path is checked, not mirrored. */
    private class FakeSwitcher(
        var current: LauncherEntry,
        val atomicSupported: Boolean,
        val failOn: String? = null,
    ) : LauncherComponentSwitcher {
        val writes = mutableListOf<String>()
        var usedAtomic = false
        var batchCalls = 0
        var lastBatch: Map<LauncherEntry, Boolean> = emptyMap()
        private val states = LauncherEntry.all.associateWith { it == current }.toMutableMap()
        private var restoring = false

        fun enabledSet() = states.filterValues { it }.keys

        override fun enabled(entry: LauncherEntry): Boolean? = states[entry]

        override fun setEnabled(states: Map<LauncherEntry, Boolean>, atomic: Boolean) {
            if (atomic) {
                usedAtomic = true
                batchCalls++
                lastBatch = states.toMap()
                if (!restoring && (failOn == "batch" || failOn == "all")) error("injected batch failure")
                this.states.putAll(states)
                return
            }
            if (!restoring && failOn == "all") error("injected failure")
            states.entries.sortedByDescending { it.value }.forEach { (entry, on) ->
                writes += (if (on) "enable:" else "disable:") + entry.name
                if (on) this.states[entry] = true
                else {
                    if (!restoring && failOn == "disable") error("injected disable failure")
                    this.states[entry] = false
                }
            }
        }

        override fun restoreDefault() {
            restoring = true
            try {
                states.putAll(LauncherEntry.all.associateWith { it.exportedByDefault })
            } finally {
                restoring = false
            }
        }
    }
}
