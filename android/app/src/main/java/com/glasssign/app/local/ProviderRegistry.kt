package com.glasssign.app.local

import android.content.Context
import android.content.SharedPreferences
import com.glasssign.app.BuildConfig

/*
 * Built-in provider registry.
 *
 * It describes the four adapters this build really ships plus the user's per-provider switch. It is not a
 * third-party plugin system: no download, no dynamic loading, no network manifest.
 *
 * Two-level gate: this registry is level 1 (whole provider); `accounts.enabled` / `auto_enabled` and
 * `game_bindings.enabled` / `auto_sign` remain level 2 and are not re-implemented. Disabling a provider only gates
 * network work — it never touches accounts, roles, credentials or records.
 */

/** What an adapter actually implements. Declared per adapter from source, never as a blanket claim. */
enum class ProviderCapability {
    /** `ProviderAdapter.targets()` is implemented. */
    ROLE_DISCOVERY,
    /** `ProviderAdapter.checkin()` performs a real (or accepted) submission. */
    SIGN,
    /** The adapter overrides `queryStatus()` with a real read-only status read. */
    QUERY_STATUS,
}

data class ProviderManifest(
    val id: String,
    val title: String,
    val version: String,
    val minHostVersion: String,
    val capabilities: Set<ProviderCapability>,
    /** User-facing Chinese; bounded to what the adapter is allowed to do. */
    val permissionSummary: String,
    val description: String,
    /** Game catalog ids of this provider. */
    val games: List<String> = emptyList(),
) {
    fun supports(capability: ProviderCapability): Boolean = capability in capabilities
    val canQueryStatus: Boolean get() = supports(ProviderCapability.QUERY_STATUS)
}

/**
 * Compile-time facts of the shipped adapters.
 *
 * Capabilities were read from [Providers.kt]: miyoushe `:56/:68/:76`, skland `:153/:157/:179`,
 * tagged `:235/:259/:283`, kurobbs `:324/:348` — KuroBBS has no `queryStatus` override, so it must not claim one.
 */
object BuiltinProviders {
    const val MIYOUSHE = "miyoushe"
    const val SKLAND = "skland"
    const val TAGGED = "tagged"
    const val KUROBBS = "kurobbs"

    /** Real host version. Never hard-coded: a release build reports its own value. */
    val HOST_VERSION: String get() = BuildConfig.VERSION_NAME

    private val SIGN_AND_DISCOVERY = setOf(
        ProviderCapability.ROLE_DISCOVERY,
        ProviderCapability.SIGN,
        ProviderCapability.QUERY_STATUS,
    )

    val all: List<ProviderManifest> = listOf(
        ProviderManifest(
            id = MIYOUSHE,
            title = "米游社",
            version = "1",
            minHostVersion = "0.7.0",
            capabilities = SIGN_AND_DISCOVERY,
            permissionSummary = "读取本机米游社 Cookie；查询角色；提交签到；提交后只读查询一次官方签到状态",
            description = "官方状态来自 /event/luna/info。签到结果以官方记录为准。",
            games = listOf("genshin", "honkai_sr", "zzz", "honkai3"),
        ),
        ProviderManifest(
            id = SKLAND,
            title = "森空岛",
            version = "1",
            minHostVersion = "0.7.0",
            capabilities = SIGN_AND_DISCOVERY,
            permissionSummary = "读取本机森空岛凭据；查询角色与社区检票项；提交签到；读取一次官方日历",
            description = "社区检票项没有独立的官方状态查询，提交后会如实标记为待确认。",
            games = listOf("arknights", "endfield"),
        ),
        ProviderManifest(
            id = TAGGED,
            title = "塔吉多",
            version = "1",
            minHostVersion = "0.7.0",
            capabilities = SIGN_AND_DISCOVERY,
            permissionSummary = "读取本机塔吉多令牌；查询角色；提交签到；在可确认角色归属时读取一次官方状态",
            description = "无法确认角色归属时不做状态推断，结果保留为待确认。",
            games = listOf("nte"),
        ),
        ProviderManifest(
            id = KUROBBS,
            title = "库街区",
            version = "1",
            minHostVersion = "0.7.0",
            capabilities = setOf(ProviderCapability.ROLE_DISCOVERY, ProviderCapability.SIGN),
            permissionSummary = "读取本机库街区令牌；查询角色；提交签到",
            description = "没有独立的官方状态查询接口；提交成功只记为「已提交待确认」，不显示为官方已确认。",
            games = listOf("wuwa", "pgr"),
        ),
    )

    private val byId: Map<String, ProviderManifest> = all.associateBy { it.id }

    val ids: List<String> get() = all.map { it.id }

    fun find(id: String): ProviderManifest? = byId[id]

    fun isBuiltin(id: String): Boolean = byId.containsKey(id)
}

/** Whether a provider may execute network work. Injected into [LocalSignEngine]; production uses [ProviderRegistry]. */
interface ProviderExecutionGate {
    fun isExecutionAllowed(providerId: String): Boolean
    fun blockedReason(providerId: String): String?

    companion object {
        /** Compatibility default used by every pre-existing engine call site. */
        val ALLOW_ALL: ProviderExecutionGate = AllowAllProviderExecutionGate
        const val PAUSED_REASON = "该平台适配器已停用；本次未读取授权、未发送请求。账号、角色与历史记录均保留"
    }
}

object AllowAllProviderExecutionGate : ProviderExecutionGate {
    override fun isExecutionAllowed(providerId: String): Boolean = true
    override fun blockedReason(providerId: String): String? = null
}

/**
 * Strict 3-part numeric SemVer. Missing segments are 0 ("0.7" == "0.7.0"); pre-release/build suffixes are ignored.
 * Anything that is not purely numeric, or has more than 3 segments, is INVALID (null) and must be treated as
 * incompatible — never coerced into a number.
 */
internal object SemVer {
    private val PATTERN = Regex("""^(\d{1,9})(?:\.(\d{1,9}))?(?:\.(\d{1,9}))?(?:[-+].*)?$""")

    fun parse(raw: String?): Triple<Int, Int, Int>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val m = PATTERN.matchEntire(text) ?: return null
        val parts = m.groupValues
        val major = parts[1].toIntOrNull() ?: return null
        val minor = parts[2].ifEmpty { "0" }.toIntOrNull() ?: return null
        val patch = parts[3].ifEmpty { "0" }.toIntOrNull() ?: return null
        return Triple(major, minor, patch)
    }

    /** True when [version] is a valid SemVer at or above [minVersion]. An unparseable side is false. */
    fun atLeast(version: String?, minVersion: String?): Boolean {
        val v = parse(version) ?: return false
        val m = parse(minVersion) ?: return false
        return compare(v, m) >= 0
    }

    /** Numeric comparison per segment, so 0.10.0 > 0.9.9. */
    fun compare(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        if (a.first != b.first) return a.first.compareTo(b.first)
        if (a.second != b.second) return a.second.compareTo(b.second)
        return a.third.compareTo(b.third)
    }
}

/**
 * Persisted per-provider switch. Own SharedPreferences file, one boolean per provider id; never in the sign database
 * and never in `plugins_v1`.
 *
 * Default is ENABLED so an upgrade with no stored choice behaves exactly as before. Any read failure is fail-closed
 * for a stored "disabled" choice: a corrupt prefs file must never silently re-enable a paused provider.
 */
class ProviderRegistry(
    private val prefs: SharedPreferences?,
    private val manifests: List<ProviderManifest> = BuiltinProviders.all,
    private val hostVersionProvider: () -> String = { BuiltinProviders.HOST_VERSION },
) : ProviderExecutionGate {

    constructor(context: Context, manifests: List<ProviderManifest> = BuiltinProviders.all) :
        this(runCatching { context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }.getOrNull(), manifests)

    private val byId: Map<String, ProviderManifest> = manifests.associateBy { it.id }

    val providers: List<ProviderManifest> get() = manifests

    fun manifest(providerId: String): ProviderManifest? = byId[providerId]

    fun keyOf(providerId: String): String = KEY_PREFIX + providerId

    /**
     * Enabled = the adapter may be handed a credential and may run. Unknown ids are always false.
     *
     * FAIL-CLOSED RULE: any condition that prevents reading a definite stored choice yields DISABLED — a wrong-typed
     * value, a corrupt prefs file, or no preference store at all. A paused provider must never be woken by a read
     * failure. Only a store that is readable AND holds no value for this key yields the enabled default.
     */
    @Synchronized
    override fun isExecutionAllowed(providerId: String): Boolean {
        if (!byId.containsKey(providerId)) return false
        val store = prefs ?: return false
        val key = keyOf(providerId)
        return try {
            if (!store.contains(key)) true else store.getBoolean(key, true)
        } catch (_: ClassCastException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun isEnabled(providerId: String): Boolean = isExecutionAllowed(providerId)

    override fun blockedReason(providerId: String): String? = when {
        !byId.containsKey(providerId) -> "该平台不在内置适配器清单中，已拒绝执行；未读取授权、未发送请求"
        !isExecutionAllowed(providerId) -> ProviderExecutionGate.PAUSED_REASON
        else -> null
    }

    /** Persists the switch. False for an unknown id or a write that did not land. */
    @Synchronized
    fun setEnabled(providerId: String, enabled: Boolean): Boolean {
        if (!byId.containsKey(providerId)) return false
        val store = prefs ?: return false
        return runCatching { store.edit().putBoolean(keyOf(providerId), enabled).commit() }.getOrDefault(false)
    }

    /** Removes the stored choice, restoring the documented default (enabled). */
    @Synchronized
    fun reset(providerId: String): Boolean {
        if (!byId.containsKey(providerId)) return false
        val store = prefs ?: return false
        return runCatching { store.edit().remove(keyOf(providerId)).commit() }.getOrDefault(false)
    }

    /** Host-version compatibility using real SemVer comparison. A broken version on either side is incompatible. */
    fun isCompatible(providerId: String): Boolean {
        val m = byId[providerId] ?: return false
        val host = runCatching { hostVersionProvider() }.getOrNull()
        return SemVer.atLeast(host, m.minHostVersion)
    }

    fun snapshot(): List<ProviderState> = manifests.map { ProviderState(it, isExecutionAllowed(it.id), isCompatible(it.id)) }

    /** Change notification for Compose/VM. Returns the listener for [stopListening]. */
    fun listen(onChange: (String) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener? {
        val store = prefs ?: return null
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            val id = manifests.firstOrNull { key == keyOf(it.id) }?.id
            if (id != null) onChange(id)
        }
        runCatching { store.registerOnSharedPreferenceChangeListener(listener) }
        return listener
    }

    fun stopListening(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener == null) return
        val store = prefs ?: return
        runCatching { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        const val PREFS_NAME = "provider_states_v1"
        const val KEY_PREFIX = "provider_enabled_"
    }
}

data class ProviderState(
    val manifest: ProviderManifest,
    val enabled: Boolean,
    val compatible: Boolean,
) {
    val enabledLabel: String get() = if (enabled) "已启用" else "已停用（任务暂停）"

    /** States the real capability without implying that a missing endpoint is an authorization problem. */
    val capabilityLabel: String get() = buildList {
        if (manifest.supports(ProviderCapability.SIGN)) add("签到")
        if (manifest.supports(ProviderCapability.ROLE_DISCOVERY)) add("角色读取")
        if (manifest.supports(ProviderCapability.QUERY_STATUS)) add("官方状态查询") else add("无独立状态查询")
    }.joinToString(" · ")
}
