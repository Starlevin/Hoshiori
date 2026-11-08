package com.glasssign.app.local

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Wiring check for the production object graph ([LocalServices]).
 *
 * [LocalServices] is a process-wide singleton (`LocalServices.get`), so this test never asserts through the shared
 * instance's mutable state: it builds the SAME stores the graph uses, from this test's own Robolectric application
 * context. That keeps the assertions real without depending on whatever another test left in the singleton.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class LocalServicesWiringTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        providerPrefs().edit().clear().commit()
        labPrefs().edit().clear().commit()
    }

    @After fun cleanup() {
        providerPrefs().edit().clear().commit()
        labPrefs().edit().clear().commit()
    }

    private fun providerPrefs() = context.getSharedPreferences(ProviderRegistry.PREFS_NAME, Context.MODE_PRIVATE)
    private fun labPrefs() = context.getSharedPreferences(LabStore.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The singleton exists and exposes the four built-in providers PLUS the one plugin platform
     * (领签, local simulation only) plus the single lab flag. Immutable shape (ids), not mutable choices.
     */
    @Test fun localServicesExposeTheRegistryAndTheLabStore() {
        val services = LocalServices.get(context)
        assertNotNull(services.registry)
        assertNotNull(services.labs)
        assertEquals(5, services.registry.providers.size)
        assertEquals(
            listOf("miyoushe", "skland", "tagged", "kurobbs", "lingqian"),
            services.registry.providers.map { it.id },
        )
        assertEquals(listOf(LabStore.FLAG_WATER_DROP_GLASS), services.labs.flags.map { it.id })
    }

    /** A pause written through the registry is what the engine's gate reads: both sides use the same prefs key. */
    @Test fun registrySwitchIsStoredUnderTheKeyTheGateReads() {
        val registry = ProviderRegistry(context)
        assertTrue(registry.isExecutionAllowed("miyoushe"))
        assertTrue(registry.setEnabled("miyoushe", false))

        // Read back through a fresh instance (same prefs file, i.e. what the production gate sees after restart).
        val reloaded = ProviderRegistry(context)
        assertFalse(reloaded.isExecutionAllowed("miyoushe"))
        assertEquals(ProviderExecutionGate.PAUSED_REASON, reloaded.blockedReason("miyoushe"))
        assertEquals("provider_enabled_miyoushe", ProviderRegistry(context).keyOf("miyoushe"))
        assertTrue(registry.setEnabled("miyoushe", true))
        assertTrue(ProviderRegistry(context).isExecutionAllowed("miyoushe"))
    }

    /** The three persistence files are independent: no store writes into another's namespace. */
    @Test fun storesUseSeparatePreferenceFiles() {
        // Both stores are built against THIS test's context, not the process singleton.
        val registry = ProviderRegistry(context)
        val labs = LabStore(context)

        assertTrue(registry.setEnabled("tagged", false))
        assertTrue(labs.setEnabled(LabStore.FLAG_WATER_DROP_GLASS, true))

        assertFalse("provider switch lands in its own file", providerPrefs().getBoolean("provider_enabled_tagged", true))
        assertFalse("the lab file must not carry provider switches", labPrefs().contains("provider_enabled_tagged"))
        assertEquals("lab quality lands in its own file","FULL",labPrefs().getString("lab_water_drop_glass_quality_v2", null))
        assertFalse("the provider file must not carry lab flags", providerPrefs().contains("lab_water_drop_glass_quality_v2"))

        // Neither store touches the sign settings file (owned by the schedule manager).
        val settings = context.getSharedPreferences("local_settings_v4", Context.MODE_PRIVATE)
        assertFalse(settings.contains("provider_enabled_tagged"))
        assertFalse(settings.contains("lab_water_drop_glass_quality_v2"))
    }

    /** Storing a switch does not touch the sign database file at all. */
    @Test fun providerSwitchWritesNoAccountDatabase() {
        val registry = ProviderRegistry(context)
        val dbFile = context.getDatabasePath("local_sign_v4.db")
        val existedBefore = dbFile.exists()
        registry.setEnabled("skland", false)
        registry.setEnabled("skland", true)
        assertEquals("a provider switch must not create the sign database", existedBefore, dbFile.exists())
    }
}
