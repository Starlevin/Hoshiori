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
 * Provider registry contract.
 *
 * Asserts the four built-in ids, their REAL capabilities, the enabled default, persistence, unknown-id rejection,
 * SemVer host compatibility and the fail-closed read rule. Nothing here claims a real platform sign-in works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ProviderRegistryTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(ProviderRegistry.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun cleanup() {
        context.getSharedPreferences(ProviderRegistry.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun registersExactlyTheFourBuiltinAdapters() {
        assertEquals(listOf("miyoushe", "skland", "tagged", "kurobbs"), BuiltinProviders.ids)
        assertFalse(BuiltinProviders.isBuiltin("lingqian"))
        assertFalse(BuiltinProviders.isBuiltin(""))
    }

    @Test fun capabilitiesMatchTheAdaptersThatReallyImplementThem() {
        assertTrue(BuiltinProviders.find("miyoushe")!!.canQueryStatus)
        assertTrue(BuiltinProviders.find("skland")!!.canQueryStatus)
        assertTrue(BuiltinProviders.find("tagged")!!.canQueryStatus)
        assertFalse("KuroBBS has no queryStatus override", BuiltinProviders.find("kurobbs")!!.canQueryStatus)

        BuiltinProviders.all.forEach { m ->
            assertTrue("${m.id} must declare ROLE_DISCOVERY", m.supports(ProviderCapability.ROLE_DISCOVERY))
            assertTrue("${m.id} must declare SIGN", m.supports(ProviderCapability.SIGN))
            assertTrue(m.permissionSummary.isNotBlank())
            assertTrue(m.description.isNotBlank())
            m.games.forEach { g -> assertEquals(m.id, com.glasssign.app.data.GameCatalog.find(g)?.provider) }
        }
        assertEquals(listOf("wuwa", "pgr"), BuiltinProviders.find("kurobbs")!!.games)
    }

    /** A missing status endpoint is a capability limit, never described as an expired/absent authorization. */
    @Test fun missingQueryCapabilityIsNotDescribedAsAuthFailure() {
        val row = ProviderRegistry(context).snapshot().single { it.manifest.id == "kurobbs" }
        assertTrue(row.capabilityLabel.contains("无独立状态查询"))
        val text = row.manifest.permissionSummary + row.manifest.description
        listOf("失效", "过期", "未授权", "缺少授权").forEach {
            assertFalse("capability text must not imply an auth problem: $it", text.contains(it))
        }
    }

    @Test fun defaultIsEnabledAndSurvivesARestart() {
        val first = ProviderRegistry(context)
        BuiltinProviders.ids.forEach { id ->
            assertTrue("$id must be enabled by default", first.isExecutionAllowed(id))
            assertNull(first.blockedReason(id))
        }
        assertTrue(first.setEnabled("tagged", false))
        val second = ProviderRegistry(context)
        assertFalse(second.isExecutionAllowed("tagged"))
        assertEquals(ProviderExecutionGate.PAUSED_REASON, second.blockedReason("tagged"))
        assertTrue(second.isExecutionAllowed("miyoushe"))
    }

    @Test fun resetRestoresTheDocumentedDefault() {
        val registry = ProviderRegistry(context)
        registry.setEnabled("skland", false)
        assertFalse(registry.isExecutionAllowed("skland"))
        assertTrue(registry.reset("skland"))
        assertTrue(ProviderRegistry(context).isExecutionAllowed("skland"))
    }

    @Test fun unknownProviderIdIsRejectedAndNeverEnabled() {
        val registry = ProviderRegistry(context)
        assertFalse(registry.isExecutionAllowed("not_a_provider"))
        assertFalse(registry.setEnabled("not_a_provider", true))
        assertFalse(registry.reset("not_a_provider"))
        assertNotNull(registry.blockedReason("not_a_provider"))
        assertTrue(registry.blockedReason("not_a_provider")!!.contains("未读取授权"))
    }

    /**
     * A stored value of the wrong type (or a corrupt prefs file) must NOT re-enable a paused provider.
     * The only case that yields "enabled" is the absence of any stored value.
     */
    @Test fun corruptStoredValueFailsClosedInsteadOfEnabling() {
        val prefs = context.getSharedPreferences(ProviderRegistry.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(ProviderRegistry.KEY_PREFIX + "tagged", "not-a-boolean").commit()
        val registry = ProviderRegistry(context)
        assertFalse("a wrong-typed value must not count as enabled", registry.isExecutionAllowed("tagged"))
        assertNotNull(registry.blockedReason("tagged"))
        // A provider with nothing stored keeps the enabled default.
        assertTrue(registry.isExecutionAllowed("miyoushe"))
    }

    /** No backing preference file at all is also fail-closed. */
    @Test fun missingPreferenceFileFailsClosed() {
        val registry = ProviderRegistry(null)
        assertFalse(registry.isExecutionAllowed("tagged"))
        assertFalse(registry.setEnabled("tagged", true))
        assertFalse(registry.reset("tagged"))
        // Unknown ids stay false, and the registry stays queryable instead of throwing.
        assertFalse(registry.isExecutionAllowed("whatever"))
        assertEquals(4, registry.providers.size)
    }

    /** Strict 3-part SemVer: 0.10.0 > 0.9.9 — string comparison would get this wrong. */
    @Test fun hostCompatibilityUsesNumericSemver() {
        fun registryAt(host: String) = ProviderRegistry(prefs = null, hostVersionProvider = { host })
        assertTrue("0.10.0 > 0.9.9", registryAt("0.10.0").isCompatible("miyoushe"))
        assertTrue("0.9.9 >= 0.7.0", registryAt("0.9.9").isCompatible("miyoushe"))
        assertTrue(registryAt("0.7.0").isCompatible("miyoushe"))
        assertFalse(registryAt("0.6.9").isCompatible("miyoushe"))
        assertTrue("missing segments default to 0", registryAt("0.7").isCompatible("miyoushe"))
        assertTrue("pre-release suffix is ignored", registryAt("0.7.9-preview.5").isCompatible("miyoushe"))
        // An unparseable host version is incompatible, never optimistically accepted.
        assertFalse(registryAt("preview").isCompatible("miyoushe"))
        assertFalse(registryAt("").isCompatible("miyoushe"))
        assertFalse(registryAt("0.1.2.3").isCompatible("miyoushe"))
        // Unknown provider has no compatibility claim.
        assertFalse(registryAt("1.0.0").isCompatible("nope"))
    }

    @Test fun semverParseRejectsGarbage() {
        assertNotNull(SemVer.parse("1.2.3"))
        assertNotNull(SemVer.parse("1.2"))
        assertNotNull(SemVer.parse("1"))
        assertNotNull(SemVer.parse("0.7.9-preview.5"))
        assertNull(SemVer.parse("abc"))
        assertNull(SemVer.parse(""))
        assertNull(SemVer.parse("1.2.3.4"))
        assertNull(SemVer.parse("1..3"))
        assertNull(SemVer.parse("-1.0.0"))
        assertTrue(SemVer.atLeast("0.10.0", "0.9.9"))
        assertFalse(SemVer.atLeast("0.9.9", "0.10.0"))
    }

    @Test fun snapshotExposesStateWithoutCredentialMaterial() {
        val registry = ProviderRegistry(context)
        registry.setEnabled("kurobbs", false)
        val rows = registry.snapshot()
        assertEquals(4, rows.size)
        val kuro = rows.single { it.manifest.id == "kurobbs" }
        assertFalse(kuro.enabled)
        assertTrue(kuro.enabledLabel.contains("暂停"))
        assertTrue(rows.single { it.manifest.id == "miyoushe" }.capabilityLabel.contains("官方状态查询"))
        rows.forEach {
            val text = it.manifest.permissionSummary + it.manifest.description + it.capabilityLabel
            assertFalse(text.contains("cookie=", ignoreCase = true))
            assertFalse(text.contains("token=", ignoreCase = true))
        }
    }

    @Test fun registryIsItsOwnExecutionGate() {
        val registry: ProviderExecutionGate = ProviderRegistry(context)
        assertTrue(registry.isExecutionAllowed("miyoushe"))
        (registry as ProviderRegistry).setEnabled("miyoushe", false)
        assertFalse(registry.isExecutionAllowed("miyoushe"))
        assertFalse(registry.isExecutionAllowed("nope"))
    }

    @Test fun allowAllGateIsTheCompatibilityDefault() {
        val gate = ProviderExecutionGate.ALLOW_ALL
        BuiltinProviders.ids.forEach { assertTrue(gate.isExecutionAllowed(it)) }
        assertTrue(gate.isExecutionAllowed("anything_at_all"))
        assertNull(gate.blockedReason("anything_at_all"))
    }

    /** Compose/VM get change notifications instead of polling. */
    @Test fun listenReportsTheChangedProvider() {
        val registry = ProviderRegistry(context)
        val seen = mutableListOf<String>()
        val listener = registry.listen { seen.add(it) }
        try {
            registry.setEnabled("skland", false)
            assertEquals(listOf("skland"), seen)
            registry.setEnabled("skland", true)
            assertEquals(listOf("skland", "skland"), seen)
        } finally {
            registry.stopListening(listener)
        }
        // After unregistering nothing is reported.
        registry.setEnabled("skland", false)
        assertEquals(2, seen.size)
    }

    /** The host version is the real build version, not a constant baked into this file. */
    @Test fun hostVersionComesFromBuildConfig() {
        assertEquals(com.glasssign.app.BuildConfig.VERSION_NAME, BuiltinProviders.HOST_VERSION)
        assertTrue(BuiltinProviders.HOST_VERSION.isNotBlank())
    }
}
